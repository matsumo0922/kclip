# kclip

[![CI](https://github.com/matsumo0922/kclip/actions/workflows/ci.yml/badge.svg)](https://github.com/matsumo0922/kclip/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

`kclip` は、ローカル環境と SSH 接続先の間でテキストクリップボードを扱うための Kotlin/Native 製 CLI です。

普通の `ssh` で入ったあとに、remote shell をあとからローカルの clipboard agent へ attach できます。remote から local へ直接接続できる必要はありません。local から OpenSSH の Unix-domain socket forwarding を張り、remote の `kclip copy` / `kclip paste` を local clipboard へ安全に中継します。

```text
remote shell              SSH reverse forwarding          local machine
-----------               ----------------------          -------------
kclip copy   ----->  /tmp/kclip-*.sock  ========>  attachment agent  -> pbcopy / wl-copy / xclip
kclip paste  <-----  /tmp/kclip-*.sock  <========  attachment agent  <- pbpaste / wl-paste / xclip
```

## Status

`kclip` は現在 `0.1.0-dev` の開発版です。macOS / Linux 向けの Kotlin/Native CLI として、以下の機能を実装しています。

- local clipboard の `copy` / `paste`
- 既存 SSH session に対する `pair` / `attach`
- OpenSSH ControlMaster を使った attach
- ControlMaster がない場合の dedicated SSH sidecar attach
- local attachment の `attachments` / `detach` / `reconnect`
- local clipboard availability を確認する `doctor`
- OpenSSH transport の integration spike / self-test

まだ package manager や GitHub Releases による配布はありません。現時点では source から build して使います。

## Why kclip?

SSH 先でクリップボードを扱う方法はいくつかありますが、どれも少しずつ痛みがあります。

- `pbcopy` / `pbpaste` は macOS local では便利だが、remote にはそのまま届かない
- OSC 52 は terminal emulator 依存で、paste には向かない
- remote から local へ接続させる方式は、local SSH server や firewall 設定が必要になりがち
- `ssh` wrapper だけにすると、すでに開いている SSH session を救えない

`kclip` は、すでに開いている SSH session に後付けで clipboard access を足すことを中心にしています。

## Features

- **Text-first clipboard**: UTF-8 の `text/plain` を stdin/stdout で扱います。
- **Existing SSH attach**: remote で `kclip pair`、local で `kclip attach` を実行するだけです。
- **No inbound local connection**: remote から local へ直接接続しません。forwarding は local から作ります。
- **Paste is opt-in**: remote から local clipboard を読む `paste` は、local 側の `--paste=allow` が必要です。
- **No global daemon**: system-wide daemon は起動しません。attachment ごとの local agent だけが動きます。
- **OpenSSH-native transport**: SSH 認証、host key 検証、暗号化は OpenSSH に任せます。
- **Kotlin Multiplatform structure**: domain / protocol / application / platform / agent / cli を分け、Unix native target に載せています。

## Supported Platforms

| Platform | Target | Clipboard backend |
|---|---|---|
| macOS Apple Silicon | `macosArm64` | `/usr/bin/pbcopy`, `/usr/bin/pbpaste` |
| Linux x64 | `linuxX64` | Wayland: `wl-copy` / `wl-paste`, X11: `xclip`, fallback: `xsel` |
| Linux arm64 | `linuxArm64` | Wayland: `wl-copy` / `wl-paste`, X11: `xclip`, fallback: `xsel` |

Requirements:

- JDK 21 for building
- OpenSSH client for SSH attach
- OpenSSH server on the remote host when using SSH attach
- Unix-domain stream local forwarding enabled on the SSH server
- One of the clipboard backends listed above

## Installation

Clone the repository and build the native executable.

```sh
git clone git@github.com:matsumo0922/kclip.git
cd kclip
```

macOS Apple Silicon:

```sh
./gradlew :cli:linkDebugExecutableMacosArm64
mkdir -p ~/.local/bin
cp cli/build/bin/macosArm64/debugExecutable/kclip.kexe ~/.local/bin/kclip
chmod +x ~/.local/bin/kclip
```

Linux x64:

```sh
./gradlew :cli:linkDebugExecutableLinuxX64
mkdir -p ~/.local/bin
cp cli/build/bin/linuxX64/debugExecutable/kclip.kexe ~/.local/bin/kclip
chmod +x ~/.local/bin/kclip
```

Linux arm64:

```sh
./gradlew :cli:linkDebugExecutableLinuxArm64
mkdir -p ~/.local/bin
cp cli/build/bin/linuxArm64/debugExecutable/kclip.kexe ~/.local/bin/kclip
chmod +x ~/.local/bin/kclip
```

Make sure `~/.local/bin` is in your `PATH`.

```sh
kclip version
kclip doctor
```

You need `kclip` on both the local machine and the remote host.

```sh
ssh dev@example.com 'mkdir -p ~/bin'
scp ~/.local/bin/kclip dev@example.com:~/bin/kclip
ssh dev@example.com 'chmod +x ~/bin/kclip && ~/bin/kclip version'
```

## Quick Start

### Local clipboard

Copy stdin into the local clipboard:

```sh
printf 'hello from kclip' | kclip copy
```

Paste the local clipboard to stdout:

```sh
kclip paste
```

Check whether a local clipboard backend is available:

```sh
kclip doctor
```

Example output on macOS:

```text
kclip
  status: local
  clipboard: available (macos-pbcopy)
```

### Attach an existing SSH session

Open or reuse a normal SSH session.

```sh
local$ ssh dev@example.com
remote$ kclip pair --paste
```

`kclip pair` prints a one-time pairing code and waits for local attachment.

```text
kclip pairing
code: KC1-6X4P-9Q2K-H7MT-W3DN

On your local machine:
  kclip attach --pairing-code-stdin --paste=allow <ssh-destination>

Waiting for local attachment...
```

In another local terminal, attach to the same SSH destination.

```sh
local$ printf 'KC1-6X4P-9Q2K-H7MT-W3DN\n' |
  kclip attach --pairing-code-stdin --paste=allow dev@example.com
```

Go back to the remote shell and use `kclip` naturally.

```sh
remote$ printf 'from remote' | kclip copy
remote$ kclip paste
```

If the remote only needs to write to your local clipboard, omit `--paste` on the remote side and keep the local side at `--paste=deny`.

```sh
remote$ kclip pair
local$ printf '<pairing-code>\n' |
  kclip attach --pairing-code-stdin --paste=deny dev@example.com
```

## Command Reference

### `kclip copy`

Reads UTF-8 text from stdin and copies it to a clipboard backend.

```sh
kclip copy [--backend=auto|attachment|system|osc52] [--attachment=<id>] [--max-bytes=<bytes>]
```

Examples:

```sh
printf 'hello' | kclip copy
printf 'hello' | kclip copy --backend=system
printf 'hello' | kclip copy --backend=attachment
```

`--attachment=<id>` is an advanced option for scripts that know the full attachment ID. Interactive SSH use normally relies on the current TTY binding and does not need this option.

### `kclip paste`

Reads text from a clipboard backend and writes it to stdout.

```sh
kclip paste [--backend=auto|attachment|system] [--attachment=<id>] [--max-bytes=<bytes>]
```

Examples:

```sh
kclip paste
kclip paste > note.txt
value="$(kclip paste)"
```

When running inside SSH, `paste --backend=auto` does not silently fall back to the remote GUI clipboard if no attachment is bound. It asks you to pair and attach instead.

### `kclip pair`

Starts a one-time pairing request from the remote shell.

```sh
kclip pair [--paste] [--replace]
```

- `--paste` requests permission to read the local clipboard.
- `--replace` replaces an existing binding for the same remote TTY.

The pairing request is scoped to the current controlling TTY. This means different tmux panes, screen windows, or terminal sessions can have separate attachments.

### `kclip attach`

Runs on the local machine and connects a waiting remote pairing request to a local attachment agent.

```sh
kclip attach \
  [--transport=auto|controlmaster|dedicated] \
  [--control-path=<path>] \
  [--paste=deny|allow] \
  [--pairing-code-stdin] \
  [--agent-executable=<path>] \
  [--ssh=<path>] \
  <destination>
```

Transport modes:

| Mode | Behavior |
|---|---|
| `auto` | Use an active OpenSSH ControlMaster if available. Otherwise start a private dedicated master. |
| `controlmaster` | Require an existing ControlMaster and add forwarding through it. |
| `dedicated` | Always start a kclip-owned private SSH master for the attachment. |

`--control-path` is useful when the ControlMaster path is not discoverable from `ssh -G`, or when you want to test a specific master socket.

### `kclip attachments`

Lists local attachments known to this machine.

```sh
kclip attachments
```

Example:

```text
KC-A7D2  dedicated  paste=allow  active  dev@example.com
```

### `kclip detach`

Stops the local agent and removes forwarding for one attachment.

```sh
kclip detach KC-A7D2
```

For ControlMaster attachments, `detach` cancels only the kclip forwarding. It does not stop your existing SSH master connection.

### `kclip reconnect`

Recreates SSH forwarding for an existing local attachment.

```sh
kclip reconnect KC-A7D2
```

This is useful after a dedicated master was interrupted or a forwarding path became degraded.

### `kclip doctor`

Prints a compact local diagnostic report.

```sh
kclip doctor
```

Current diagnostics focus on local clipboard backend availability.

## Security Model

`kclip` is designed to avoid surprising clipboard exposure.

- Remote `copy` is allowed by default because it only writes to your local clipboard.
- Remote `paste` is denied by default because it reads your local clipboard.
- `paste` permission is granted per attachment with `kclip attach --paste=allow`.
- Pairing codes are one-time values generated from 80 bits of entropy.
- The local agent uses an attachment-specific random nonce after pairing.
- SSH authentication, encryption, and host key verification are delegated to OpenSSH.
- The local agent is per attachment, not a system-wide daemon.
- Dedicated transport uses a private OpenSSH master controlled by kclip.
- ControlMaster transport validates the control socket before use.
- Stale remote socket collisions fail closed. `kclip` does not unlink arbitrary remote paths automatically.

Important limitations:

- A process running as the same remote UID may be able to use the same TTY-bound attachment state.
- Remote root is not isolated from the remote user's runtime files.
- Clipboard payloads are text-only UTF-8.
- The default payload limit is 1 MiB.
- Image, HTML, RTF, and multi-MIME clipboard contents are out of scope for v1.

## Architecture

The project is split into small KMP modules so protocol, domain, and platform responsibilities stay separated.

| Module | Responsibility |
|---|---|
| `:core:domain` | Core value objects, errors, clipboard abstractions, attachment state codecs |
| `:core:protocol` | Versioned attachment agent wire protocol |
| `:core:application` | Use cases for copy, paste, pair, and attach orchestration |
| `:core:platform` | Unix IPC, filesystem, process, command, and clipboard integrations |
| `:core:agent` | Local per-attachment agent and remote attachment client |
| `:core:diagnostics` | Diagnostic report model |
| `:cli` | Clikt-based command line entry point |
| `integration/openssh` | OpenSSH forwarding spike and self-test scripts |

The primary SSH attach path looks like this:

1. Remote user runs `kclip pair`.
2. Remote `pair` creates a one-time code and waits on a remote Unix socket derived from that code.
3. Local user runs `kclip attach` with the code.
4. Local `attach` starts an attachment agent on a local Unix socket.
5. Local `attach` adds an OpenSSH reverse Unix-domain socket forward from remote to local.
6. Remote `pair` connects through the forwarded socket and completes the protocol handshake.
7. Remote `copy` / `paste` uses the TTY-bound attachment lease until detached.

See [docs/design-v1.md](docs/design-v1.md) for the detailed design.

## Development

Run the main test set:

```sh
./gradlew :core:domain:allTests \
  :core:application:allTests \
  :core:platform:allTests \
  :core:protocol:allTests \
  :core:agent:allTests \
  :cli:allTests
```

Run static analysis:

```sh
./gradlew detekt
```

Build the local macOS executable:

```sh
./gradlew :cli:linkDebugExecutableMacosArm64
```

Run the OpenSSH integration spike:

```sh
integration/openssh/run-openssh-spike.sh --self-test
```

CI currently validates:

- macOS tests, macOS executable link, and detekt
- Linux x64 tests and compile
- OpenSSH transport spike self-test

## Roadmap

Near-term work:

- Release artifacts for macOS and Linux
- Installer or package-manager recipes
- Richer `doctor` output for attachment and forwarding failures
- More ergonomic `kclip ssh` convenience flow
- `pbcopy` / `pbpaste` shim installation for remote environments
- More explicit troubleshooting docs for tmux, ProxyJump, and stale sockets

Non-goals for v1:

- Clipboard history or automatic sync
- Long-running global daemon
- Binary/image clipboard payloads
- Windows, Android, or iOS binaries
- OpenSSH server policy bypasses

## License

MIT License. See [LICENSE](LICENSE).
