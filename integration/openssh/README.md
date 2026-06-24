# OpenSSH transport spike

Phase 0.5 validates OpenSSH command shapes before they are wrapped in Kotlin
code. The spike is intentionally shell-based and does not become production
transport code.

## Run

Run the hermetic Linux/Unix self-test with temporary local `sshd` instances:

```sh
integration/openssh/run-openssh-spike.sh --self-test
```

Run against an existing SSH destination:

```sh
integration/openssh/run-openssh-spike.sh \
  --destination <ssh-destination> \
  --ssh-option -o \
  --ssh-option BatchMode=yes
```

The script needs `ssh`, `ssh-keygen`, `python3`, and, for `--self-test`,
`sshd`.

## Coverage

- remote Unix-domain socket forwarding with `-R remote.sock:local.sock`
- dedicated private master with `ssh -fNT -M -S ...`
- private master control operations with `ssh -F /dev/null -S ... -O forward`
- exact `ssh -O cancel -R ...` without stopping the master
- `ClearAllForwardings=yes` clearing command-line `-R`
- `StreamLocalBindMask=0177` owner-only socket mode measurement
- stale socket, permission-denied, and path-length failure classification
- forwarding rejection with a temporary denied `sshd`
- ProxyJump with custom port and identity in self-test mode

## Evidence

Recorded on 2026-06-24 from macOS local to a private macOS SSH destination:

- dedicated private master started and survived `-O cancel`
- remote stream-local forwarding round trip succeeded
- remote socket mode was `0o600`
- `ClearAllForwardings=yes` cleared command-line `-R`
- stale remote socket collision failed with sanitized `remote port forwarding failed for listen path ...`
- `StreamLocalBindUnlink=yes` did not replace a stale socket through mux `-O forward`
- permission-denied and path-too-long cases produced classifiable OpenSSH stderr

Recorded on 2026-06-24 with `--self-test`:

- temporary target, denied, and jump `sshd` instances started successfully
- forwarding rejection was classified against `AllowStreamLocalForwarding no`
- ProxyJump with custom port and identity succeeded

## Design impact

The successful round trip confirms the Phase 3 dedicated sidecar direction.

The stale-socket result means `kclip` must not rely on `StreamLocalBindUnlink`
to repair stale sockets when adding forwards through mux `-O forward`. Stale
socket collision remains an explicit transport failure; production attach and
reconnect should surface a clear diagnostic and move to a new pairing path or a
controlled user-guided repair path instead of unlinking arbitrary remote paths.
