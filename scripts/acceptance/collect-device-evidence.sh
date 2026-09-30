#!/bin/sh
# Read-only evidence collector for Mishka device acceptance.
# It never changes app data. The only temporary ADB side effect is an optional
# local port-forward to reach mihomo's loopback external controller.

set -u

SCRIPT_NAME=${0##*/}
SERIAL=${ANDROID_SERIAL:-}
OUT_DIR=${MISHKA_ACCEPTANCE_OUT:-}
MODE=${MISHKA_MODE:-all}
TIMEOUT_SECONDS=${MISHKA_ACCEPTANCE_TIMEOUT:-20}
API_LOCAL_PORT=${MISHKA_API_LOCAL_PORT:-19090}
API_SECRET=${MISHKA_API_SECRET:-}
API_SECRET_FILE=${MISHKA_API_SECRET_FILE:-}
TAILNET_IPV4=${MISHKA_TAILNET_IPV4:-}
TAILNET_IPV6=${MISHKA_TAILNET_IPV6:-}
SUBNET_TARGET=${MISHKA_SUBNET_TARGET:-}
UDP_TARGET=${MISHKA_UDP_TARGET:-}
FAILURES=0
COLLECTED=0
FORWARD_ACTIVE=0
CURL_CONFIG=

usage() {
    cat <<USAGE
Usage: $SCRIPT_NAME -o OUTPUT_DIR [options]

Read-only Mishka acceptance evidence collector. It captures ADB/device state,
logcat, network routes, optional root netfilter state, and safe projections of
mihomo's local REST API.

Required:
  -o DIR                 Evidence output directory.

Options:
  -s SERIAL              ADB device serial (default: ANDROID_SERIAL or first device).
  -m MODE                vpn | root-tun | root-tproxy | all (default: all).
  -t SECONDS             Per-command timeout (default: 20).
  -p PORT                Host port for adb forward to device 127.0.0.1:9090
                         (default: 19090).
  -h                     Show this help.

Environment inputs (never printed by this script):
  MISHKA_API_SECRET      Optional mihomo Bearer secret for REST evidence.
  MISHKA_API_SECRET_FILE Optional file containing that secret.
  MISHKA_TAILNET_IPV4   Optional 100.64/10 target for a device ping probe.
  MISHKA_TAILNET_IPV6   Optional fd7a:115c:a1e0::/48 target for ping6.
  MISHKA_SUBNET_TARGET   Optional subnet-router target for a ping probe.
  MISHKA_UDP_TARGET      Optional host:port; probe uses toybox nc when present.

Examples:
  $SCRIPT_NAME -o /tmp/mishka-vpn-evidence -m vpn
  MISHKA_API_SECRET='do-not-log' $SCRIPT_NAME -o /tmp/mishka-root-evidence -m root-tproxy
USAGE
}

fail() {
    printf '%s\n' "ERROR: $*" >&2
    FAILURES=$((FAILURES + 1))
}

note() {
    printf '%s\n' "$*"
}

while getopts 'o:s:m:t:p:h' opt; do
    case "$opt" in
        o) OUT_DIR=$OPTARG ;;
        s) SERIAL=$OPTARG ;;
        m) MODE=$OPTARG ;;
        t) TIMEOUT_SECONDS=$OPTARG ;;
        p) API_LOCAL_PORT=$OPTARG ;;
        h) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done
shift $((OPTIND - 1))

if [ "$#" -ne 0 ]; then
    usage >&2
    exit 2
fi

case "$MODE" in
    vpn|root-tun|root-tproxy|all) ;;
    *) fail "invalid mode: $MODE"; usage >&2; exit 2 ;;
esac
case "$TIMEOUT_SECONDS" in
    ''|*[!0-9]*) fail "timeout must be an integer: $TIMEOUT_SECONDS"; exit 2 ;;
esac
case "$API_LOCAL_PORT" in
    ''|*[!0-9]*) fail "API local port must be an integer: $API_LOCAL_PORT"; exit 2 ;;
esac

validate_target() {
    name=$1
    value=$2
    case "$value" in
        *[!A-Za-z0-9:._%/-]*) fail "$name contains unsupported shell characters"; exit 2 ;;
    esac
}
[ -z "$TAILNET_IPV4" ] || validate_target MISHKA_TAILNET_IPV4 "$TAILNET_IPV4"
[ -z "$TAILNET_IPV6" ] || validate_target MISHKA_TAILNET_IPV6 "$TAILNET_IPV6"
[ -z "$SUBNET_TARGET" ] || validate_target MISHKA_SUBNET_TARGET "$SUBNET_TARGET"
[ -z "$UDP_TARGET" ] || validate_target MISHKA_UDP_TARGET "$UDP_TARGET"

if [ -z "$OUT_DIR" ]; then
    fail "output directory is required; use -o DIR or MISHKA_ACCEPTANCE_OUT"
    usage >&2
    exit 2
fi

if ! command -v adb >/dev/null 2>&1; then
    fail "adb is not on PATH"
    exit 127
fi

mkdir -p "$OUT_DIR" 2>/dev/null || {
    fail "cannot create output directory: $OUT_DIR"
    exit 1
}

# Preserve the exact selected serial without relying on non-POSIX shell arrays.
adb_cmd() {
    if [ -n "$SERIAL" ]; then
        adb -s "$SERIAL" "$@"
    else
        adb "$@"
    fi
}

# POSIX-shell timeout wrapper. The collector continues after one optional probe
# fails, while required local commands are counted in the final summary.
run_with_timeout() {
    output=$1
    shift
    "$@" >"$output" 2>&1 &
    command_pid=$!
    (
        sleep "$TIMEOUT_SECONDS"
        if kill -0 "$command_pid" 2>/dev/null; then
            kill -TERM "$command_pid" 2>/dev/null || true
            sleep 1
            kill -KILL "$command_pid" 2>/dev/null || true
        fi
    ) &
    watchdog_pid=$!
    wait "$command_pid"
    command_rc=$?
    kill "$watchdog_pid" 2>/dev/null || true
    wait "$watchdog_pid" 2>/dev/null || true
    if kill -0 "$command_pid" 2>/dev/null; then
        kill -KILL "$command_pid" 2>/dev/null || true
        return 124
    fi
    return "$command_rc"
}

sanitize_file() {
    # Keep accidental auth/password/token/URL fields out if the server adds
    # them to a response in a future version.
    sed -E 's/("(auth-key|secret|password|passwd|token|access-token|refresh-token|private-key|private_key|url)"[[:space:]]*:[[:space:]]*)"[^"]*"/\1"<redacted>"/g; s/("(auth-key|secret|password|passwd|token|access-token|refresh-token|private-key|private_key|url)"[[:space:]]*:[[:space:]]*)null/\1null/g' "$1"
}

record_result() {
    label=$1
    required=$2
    rc=$3
    file=$4
    printf 'command=%s\nrequired=%s\nexit_code=%s\n' "$label" "$required" "$rc" >"$file.meta"
    if [ "$rc" -ne 0 ]; then
        if [ "$required" = yes ]; then
            fail "$label failed (exit $rc); see $file"
        else
            note "optional probe failed: $label (exit $rc)"
        fi
    fi
    COLLECTED=$((COLLECTED + 1))
}

collect_shell() {
    label=$1
    required=$2
    shift 2
    slug=$(printf '%s' "$label" | tr '[:upper:] ' '[:lower:]_')
    output="$OUT_DIR/$slug.txt"
    run_with_timeout "$output" adb_cmd shell "$@"
    rc=$?
    record_result "$label" "$required" "$rc" "$output"
}

collect_root_shell() {
    label=$1
    required=$2
    script=$3
    slug=$(printf '%s' "$label" | tr '[:upper:] ' '[:lower:]_')
    output="$OUT_DIR/$slug.txt"
    run_with_timeout "$output" adb_cmd shell su -c "$script"
    rc=$?
    record_result "$label" "$required" "$rc" "$output"
}

collect_local() {
    label=$1
    required=$2
    shift 2
    slug=$(printf '%s' "$label" | tr '[:upper:] ' '[:lower:]_')
    output="$OUT_DIR/$slug.txt"
    run_with_timeout "$output" "$@"
    rc=$?
    record_result "$label" "$required" "$rc" "$output"
}

api_secret_load() {
    if [ -n "$API_SECRET_FILE" ]; then
        if [ ! -r "$API_SECRET_FILE" ]; then
            fail "MISHKA_API_SECRET_FILE is not readable"
            return 1
        fi
        API_SECRET=$(sed -n '1p' "$API_SECRET_FILE")
    fi
    [ -n "$API_SECRET" ]
}

api_cleanup() {
    if [ -n "$CURL_CONFIG" ] && [ -f "$CURL_CONFIG" ]; then
        rm -f "$CURL_CONFIG"
    fi
    if [ "$FORWARD_ACTIVE" -eq 1 ]; then
        adb_cmd forward --remove "tcp:$API_LOCAL_PORT" >/dev/null 2>&1 || true
    fi
}

trap api_cleanup EXIT
trap 'api_cleanup; exit 130' HUP INT TERM

api_get_safe() {
    path=$1
    label=$2
    required=$3
    slug=$(printf '%s' "$label" | tr '[:upper:] ' '[:lower:]_')
    body="$OUT_DIR/$slug.body"
    output="$OUT_DIR/$slug.txt"
    status_file="$OUT_DIR/$slug.status"

    if ! api_secret_load; then
        printf 'status=SKIPPED\nreason=No API secret supplied; set MISHKA_API_SECRET or MISHKA_API_SECRET_FILE.\n' >"$output"
        COLLECTED=$((COLLECTED + 1))
        note "REST probe skipped: $path (no secret supplied)"
        return 0
    fi

    if [ -z "$CURL_CONFIG" ]; then
        CURL_CONFIG=$(mktemp "${TMPDIR:-/tmp}/mishka-curl.XXXXXX") || {
            fail "cannot create temporary curl config"
            return 1
        }
        chmod 600 "$CURL_CONFIG"
        printf 'silent\nshow-error\nheader = "Authorization: Bearer %s"\n' "$API_SECRET" >"$CURL_CONFIG"
    fi

    if [ "$FORWARD_ACTIVE" -eq 0 ]; then
        if ! adb_cmd forward "tcp:$API_LOCAL_PORT" tcp:9090 >/dev/null 2>&1; then
            printf 'status=SKIPPED\nreason=adb forward tcp:%s -> tcp:9090 failed.\n' "$API_LOCAL_PORT" >"$output"
            if [ "$required" = yes ]; then
                fail "REST port forward failed"
            fi
            COLLECTED=$((COLLECTED + 1))
            return 0
        fi
        FORWARD_ACTIVE=1
    fi

    run_with_timeout "$body" curl --config "$CURL_CONFIG" --connect-timeout "$TIMEOUT_SECONDS" --max-time "$TIMEOUT_SECONDS" -w '%{http_code}' -o "$body.json" "http://127.0.0.1:$API_LOCAL_PORT$path"
    curl_rc=$?
    # -w writes the status code into $body; the response body is in $body.json.
    status=$(cat "$body" 2>/dev/null || printf '000')
    rm -f "$body"
    {
        printf 'path=%s\nhttp_status=%s\ncurl_exit_code=%s\n' "$path" "$status" "$curl_rc"
        if [ -f "$body.json" ]; then
            sanitize_file "$body.json"
        fi
    } >"$output"
    rm -f "$body.json"
    printf '%s\n' "$status" >"$status_file"

    case "$status" in
        2??) rc=0 ;;
        *) rc=1 ;;
    esac
    if [ "$curl_rc" -ne 0 ]; then rc=$curl_rc; fi
    record_result "REST $path" "$required" "$rc" "$output"
}

# Basic host/device metadata.
printf 'collector=%s\nmode=%s\nstarted_at=%s\nserial=%s\n' "$SCRIPT_NAME" "$MODE" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "${SERIAL:-<adb-default>}" >"$OUT_DIR/manifest.txt"
collect_local "adb version" yes adb version
collect_local "adb devices" yes adb devices -l
collect_shell "device properties" yes 'getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.boot.slot_suffix; id; uname -a'
collect_shell "package state" yes 'dumpsys package top.yukonga.mishka | sed -n "1,140p"'
collect_shell "vpn state" no 'dumpsys vpn'
collect_shell "connectivity state" no 'dumpsys connectivity'
collect_shell "network addresses" yes 'ip addr show'
collect_shell "network routes" yes 'ip route show; printf "--- ipv6 ---\\n"; ip -6 route show'
collect_shell "network rules" yes 'ip rule show; printf "--- ipv6 ---\\n"; ip -6 rule show'
collect_shell "processes" no 'ps -A | grep -E "(mishka|mihomo|tailscale)" || true'

# Keep logcat bounded and avoid dumping the entire device log buffer.
collect_shell "mishka logcat" no 'logcat -d -v threadtime -t 2000 -s MishkaTunService:I MishkaRootService:I MihomoRunner:I RootTproxyApplier:I RootTetherHijacker:I WifiPolicyMonitorService:I *:S'

# Optional root-only evidence. A failed su probe is recorded, never treated as
# proof that a ROOT mode passed.
if [ "$MODE" = root-tun ] || [ "$MODE" = root-tproxy ] || [ "$MODE" = all ]; then
    root_required=no
    if [ "$MODE" = root-tun ] || [ "$MODE" = root-tproxy ]; then root_required=yes; fi
    collect_root_shell "root identity" "$root_required" 'id'
    collect_root_shell "root interfaces" no 'ip link show; ip -6 addr show'
    collect_root_shell "root routes and rules" no 'ip rule show; ip -6 rule show; ip route show table 2022; ip route show table 2024; ip -6 route show table 2022; ip -6 route show table 2024'
    collect_root_shell "root iptables mangle" no 'iptables -t mangle -S; printf "--- ip6tables mangle ---\\n"; ip6tables -t mangle -S'
    collect_root_shell "root iptables nat" no 'iptables -t nat -S; printf "--- ip6tables nat ---\\n"; ip6tables -t nat -S'
fi

# Safe API endpoints. The script intentionally does not save the full proxy
# object graph because a future mihomo version could add credentials/URLs.
if api_secret_load; then
    api_get_safe "/version" "api version" yes
    api_get_safe "/configs" "api configs" yes
    api_get_safe "/proxies" "api proxies" yes
    api_get_safe "/connections" "api connections" yes
    api_get_safe "/tailscale/status" "api tailscale status" no
else
    note "REST probes skipped: no MISHKA_API_SECRET or MISHKA_API_SECRET_FILE"
fi

# Optional reachability probes are sanity checks only. They do not prove that
# Mishka handled the traffic; pair them with /connections evidence and a manual
# app-level check from the acceptance document.
if [ -n "$TAILNET_IPV4" ]; then
    collect_shell "probe tailnet ipv4" no "ping -c 3 -W 2 '$TAILNET_IPV4'"
fi
if [ -n "$TAILNET_IPV6" ]; then
    collect_shell "probe tailnet ipv6" no "ping6 -c 3 -W 2 '$TAILNET_IPV6'"
fi
if [ -n "$SUBNET_TARGET" ]; then
    collect_shell "probe subnet route" no "ping -c 3 -W 2 '$SUBNET_TARGET'"
fi
if [ -n "$UDP_TARGET" ]; then
    udp_host=${UDP_TARGET%:*}
    udp_port=${UDP_TARGET##*:}
    collect_shell "probe udp" no "command -v nc >/dev/null 2>&1 && nc -u -w 3 -z '$udp_host' '$udp_port' || (command -v toybox >/dev/null 2>&1 && toybox nc -u -w 3 -z '$udp_host' '$udp_port')"
fi

printf 'collected=%s\nfailures=%s\nfinished_at=%s\n' "$COLLECTED" "$FAILURES" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" >>"$OUT_DIR/manifest.txt"
note "Evidence written to $OUT_DIR"
note "Collected probes: $COLLECTED; required failures: $FAILURES"
if [ "$FAILURES" -ne 0 ]; then
    exit 1
fi
exit 0
