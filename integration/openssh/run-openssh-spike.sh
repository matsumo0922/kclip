#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  run-openssh-spike.sh --destination HOST [--ssh-option OPTION ...]
  run-openssh-spike.sh --self-test

Options:
  --destination HOST      Existing SSH destination to test.
  --ssh-option OPTION     Extra ssh option passed to normal SSH connections.
  --self-test             Start temporary local sshd instances and test against them.
  --work-dir DIR          Use DIR for temporary local state.
  --keep-work-dir         Keep local temporary state after the run.
  -h, --help              Show this help.
EOF
}

log() {
  printf '[openssh-spike] %s\n' "$*"
}

fail() {
  printf '[openssh-spike] ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

short_tmp_dir() {
  if [ -d /tmp ]; then
    mktemp -d /tmp/kclip-openssh.XXXXXX
    return
  fi

  mktemp -d "${TMPDIR:-.}/kclip-openssh.XXXXXX"
}

free_port() {
  python3 - <<'PY'
import socket

server = socket.socket()
server.bind(("127.0.0.1", 0))
print(server.getsockname()[1])
server.close()
PY
}

SSH_BIN=${SSH_BIN:-ssh}
SSHD_BIN=${SSHD_BIN:-}
DESTINATION=
SELF_TEST=0
KEEP_WORK_DIR=0
WORK_DIR=
SSH_OPTIONS=()
DENIED_DESTINATION=
DENIED_SSH_OPTIONS=()
PROXY_DESTINATION=
PROXY_SSH_OPTIONS=()
SSHD_PIDS=()
LOCAL_ECHO_PID=
MASTER_CONTROL=
CLEAR_CONTROL=
REMOTE_BASE=
CONTROL_DESTINATION=localhost

while [ "$#" -gt 0 ]; do
  case "$1" in
    --destination)
      DESTINATION=$2
      shift 2
      ;;
    --ssh-option)
      SSH_OPTIONS+=("$2")
      shift 2
      ;;
    --self-test)
      SELF_TEST=1
      shift
      ;;
    --work-dir)
      WORK_DIR=$2
      shift 2
      ;;
    --keep-work-dir)
      KEEP_WORK_DIR=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

require_command "$SSH_BIN"
require_command python3
require_command ssh-keygen

if [ -z "$WORK_DIR" ]; then
  WORK_DIR=$(short_tmp_dir)
fi

mkdir -p "$WORK_DIR"
WORK_DIR=$(cd "$WORK_DIR" && pwd)

cleanup() {
  set +e

  if [ -n "$MASTER_CONTROL" ] && [ -S "$MASTER_CONTROL" ]; then
    "$SSH_BIN" -F /dev/null -S "$MASTER_CONTROL" -O exit "$CONTROL_DESTINATION" >/dev/null 2>&1
  fi

  if [ -n "$CLEAR_CONTROL" ] && [ -S "$CLEAR_CONTROL" ]; then
    "$SSH_BIN" -F /dev/null -S "$CLEAR_CONTROL" -O exit "$CONTROL_DESTINATION" >/dev/null 2>&1
  fi

  if [ -n "$REMOTE_BASE" ] && [ -n "$DESTINATION" ]; then
    remote_sh "chmod -R u+rwx $REMOTE_BASE 2>/dev/null || true; rm -rf $REMOTE_BASE" >/dev/null 2>&1
  fi

  if [ -n "$LOCAL_ECHO_PID" ]; then
    kill "$LOCAL_ECHO_PID" >/dev/null 2>&1
  fi

  if [ "${#SSHD_PIDS[@]}" -gt 0 ]; then
    for sshd_pid in "${SSHD_PIDS[@]}"; do
      kill "$sshd_pid" >/dev/null 2>&1
    done
  fi

  if [ "$KEEP_WORK_DIR" -eq 0 ]; then
    rm -rf "$WORK_DIR"
  else
    log "kept work dir: $WORK_DIR"
  fi
}

trap cleanup EXIT

find_sshd() {
  if [ -n "$SSHD_BIN" ]; then
    printf '%s\n' "$SSHD_BIN"
    return
  fi

  if command -v sshd >/dev/null 2>&1; then
    command -v sshd
    return
  fi

  if [ -x /usr/sbin/sshd ]; then
    printf '%s\n' /usr/sbin/sshd
    return
  fi

  fail "sshd not found; install openssh-server or pass SSHD_BIN"
}

wait_for_port() {
  local port=$1

  python3 - "$port" <<'PY'
import socket
import sys
import time

port = int(sys.argv[1])
deadline = time.time() + 10
last_error = None

while time.time() < deadline:
    sock = socket.socket()
    sock.settimeout(0.2)

    try:
        sock.connect(("127.0.0.1", port))
        sock.close()
        sys.exit(0)
    except OSError as error:
        last_error = error
        time.sleep(0.1)
    finally:
        sock.close()

print(f"port {port} did not open: {last_error}", file=sys.stderr)
sys.exit(1)
PY
}

start_sshd() {
  local name=$1
  local port=$2
  local allow_stream_local=$3
  local config_file="$WORK_DIR/sshd-$name.conf"
  local pid_file="$WORK_DIR/sshd-$name.pid"
  local log_file="$WORK_DIR/sshd-$name.log"

  cat > "$config_file" <<EOF
Port $port
ListenAddress 127.0.0.1
HostKey $WORK_DIR/host_ed25519
PidFile $pid_file
AuthorizedKeysFile $WORK_DIR/authorized_keys
PasswordAuthentication no
KbdInteractiveAuthentication no
ChallengeResponseAuthentication no
PubkeyAuthentication yes
StrictModes no
UsePAM no
PermitTTY no
PrintMotd no
AllowTcpForwarding yes
AllowStreamLocalForwarding $allow_stream_local
PermitOpen any
LogLevel VERBOSE
EOF

  "$SSHD_BIN" -t -f "$config_file" || fail "invalid sshd config: $config_file"
  "$SSHD_BIN" -f "$config_file" -E "$log_file"
  wait_for_port "$port"

  if [ -f "$pid_file" ]; then
    SSHD_PIDS+=("$(cat "$pid_file")")
  fi
}

configure_self_test() {
  SSHD_BIN=$(find_sshd)

  ssh-keygen -q -t ed25519 -N "" -f "$WORK_DIR/client_ed25519"
  ssh-keygen -q -t ed25519 -N "" -f "$WORK_DIR/host_ed25519"
  cp "$WORK_DIR/client_ed25519.pub" "$WORK_DIR/authorized_keys"
  chmod 600 "$WORK_DIR/client_ed25519" "$WORK_DIR/authorized_keys"

  local target_port
  local denied_port
  local jump_port
  local current_user
  local proxy_config

  target_port=$(free_port)
  denied_port=$(free_port)
  jump_port=$(free_port)
  current_user=$(id -un)
  proxy_config="$WORK_DIR/proxy-ssh-config"

  start_sshd target "$target_port" yes
  start_sshd denied "$denied_port" no
  start_sshd jump "$jump_port" yes

  DESTINATION=127.0.0.1
  CONTROL_DESTINATION=127.0.0.1
  SSH_OPTIONS=(
    -F /dev/null
    -p "$target_port"
    -i "$WORK_DIR/client_ed25519"
    -o IdentitiesOnly=yes
    -o StrictHostKeyChecking=no
    -o UserKnownHostsFile="$WORK_DIR/known_hosts"
    -o GlobalKnownHostsFile=/dev/null
    -o PreferredAuthentications=publickey
    -o BatchMode=yes
    -l "$current_user"
  )
  DENIED_DESTINATION=127.0.0.1
  DENIED_SSH_OPTIONS=(
    -F /dev/null
    -p "$denied_port"
    -i "$WORK_DIR/client_ed25519"
    -o IdentitiesOnly=yes
    -o StrictHostKeyChecking=no
    -o UserKnownHostsFile="$WORK_DIR/known_hosts"
    -o GlobalKnownHostsFile=/dev/null
    -o PreferredAuthentications=publickey
    -o BatchMode=yes
    -l "$current_user"
  )

  cat > "$proxy_config" <<EOF
Host kclip-jump
  HostName 127.0.0.1
  Port $jump_port
  User $current_user
  IdentityFile $WORK_DIR/client_ed25519
  IdentitiesOnly yes
  StrictHostKeyChecking no
  UserKnownHostsFile $WORK_DIR/known_hosts
  GlobalKnownHostsFile /dev/null
  PreferredAuthentications publickey
  BatchMode yes

Host kclip-target-through-jump
  HostName 127.0.0.1
  Port $target_port
  User $current_user
  IdentityFile $WORK_DIR/client_ed25519
  IdentitiesOnly yes
  StrictHostKeyChecking no
  UserKnownHostsFile $WORK_DIR/known_hosts
  GlobalKnownHostsFile /dev/null
  PreferredAuthentications publickey
  BatchMode yes
  ProxyJump kclip-jump
EOF

  PROXY_DESTINATION=kclip-target-through-jump
  PROXY_SSH_OPTIONS=(
    -F "$proxy_config"
  )

  log "self-test target port=$target_port denied port=$denied_port jump port=$jump_port"
}

remote_sh() {
  printf '%s\n' "$1" | "$SSH_BIN" "${SSH_OPTIONS[@]}" "$DESTINATION" sh -s
}

remote_python_connect() {
  local socket_path=$1
  local payload=$2

  "$SSH_BIN" "${SSH_OPTIONS[@]}" "$DESTINATION" python3 - "$socket_path" "$payload" <<'PY'
import socket
import sys

path = sys.argv[1]
payload = sys.argv[2].encode()

client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
client.settimeout(5)
client.connect(path)
client.sendall(payload)
response = client.recv(65536)
client.close()

sys.stdout.write(response.decode())
PY
}

remote_python_assert_socket() {
  local socket_path=$1

  "$SSH_BIN" "${SSH_OPTIONS[@]}" "$DESTINATION" python3 - "$socket_path" <<'PY'
import os
import stat
import sys

mode = os.lstat(sys.argv[1]).st_mode

if not stat.S_ISSOCK(mode):
    print(oct(stat.S_IMODE(mode)), file=sys.stderr)
    sys.exit(1)
PY
}

remote_python_socket_mode() {
  local socket_path=$1

  "$SSH_BIN" "${SSH_OPTIONS[@]}" "$DESTINATION" python3 - "$socket_path" <<'PY'
import os
import stat
import sys

mode = os.lstat(sys.argv[1]).st_mode
print(oct(stat.S_IMODE(mode)))
PY
}

remote_python_create_stale_socket() {
  local socket_path=$1

  "$SSH_BIN" "${SSH_OPTIONS[@]}" "$DESTINATION" python3 - "$socket_path" <<'PY'
import os
import socket
import sys

path = sys.argv[1]

try:
    os.unlink(path)
except FileNotFoundError:
    pass

server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
server.bind(path)
server.close()
PY
}

remote_python_path_exists() {
  local path=$1

  "$SSH_BIN" "${SSH_OPTIONS[@]}" "$DESTINATION" python3 - "$path" <<'PY'
import os
import sys

sys.exit(0 if os.path.exists(sys.argv[1]) else 1)
PY
}

wait_for_local_socket() {
  local socket_path=$1

  python3 - "$socket_path" <<'PY'
import os
import stat
import sys
import time

path = sys.argv[1]
deadline = time.time() + 5

while time.time() < deadline:
    try:
        mode = os.lstat(path).st_mode
        if stat.S_ISSOCK(mode):
            sys.exit(0)
    except FileNotFoundError:
        pass

    time.sleep(0.05)

print(f"socket did not appear: {path}", file=sys.stderr)
sys.exit(1)
PY
}

wait_for_remote_socket() {
  local socket_path=$1
  local attempts=0

  while [ "$attempts" -lt 50 ]; do
    if remote_python_assert_socket "$socket_path" >/dev/null 2>&1; then
      return 0
    fi

    attempts=$((attempts + 1))
    sleep 0.1
  done

  fail "remote socket did not appear: $socket_path"
}

start_local_echo_server() {
  local socket_path=$1

  python3 - "$socket_path" <<'PY' &
import os
import signal
import socket
import sys

path = sys.argv[1]

try:
    os.unlink(path)
except FileNotFoundError:
    pass

server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
server.bind(path)
os.chmod(path, 0o600)
server.listen(16)
signal.signal(signal.SIGTERM, lambda signum, frame: sys.exit(0))

while True:
    connection, _ = server.accept()
    payload = connection.recv(65536)
    connection.sendall(b"kclip-echo:" + payload)
    connection.close()
PY
  LOCAL_ECHO_PID=$!
  wait_for_local_socket "$socket_path"
}

control_ssh() {
  "$SSH_BIN" -F /dev/null -S "$MASTER_CONTROL" "$@"
}

start_dedicated_master() {
  "$SSH_BIN" \
    -fNT \
    -M \
    -S "$MASTER_CONTROL" \
    -o ControlPersist=no \
    -o ClearAllForwardings=yes \
    -o PermitLocalCommand=no \
    -o StreamLocalBindMask=0177 \
    -o StreamLocalBindUnlink=yes \
    -o ForwardAgent=no \
    -o ForwardX11=no \
    -o RequestTTY=no \
    -o Tunnel=no \
    "${SSH_OPTIONS[@]}" \
    "$DESTINATION"

  control_ssh -O check "$CONTROL_DESTINATION" >/dev/null
  log "dedicated private master is alive"
}

add_forward() {
  local remote_socket=$1
  local local_socket=$2

  control_ssh \
    -o ClearAllForwardings=no \
    -o PermitLocalCommand=no \
    -o StreamLocalBindMask=0177 \
    -o StreamLocalBindUnlink=yes \
    -O forward \
    -R "$remote_socket:$local_socket" \
    "$CONTROL_DESTINATION"
}

cancel_forward() {
  local remote_socket=$1
  local local_socket=$2

  control_ssh \
    -o ClearAllForwardings=no \
    -o PermitLocalCommand=no \
    -O cancel \
    -R "$remote_socket:$local_socket" \
    "$CONTROL_DESTINATION"
}

sanitize_evidence() {
  local file=$1

  tr '\n' ' ' < "$file" \
    | sed "s#$WORK_DIR#<work-dir>#g; s#$REMOTE_BASE#<remote-base>#g; s#[[:space:]][[:space:]]*# #g" \
    | cut -c 1-240
}

expect_forward_failure() {
  local label=$1
  local remote_socket=$2
  local local_socket=$3
  local stderr_file="$WORK_DIR/$label.stderr"

  if add_forward "$remote_socket" "$local_socket" 2>"$stderr_file"; then
    cancel_forward "$remote_socket" "$local_socket" >/dev/null 2>&1 || true
    fail "$label unexpectedly succeeded"
  fi

  log "$label failed as expected: $(sanitize_evidence "$stderr_file")"
}

test_round_trip_and_cancel() {
  local remote_socket="$REMOTE_BASE/roundtrip.sock"
  local local_socket=$1
  local response
  local socket_mode

  add_forward "$remote_socket" "$local_socket" >/dev/null
  wait_for_remote_socket "$remote_socket"

  response=$(remote_python_connect "$remote_socket" "roundtrip")
  [ "$response" = "kclip-echo:roundtrip" ] || fail "unexpected round-trip response: $response"

  socket_mode=$(remote_python_socket_mode "$remote_socket")
  case "$socket_mode" in
    0o600|0o700)
      log "StreamLocalBindMask produced owner-only socket mode: $socket_mode"
      ;;
    *)
      fail "remote socket mode is not owner-only: $socket_mode"
      ;;
  esac

  cancel_forward "$remote_socket" "$local_socket" >/dev/null

  if remote_python_connect "$remote_socket" "after-cancel" >/dev/null 2>&1; then
    fail "remote socket accepted a connection after exact cancel"
  fi

  control_ssh -O check "$CONTROL_DESTINATION" >/dev/null
  log "round-trip and exact cancel succeeded without stopping the master"
}

test_clear_all_forwardings_constraint() {
  local local_socket=$1
  local remote_socket="$REMOTE_BASE/clear-all.sock"

  CLEAR_CONTROL="$WORK_DIR/clear-master.sock"
  "$SSH_BIN" \
    -fNT \
    -M \
    -S "$CLEAR_CONTROL" \
    -o ControlPersist=no \
    -o ClearAllForwardings=yes \
    -o StreamLocalBindUnlink=yes \
    -R "$remote_socket:$local_socket" \
    "${SSH_OPTIONS[@]}" \
    "$DESTINATION"

  sleep 0.5

  if remote_python_path_exists "$remote_socket"; then
    fail "ClearAllForwardings=yes did not clear command-line -R"
  fi

  "$SSH_BIN" -F /dev/null -S "$CLEAR_CONTROL" -O exit "$CONTROL_DESTINATION" >/dev/null 2>&1 || true
  CLEAR_CONTROL=
  log "ClearAllForwardings=yes cleared command-line -R as expected"
}

test_stale_socket_unlink() {
  local local_socket=$1
  local remote_socket="$REMOTE_BASE/stale.sock"
  local stderr_file="$WORK_DIR/stale-socket.stderr"
  local response

  remote_python_create_stale_socket "$remote_socket"
  remote_python_assert_socket "$remote_socket"

  if ! add_forward "$remote_socket" "$local_socket" > /dev/null 2>"$stderr_file"; then
    log "stale socket collision observed: $(sanitize_evidence "$stderr_file")"
    log "StreamLocalBindUnlink did not replace a stale socket through mux -O forward"
    return
  fi

  wait_for_remote_socket "$remote_socket"

  response=$(remote_python_connect "$remote_socket" "stale")
  [ "$response" = "kclip-echo:stale" ] || fail "unexpected stale-socket response: $response"

  cancel_forward "$remote_socket" "$local_socket" >/dev/null
  log "StreamLocalBindUnlink replaced a stale remote socket through mux -O forward"
}

test_failure_cases() {
  local local_socket=$1
  local protected_dir="$REMOTE_BASE/no-write"
  local long_name

  remote_sh "mkdir -p $protected_dir && chmod 500 $protected_dir"
  expect_forward_failure "permission-denied" "$protected_dir/denied.sock" "$local_socket"
  remote_sh "chmod 700 $protected_dir"

  long_name=$(python3 - <<'PY'
print("x" * 180 + ".sock")
PY
)
  expect_forward_failure "path-too-long" "$REMOTE_BASE/$long_name" "$local_socket"
}

test_forwarding_rejected() {
  local local_socket=$1
  local denied_control="$WORK_DIR/denied-master.sock"
  local denied_remote="$REMOTE_BASE/denied-forward.sock"
  local stderr_file="$WORK_DIR/forwarding-rejected.stderr"

  if [ -z "$DENIED_DESTINATION" ]; then
    log "skipping forwarding-rejected case; no denied sshd configured"
    return
  fi

  if "$SSH_BIN" \
      -fNT \
      -M \
      -S "$denied_control" \
      -o ControlPersist=no \
      -o ExitOnForwardFailure=yes \
      -o StreamLocalBindUnlink=yes \
      -R "$denied_remote:$local_socket" \
      "${DENIED_SSH_OPTIONS[@]}" \
      "$DENIED_DESTINATION" 2>"$stderr_file"; then
    "$SSH_BIN" -F /dev/null -S "$denied_control" -O exit "$CONTROL_DESTINATION" >/dev/null 2>&1 || true
    fail "forwarding rejection case unexpectedly succeeded"
  fi

  log "forwarding rejection classified: $(sanitize_evidence "$stderr_file")"
}

test_proxyjump_smoke() {
  if [ -z "$PROXY_DESTINATION" ]; then
    log "skipping ProxyJump smoke; no jump sshd configured"
    return
  fi

  "$SSH_BIN" "${PROXY_SSH_OPTIONS[@]}" "$PROXY_DESTINATION" true
  log "ProxyJump with custom port and identity succeeded"
}

if [ "$SELF_TEST" -eq 1 ]; then
  configure_self_test
elif [ -z "$DESTINATION" ]; then
  usage
  fail "either --destination or --self-test is required"
else
  CONTROL_DESTINATION=${KCLIP_OPENSSH_CONTROL_HOST:-$DESTINATION}
fi

REMOTE_BASE="/tmp/kclip-openssh-spike-$(date +%s)-$$"
LOCAL_SOCKET="$WORK_DIR/local-agent.sock"
MASTER_CONTROL="$WORK_DIR/dedicated-master.sock"

log "work dir: $WORK_DIR"
log "destination: $DESTINATION"

"$SSH_BIN" "${SSH_OPTIONS[@]}" "$DESTINATION" python3 - >/dev/null <<'PY'
import socket
PY
remote_sh "rm -rf $REMOTE_BASE && mkdir -p $REMOTE_BASE && chmod 700 $REMOTE_BASE"
start_local_echo_server "$LOCAL_SOCKET"
start_dedicated_master

test_round_trip_and_cancel "$LOCAL_SOCKET"
test_clear_all_forwardings_constraint "$LOCAL_SOCKET"
test_stale_socket_unlink "$LOCAL_SOCKET"
test_failure_cases "$LOCAL_SOCKET"
test_forwarding_rejected "$LOCAL_SOCKET"
test_proxyjump_smoke

log "OpenSSH transport spike completed successfully"
