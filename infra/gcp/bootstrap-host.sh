#!/usr/bin/env bash
# Run as the normal SSH user on an already-created Ubuntu 22.04 GCP VM:
#   bash infra/gcp/bootstrap-host.sh
# Uses sudo only for distro packages; installs Codex for the current user.
# Sources checked 2026-10-28:
# https://packages.ubuntu.com/jammy/openjdk-11-jdk-headless
# https://learn.chatgpt.com/docs/codex/cli
# https://chatgpt.com/codex/install.sh
# https://releases.openai.com/codex/channels/latest
set -euo pipefail

if [[ ${EUID} -eq 0 ]]; then
    printf 'Run this script as your normal SSH user, without sudo.\n' >&2
    exit 1
fi

source /etc/os-release
if [[ ${ID:-} != ubuntu || ${VERSION_ID:-} != 22.04 ]]; then
    printf 'This bootstrap targets Ubuntu 22.04, which supplies JDK 11 directly.\n' >&2
    exit 1
fi
command -v sudo >/dev/null || { printf 'sudo is required.\n' >&2; exit 1; }

# Keep the headless Java runtime small. No third-party APT repositories needed.
sudo apt-get -o DPkg::Lock::Timeout=120 update
sudo env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=120 \
    install --yes --no-install-recommends \
    ca-certificates curl git openjdk-11-jdk-headless tar gzip unzip xz-utils \
    bubblewrap ripgrep

# Use the official native installer: Codex does not need Node.js this way.
# Pin the first installation; preserve any existing functional Codex install.
# Override only for a new installation: TANKSIM_CODEX_VERSION=x.y.z bash ...
codex_version="${TANKSIM_CODEX_VERSION:-0.165.0}"
codex_bin="$(command -v codex || true)"
if [[ -z ${codex_bin} && -x ${HOME}/.local/bin/codex ]]; then
    codex_bin="${HOME}/.local/bin/codex"
fi

if [[ -n ${codex_bin} ]]; then
    printf 'Keeping the existing Codex installation: %s\n' "${codex_bin}"
else
    installer_file="$(mktemp)"
    trap 'rm -f -- "${installer_file}"' EXIT
    curl --fail --show-error --silent --location --retry 3 \
        --connect-timeout 15 --max-time 120 \
        https://chatgpt.com/codex/install.sh --output "${installer_file}"
    # Installer verifies release checksums. Primary downloads use releases.openai.com;
    # fallback uses GitHub, which may be unreachable from an IPv6-only VM.
    # Noninteractive mode also prevents the installer's optional Codex launch.
    CODEX_NON_INTERACTIVE=1 CODEX_INSTALL_DIR="${HOME}/.local/bin" \
        sh "${installer_file}" --release "${codex_version}"
    codex_bin="${HOME}/.local/bin/codex"
fi

java -version
javac -version
git --version
"${codex_bin}" --version
printf '\nHost tools are ready. Reconnect SSH if Codex is not yet on PATH.\n'
printf 'Project transfer, Portico setup, ChatGPT sign-in and remote access are separate steps.\n'
