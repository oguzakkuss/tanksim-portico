#!/usr/bin/env bash
# Preview only (no gcloud calls): bash infra/gcp/create-host.sh
# Create after reviewing the plan: bash infra/gcp/create-host.sh --apply
#
# Free Tier is an allowance, not a spending cap: eligible e2-micro hours,
# 30 GB-months of standard persistent disk and 1 GB/month outbound transfer
# from North America (excluding China/Australia destinations) have limits.
# Usage is aggregated across eligible regions and the billing account's
# projects. This script cannot verify account-wide usage or guarantee $0.
# Requires existing billing, enabled Compute/IAP APIs and sufficient access.
# No billing changes, IAM grants, API enablement or automatic host bootstrap.
# Sources checked 2026-10-28:
# https://cloud.google.com/free/docs/free-cloud-features#compute
# https://cloud.google.com/compute/docs/images/os-details
# https://cloud.google.com/sdk/gcloud/reference/compute/instances/create
# https://cloud.google.com/sdk/gcloud/reference/compute/networks/subnets/create
set -euo pipefail

apply=false
case "${1:-}" in
    '') ;;
    --apply) apply=true ;;
    *) printf 'Usage: bash %s [--apply]\n' "$0" >&2; exit 2 ;;
esac
[[ $# -le 1 ]] || { printf 'Only --apply is accepted.\n' >&2; exit 2; }

readonly project_id=tanksim-dev region=us-east1 zone=us-east1-b
readonly network_name=tanksim-net subnet_name=tanksim-subnet
readonly firewall_name=tanksim-allow-iap-ssh instance_name=tanksim-codex

network_cmd=(gcloud compute networks create "${network_name}"
    --project="${project_id}" --subnet-mode=custom --quiet)
subnet_cmd=(gcloud compute networks subnets create "${subnet_name}"
    --project="${project_id}" --network="${network_name}" --region="${region}"
    --range=10.42.0.0/24 --stack-type=IPV4_IPV6 --ipv6-access-type=EXTERNAL --quiet)
firewall_cmd=(gcloud compute firewall-rules create "${firewall_name}"
    --project="${project_id}" --network="${network_name}" --direction=INGRESS
    --priority=1000 --allow=tcp:22 --source-ranges=35.235.240.0/20
    --target-tags=tanksim-iap --quiet)
instance_cmd=(gcloud compute instances create "${instance_name}"
    --project="${project_id}" --zone="${zone}" --machine-type=e2-micro
    --provisioning-model=STANDARD --image-project=ubuntu-os-cloud
    --image-family=ubuntu-2204-lts --boot-disk-size=30GB --boot-disk-type=pd-standard
    --network="${network_name}" --subnet="${subnet_name}" --no-address
    --stack-type=IPV4_IPV6 --ipv6-network-tier=PREMIUM
    --no-service-account --no-scopes --tags=tanksim-iap --quiet)

print_command() { printf '%q ' "$@"; printf '\n'; }
printf 'Plan: one e2-micro VM, 30 GB standard disk, no external IPv4 or NAT.\n'
printf 'SSH ingress: IAP only. Internet egress: IPv6; IPv4-only sites will fail.\n'
printf 'Free Tier limits are shared and overages can be billed; this is not a $0 cap.\n\n'
print_command "${network_cmd[@]}"
print_command "${subnet_cmd[@]}"
print_command "${firewall_cmd[@]}"
print_command "${instance_cmd[@]}"

if [[ ${apply} == false ]]; then
    printf '\nDry run only. No cloud requests or changes were made.\n'
    exit 0
fi

fail() { printf 'Preflight stopped: %s\n' "$1" >&2; exit 1; }
command -v gcloud >/dev/null || fail 'Install gcloud and sign in separately first.'

billing_enabled="$(gcloud billing projects describe "${project_id}" \
    --project="${project_id}" --format='value(billingEnabled)' --quiet)"
[[ ${billing_enabled,,} == true ]] || fail 'Billing is not enabled for tanksim-dev; nothing was created.'

enabled_apis="$(gcloud services list --enabled --project="${project_id}" \
    --format='value(config.name)' --quiet)"
for required_api in compute.googleapis.com iap.googleapis.com; do
    grep -Fxq "${required_api}" <<< "${enabled_apis}" || \
        fail "Required API ${required_api} is not enabled; enable it separately."
done

# Reject even stopped VMs or unattached disks; do not silently consume another allowance.
existing_instances="$(gcloud compute instances list --project="${project_id}" --format='value(name)' --quiet)"
[[ -z ${existing_instances} ]] || fail 'Project already has VM instances; review them before creating another.'
existing_disks="$(gcloud compute disks list --project="${project_id}" --format='value(name)' --quiet)"
[[ -z ${existing_disks} ]] || fail 'Project already has disks; review storage usage before creating another.'

existing_network="$(gcloud compute networks list --project="${project_id}" \
    --filter="name=${network_name}" --format='value(name)' --quiet)"
[[ -z ${existing_network} ]] || fail 'tanksim-net already exists; inspect the previous attempt instead of rerunning.'
existing_subnet="$(gcloud compute networks subnets list --project="${project_id}" \
    --regions="${region}" --filter="name=${subnet_name}" --format='value(name)' --quiet)"
[[ -z ${existing_subnet} ]] || fail 'tanksim-subnet already exists; inspect it before retrying.'
existing_firewall="$(gcloud compute firewall-rules list --project="${project_id}" \
    --filter="name=${firewall_name}" --format='value(name)' --quiet)"
[[ -z ${existing_firewall} ]] || fail 'The planned firewall rule already exists; inspect it before retrying.'

# A failure can leave earlier resources in place. Keep them for inspection;
# never delete/recreate cloud resources automatically on a retry.
printf '\nPreflight passed. Creating the reviewed resources.\n'
"${network_cmd[@]}"
"${subnet_cmd[@]}"
"${firewall_cmd[@]}"
"${instance_cmd[@]}"
printf '\nCreated tanksim-codex. Bootstrap, sign-in and chat migration have not run.\n'
