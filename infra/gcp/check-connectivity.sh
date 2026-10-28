#!/usr/bin/env bash
# Run on the VM: bash infra/gcp/check-connectivity.sh
# Read-only diagnostics: DNS, unauthenticated HEAD requests and tool versions.
# No package installs, credentials, login, model requests or cloud mutations.
# Before bootstrap, missing Java/Codex is expected; rerun after installation.
# Sources: https://learn.chatgpt.com/docs/remote-connections
#          https://learn.chatgpt.com/docs/auth
#          https://learn.chatgpt.com/docs/codex/cli
set -uo pipefail

failures=0
for required_tool in curl getent timeout awk sort; do
    if ! command -v "${required_tool}" >/dev/null; then
        printf 'MISSING diagnostic tool: %s\n' "${required_tool}" >&2
        exit 1
    fi
done

probe_endpoint() {
    local url="$1" optional="${2:-false}" host addresses result status
    host="${url#*://}"
    host="${host%%/*}"
    addresses="$(timeout 10 getent ahostsv6 "${host}" 2>/dev/null |
        awk '$1 ~ /:/ && $1 !~ /^::ffff:/ {print $1}' | sort -u)"
    if [[ -z ${addresses} ]]; then
        printf 'NO AAAA: %s\n' "${host}"
        [[ ${optional} == true ]] || failures=$((failures + 1))
        return
    fi
    printf 'AAAA OK: %s\n' "${host}"
    # -q disables .curlrc; direct IPv6 avoids proxy credentials or IPv4 fallback.
    # Do not follow redirects, send cookies/auth, or download response bodies.
    if result="$(curl -q -6 --noproxy '*' --head --silent --show-error \
        --connect-timeout 8 --max-time 15 --output /dev/null \
        --write-out '%{http_code} %{remote_ip}' "${url}")"; then
        status="${result%% *}"
        printf 'REACHABLE: %s -> %s\n' "${url}" "${result}"
        if [[ ${status} == 401 || ${status} == 403 ]]; then
            printf '  Authentication/access denied; network and TLS responded, account access is untested.\n'
        elif [[ ${status} == 3* ]]; then
            printf '  Redirect returned; destination was not followed.\n'
        elif [[ ${status} == 5* ]]; then
            printf '  Server error; retry before relying on this endpoint.\n'
            [[ ${optional} == true ]] || failures=$((failures + 1))
        fi
    else
        printf 'CONNECTION FAILED: %s\n' "${url}" >&2
        [[ ${optional} == true ]] || failures=$((failures + 1))
    fi
}

printf 'Direct IPv6 checks; an HTTP response alone does not prove Codex login or inference.\n'
if command -v ip >/dev/null; then
    printf '\nIPv6 default route:\n'
    ip -6 route show default
fi

for endpoint in \
    https://chatgpt.com/codex/install.sh \
    https://releases.openai.com/codex/channels/latest \
    https://auth.openai.com/ \
    https://chatgpt.com/ \
    https://api.openai.com/v1/models \
    https://archive.ubuntu.com/ubuntu/ \
    https://security.ubuntu.com/ubuntu/ \
    https://packages.cloud.google.com/; do
    probe_endpoint "${endpoint}"
done

# Inspect only repository origins, never APT auth.conf or URL credentials/paths.
# Test the configured HTTP/HTTPS scheme, since an HTTP mirror need not serve HTTPS.
shopt -s nullglob
apt_sources=(/etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources)
[[ ! -r /etc/apt/sources.list ]] || apt_sources+=(/etc/apt/sources.list)
if [[ ${#apt_sources[@]} -gt 0 ]]; then
    printf '\nConfigured APT repository origins:\n'
    while IFS= read -r origin; do
        probe_endpoint "${origin}"
    done < <(awk '
        /^[[:space:]]*#/ {next}
        {for (i=1; i<=NF; i++) if ($i ~ /^https?:\/\//) {
            url=$i; scheme=url; sub(/:.*/, "", scheme)
            sub(/^https?:\/\//, "", url); sub(/\/.*/, "", url)
            sub(/^.*@/, "", url)
            if (url ~ /^[A-Za-z0-9.-]+$/) print scheme "://" url "/"
        }}' "${apt_sources[@]}" | sort -u)
fi

printf '\nOptional GitHub connectivity (not required for the primary installer):\n'
probe_endpoint https://github.com/ true

printf '\nInstalled tools:\n'
for host_tool in java javac git codex; do
    tool_path="$(command -v "${host_tool}" || true)"
    if [[ ${host_tool} == codex && -z ${tool_path} && -x ${HOME}/.local/bin/codex ]]; then
        tool_path="${HOME}/.local/bin/codex"
        printf 'Codex exists in ~/.local/bin but is absent from this shell PATH.\n'
    fi
    if [[ -z ${tool_path} ]]; then
        printf 'MISSING: %s\n' "${host_tool}"
        failures=$((failures + 1))
    elif ! "${tool_path}" --version; then
        failures=$((failures + 1))
    fi
done

printf '\nFailed required checks: %s. No login or authenticated API call was attempted.\n' "${failures}"
[[ ${failures} -eq 0 ]]
