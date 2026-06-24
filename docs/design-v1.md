# kclip v1 設計書

| 項目 | 内容 |
|---|---|
| Status | Proposed — Existing SSH Attach Revision |
| 対象 | `kclip` v1 / agent protocol v1 |
| 対応OS | macOS、Linux |
| 実装 | Kotlin/Native CLI |
| 改訂日 | 2026-06-24 |
| 想定ライセンス | Apache-2.0 |

> **改訂の要点:** v1 の primary path を `kclip ssh` managed session から、既存の通常 SSH session に対する `kclip pair` / `kclip attach` へ変更した。`kclip ssh` は同じ attachment architecture を自動化する convenience command とする。

このファイルは現行設計書の全体置換版である。特に第1〜6章、第8章、第11〜25章、第28〜31章を差し替え、既存 SSH attach、pairing、paste permission、ControlMaster/dedicated transport、再接続、診断を v1 の中心へ移した。

## 1. 概要

`kclip` は、ローカル環境と SSH 接続先の双方で同じ操作感を提供する、テキスト専用のクリップボード CLI である。

v1 の主役は、**すでに通常の `ssh` でログインしている remote shell を、後からローカルのクリップボード agent に attach すること**である。

```sh
# すでに接続済み
local$ ssh dev@example.com
remote$ kclip pair --paste

# 別のローカル terminal で
local$ kclip attach --paste=allow dev@example.com
Pairing code: KC1-6X4P-9Q2K-H7MT-W3DN

# 元の remote shell に戻る
remote$ printf 'from remote' | kclip copy
remote$ kclip paste
```

`kclip attach` は、remote 側から local 側へ直接接続できることを前提にしない。ローカルから SSH reverse forwarding を追加し、remote の Unix domain socket を local agent の Unix domain socket へ接続する。

reverse forwarding の作成方法は次の優先順位とする。

1. 対象ホストに対応する既存 OpenSSH ControlMaster があれば、`ssh -O forward` で forwarding を追加する。
2. ControlMaster がなければ、ローカルから別の `ssh -NT -R ...` 接続を張り、clipboard 専用 sidecar とする。
3. どちらも remote 側の stream-local forwarding を利用できなければ attach は失敗する。

通常の SSH 接続そのものへ、remote shell から後付けで forwarding を注入する一般的な方法はない。したがって attach には、ローカル側での一度の操作が必要である。

v1 の中心機能は次の4点である。

1. **ローカルのネイティブクリップボード操作**
   - macOS: `/usr/bin/pbcopy`、`/usr/bin/pbpaste`
   - Linux/Wayland: `wl-copy`、`wl-paste`
   - Linux/X11: `xclip`、次点で `xsel`

2. **既存 SSH セッションへの attach**
   - remote の `kclip pair` が、現在の controlling TTY に対する一時 pairing request を作る。
   - local の `kclip attach` が agent と reverse forwarding を用意する。
   - pairing 完了後、remote の `kclip copy` / `kclip paste` は current TTY に紐づく attachment を利用する。

3. **安全側の clipboard capability**
   - local clipboard への write、すなわち remote の `copy` は既定で許可する。
   - local clipboard の read、すなわち remote の `paste` は既定で拒否し、local 側の明示許可を必須とする。
   - capability は attachment ごとに固定し、永続 trust store は持たない。

4. **attach できない場合の限定的 fallback**
   - `copy` は OSC 52 を best-effort fallback として利用できる。
   - `paste` の OSC 52 query は v1 では実装しない。
   - `kclip ssh` は attach flow を自動化する convenience command として残すが、必須経路ではない。

常駐するシステム全体の daemon は導入しない。local 側では attachment ごとの supervisor/agent だけが、その attachment の寿命中に動作する。

---

## 2. 目標

### 2.1 v1 の目標

- macOS/Linux 上で Kotlin/Native の単一実行ファイルとして動作する。
- ローカルで `copy` / `paste` を提供する。
- すでに開始済みの通常 SSH セッションを、remote shell から開始する pairing と local の attach により利用可能にする。
- attach 後は remote shell 上で `kclip copy` / `kclip paste` を自然に実行できる。
- ControlMaster が利用できる場合は、既存 master connection に `ssh -O forward` で reverse forwarding を追加する。
- ControlMaster が利用できない場合は、local から clipboard 専用の SSH connection を追加して同じ機能を提供する。
- remote host から local host への routability、local SSH server、公開 TCP port を要求しない。
- paste を v1 の正式要件として扱い、terminal emulator に依存しない agent 経路で提供する。
- paste は local 側の明示許可なしに有効化しない。
- `pbcopy` / `pbpaste` 互換 shim を提供する。
- クリップボード本文を改変しない。改行を追加・削除しない。
- ペイロードは UTF-8 の `text/plain` に限定する。
- pairing、attachment、agent protocol、transport、clipboard backend を分離し、将来の Windows 実装で common 層を再利用できるようにする。
- SSH の暗号化、ホスト鍵検証、認証設定は OpenSSH に委ね、独自 SSH 実装を持たない。
- `doctor` が「未 attach」「paste denied」「agent unreachable」「forwarding rejected」などを具体的な次の操作付きで説明できるようにする。
- `kclip ssh --paste=allow` を、上記 attachment architecture の自動化として提供する。

### 2.2 非目標

v1 では以下を実装しない。

- 画像、HTML、RTF、複数 MIME type
- クリップボード履歴、監視、端末間の自動同期
- system-wide または login-time の常駐 daemon
- 永続的な host trust store
- OSC 52 query による paste
- remote host から local host への直接 TCP 接続
- local の SSH server を使う reverse SSH
- mosh 対応
- Windows バイナリ
- Android、iOS、JVM 版
- 同一 remote UID のプロセス間での厳密な権限分離
- root からの隔離
- tmux/screen の同一 pane を複数 local client が同時表示している場合の「現在見ている local client」自動判定
- 手動の多段 SSH から local への経路を自動推測すること
- OpenSSH 以外の SSH クライアントのサポート
- SSH server が禁止している forwarding の迂回

---

## 3. 設計判断の要約

| 判断 | v1 の選択 |
|---|---|
| primary UX | remote の `kclip pair` と local の `kclip attach` |
| attach の scope | pairing を実行した controlling TTY |
| primary transport | SSH remote stream-local forwarding |
| transport 選択 | ControlMaster + `ssh -O forward`、なければ private dedicated master + `ssh -O forward` |
| remote→local 接続 | 不要。すべて local から開始 |
| local agent | attachment ごとの supervisor process |
| pairing | 80-bit one-time code、既定10分で失効 |
| agent 認証 | attachment ごとの random 128-bit nonce |
| local clipboard write | `copy`。既定 allow、`--copy=deny` で禁止可能 |
| local clipboard read | `paste`。既定 deny、local の `--paste=allow` が必須 |
| paste 経路 | attachment agent のみ |
| OSC 52 | copy の fallback および明示 backend のみ |
| OSC 52 query | v1 では非採用 |
| tmux/screen | pane/window の controlling TTY に attachment を bind |
| 再接続 | supervisor が同じ endpoint で再 forward。対話認証が必要なら `kclip reconnect` |
| `kclip ssh` | attach/pairing を自動化する convenience wrapper |
| wire format | 固定長 header + raw UTF-8 body の versioned binary protocol |
| local clipboard | OS command を shell を介さず起動 |
| MIME | `text/plain;charset=utf-8` のみ |
| 通常上限 | 1 MiB |
| OSC 52 上限 | 100 KiB |
| 永続設定 | CLI option と versioned runtime state。永続 trust は持たない |
| Windows の準備 | common interface と protocol は OS 非依存。Windows transport は後で決定 |

---

## 4. ユーザー向け CLI

### 4.1 コマンド一覧

```text
kclip copy
    [--backend=auto|attachment|system|osc52]
    [--attachment=<id>]
    [--max-bytes=<bytes>]

kclip paste
    [--backend=auto|attachment|system]
    [--attachment=<id>]
    [--max-bytes=<bytes>]

# remote 側で実行
kclip pair
    [--paste]
    [--expires=<duration>]
    [--replace]

kclip unbind
    [--attachment=<id>]

# local 側で実行
kclip attach
    [--copy=allow|deny]
    [--paste=deny|allow]
    [--transport=auto|controlmaster|dedicated]
    [--control-path=<path>]
    [--ssh=<path>]
    [--pairing-code-stdin]
    <destination>
    [-- <ssh-options...>]

kclip reconnect <attachment-id>
kclip detach <attachment-id>
kclip attachments [--json]

kclip ssh
    [--copy=allow|deny]
    [--paste=deny|allow]
    [--ssh=<path>]
    <destination>
    [-- <ssh-options...>]

kclip doctor [--json]
kclip shim install [--dir=<path>] [--force]
kclip version
```

以下は内部コマンドであり、通常の help には表示しない。

```text
kclip _attach-agent ...
kclip _managed-session ...
```

### 4.2 初回セットアップ

local と利用する remote host の双方へ、agent protocol v1 を実装した `kclip` binary を置く。

```sh
# local
kclip version
kclip doctor

# remote
ssh dev@example.com
kclip version
kclip doctor
```

v1 の attach に必要な追加 setup はない。

- local の Remote Login/sshd は不要
- remote から local への route は不要
- local の常駐 daemon を事前起動しない
- SSH config の変更は必須ではない
- ControlMaster がなければ dedicated sidecar を自動利用

追加認証なしで attach しやすくするため、ControlMaster は任意で設定できる。

```sshconfig
Host dev
    HostName dev.example.com
    User dev
    ControlMaster auto
    ControlPath ~/.ssh/cm/%C
    ControlPersist 10m
```

`~/.ssh/cm` は local user だけが書き込める directory とする。

```sh
mkdir -p ~/.ssh/cm
chmod 700 ~/.ssh/cm
```

この設定は kclip の必須条件ではない。設定前から存在する SSH session や、ControlMaster を使わない session でも dedicated attach が使える。

remote で `pbcopy` / `pbpaste` 名を使いたい場合だけ shim を入れる。

```sh
kclip shim install
```

### 4.3 `copy`

- stdin を EOF まで読む。
- UTF-8 とサイズ上限を検証する。
- 成功時は stdout に何も書かない。
- 改行を追加・削除しない。
- `--backend=auto` では current TTY に bind された attachment を最優先する。
- attachment が存在せず SSH session 内で controlling TTY がある場合だけ、OSC 52 を fallback 候補とする。
- attachment の記録があるのに agent が unreachable の場合、OSC 52 へ黙って fallback しない。permission と routing の意図を維持するためである。

```sh
printf 'hello' | kclip copy
cat README.md | kclip copy
```

### 4.4 `paste`

- attachment または local system clipboard の bytes を stdout にそのまま出す。
- 末尾改行を追加しない。
- 診断メッセージは stderr のみに出す。
- SSH session 内では attachment が必須である。
- OSC 52 query へ自動 fallback しない。
- attachment に PASTE capability がなければ、明示的な permission error とする。

```sh
kclip paste > note.txt
value="$(kclip paste)"
```

### 4.5 remote の `pair`

`kclip pair` は、実行した remote shell の controlling TTY に対する one-time pairing request を作り、local attach が完了するまで foreground で待機する。

```text
remote$ kclip pair --paste

kclip pairing
  remote account: dev (uid 1000)
  terminal: /dev/pts/7
  requests: copy, paste
  expires: 10 minutes

On your local machine, run:
  kclip attach --paste=allow <same-ssh-destination>

Pairing code:
  KC1-6X4P-9Q2K-H7MT-W3DN

Waiting for local attachment...
```

`--paste` は remote 側の要求であり、許可ではない。local の `--paste=allow` がなければ PASTE capability は付与されない。

pairing が成功すると、lease と TTY binding を atomically 保存して終了する。

```text
Attached: KC-A7D2
  copy: allowed
  paste: allowed

remote$ printf 'hello' | kclip copy
remote$ kclip paste
```

同じ TTY に live または stale binding がある場合、既定では上書きしない。

```text
kclip: this terminal is already bound to attachment KC-A7D2
hint: use `kclip doctor` to inspect it, or `kclip pair --replace`
```

`--replace` は remote binding を置き換えるが、古い local supervisor を停止できるとは限らない。古い attachment は local 側で `kclip detach` する。

pairing code の既定有効期間は10分。code は成功時に消費し、再利用できない。

### 4.6 local の `attach`

安全な既定フローでは pairing code を引数に含めず、TTY から読み取る。これにより local shell history と process list への露出を避ける。

```text
local$ kclip attach --paste=allow dev@example.com
Pairing code: KC1-6X4P-9Q2K-H7MT-W3DN

Connecting to dev@example.com...
Using existing ControlMaster.
Remote request:
  account: dev (uid 1000)
  terminal: /dev/pts/7
  copy: requested
  paste: requested

Warning: allowing paste lets processes running as this remote UID,
and remote root, read your local clipboard while the attachment is active.

Attached: KC-A7D2
  transport: controlmaster
  copy: allowed
  paste: allowed
```

ControlMaster が見つからない場合は自動的に dedicated connection を使う。

```text
No usable ControlMaster was found.
Opening a dedicated SSH forwarding connection...
dev@example.com's password:
Attached: KC-A7D2
  transport: dedicated
  copy: allowed
  paste: allowed
```

`--pairing-code-stdin` は script や password manager 連携用である。

```sh
printf '%s\n' "$CODE" |
  kclip attach --pairing-code-stdin --paste=allow dev@example.com
```

pairing code を command-line option として渡す形式は提供しない。秘密値を argv に残さないためである。

`--` より後ろは、destination より前に挿入する OpenSSH option として扱う。

```sh
kclip attach --paste=allow prod -- -J bastion -p 2222
kclip attach prod -- -i ~/.ssh/id_ed25519 -F ~/.ssh/config
```

local user が、元の SSH 接続で使用した alias、`ProxyJump`、port、identity、custom `ControlPath` を再現できる必要がある。remote 側からそれらを推測することはできない。

`<same-ssh-destination>` は、remote の `kclip pair` が実行された filesystem / UID namespace へ local から到達するための SSH destination である。pairing code 自体は destination を符号化しないため、次のような場合は利用者が local 側で正しい alias/options を選ぶ必要がある。

- 元の SSH が `-J` / `ProxyJump` を使っていた
- 元の SSH が `-p`、`-l`、`-i`、`-F`、custom `ControlPath` を使っていた
- SSH alias が load balancer、ephemeral VM、container shell へ解決される
- remote 内でさらに手動 `ssh` して別 host / container に入っている

`kclip attach` は PAIR metadata として remote-reported username、uid、hostname、TTY path を表示する。ただしこれらは remote process が報告する値であり、cryptographic identity ではない。attach の成功条件は「local user が選んだ SSH destination へ作られた reverse forwarding 越しに、正しい pair credential を持つ waiting pair process と protocol handshake が完了したこと」である。

destination が違う host / namespace に着地した場合、通常は remote socket が存在せず timeout する。偶然同じ socket path が存在しても pair credential が一致しなければ拒否される。

### 4.7 最短の既存 SSH flow

```text
# terminal A: すでに接続済み
local-A$ ssh dev@example.com
remote$ kclip pair --paste
Pairing code: KC1-6X4P-9Q2K-H7MT-W3DN

# terminal B: local
local-B$ kclip attach --paste=allow dev@example.com
Pairing code: KC1-6X4P-9Q2K-H7MT-W3DN
Attached: KC-A7D2

# terminal A: remote
remote$ printf 'hello' | kclip copy
remote$ kclip paste
```

必要な新規操作は remote の `kclip pair` と local の `kclip attach` の2つである。一度 attach すれば、その attachment が生きている間は copy/paste ごとの確認は要求しない。

local terminal が1つしかない場合は、新しい tab/window を開くのが最も分かりやすい。OpenSSH の escape が有効な対話 session では、行頭で `~^Z` を入力して SSH client を一時 background にし、local shell で `kclip attach` を実行した後 `fg` で戻る方法もある。ただし tmux や nested SSH では escape の到達先が分かりにくいため、基本手順にはしない。

### 4.8 attachment 管理

local:

```sh
kclip attachments
kclip reconnect KC-A7D2
kclip detach KC-A7D2
```

remote:

```sh
kclip doctor
kclip unbind
```

`detach` は local agent と forwarding を停止する。remote の lease/binding は即時には削除できないため stale になり、次回 `doctor` または操作時に検出される。

`unbind` は current TTY の binding を削除するが、local supervisor は停止しない。

### 4.9 `kclip ssh`

`kclip ssh` は、新しい接続を開始するときに pair/attach を自動化する convenience command である。

```sh
kclip ssh --paste=allow dev@example.com
```

内部では同じ agent、wire protocol、capability model、TTY binding を使う。専用の別機能ではなく、`attach` architecture の short path とする。

`kclip ssh` を使わずに開始した通常の SSH session でも、v1 の全 copy/paste 機能を利用できなければならない。

### 4.10 `pbcopy` / `pbpaste` shim

`argv[0]` に依存した multi-call binary にはしない。POSIX shell wrapper を生成する。

```sh
kclip shim install
```

既定の出力先は `${HOME}/.local/bin`。

`pbcopy`:

```sh
#!/bin/sh
exec kclip copy "$@"
```

`pbpaste`:

```sh
#!/bin/sh
exec kclip paste "$@"
```

既存ファイルは `--force` なしでは上書きしない。生成後、対象 directory が `PATH` にない場合だけ案内を表示する。

---

## 5. バックエンド選択

### 5.1 runtime context

バックエンド選択は CLI command 自身に埋め込まず、resolver に集約する。

```kotlin
enum class ClipboardOperation {
    COPY,
    PASTE,
}

enum class BackendPreference {
    AUTO,
    ATTACHMENT,
    SYSTEM,
    OSC52,
}

data class RuntimeContext(
    val attachment: AttachmentLease?,
    val attachmentLookupError: KclipError?,
    val isSshSession: Boolean,
    val controllingTty: TtyIdentity?,
    val environment: Map<String, String>,
)
```

`attachmentLookupError` は「binding はあるが lease が壊れている」「agent が unreachable」と「binding がない」を区別するために保持する。

### 5.2 `auto` の規則

#### copy

1. current TTY に live attachment が bind されていれば attachment backend
2. binding があるが agent 接続に失敗、nonce 不一致、protocol mismatch のいずれかなら明示エラー
3. binding がなく、SSH session かつ controlling TTY があれば OSC 52
4. それ以外は local system backend

attachment が壊れている場合に OSC 52 へ黙って fallback しない。`--copy=deny`、host selection、paste/copy の routing といった attachment の意図を迂回しないためである。

#### paste

1. current TTY に live attachment が bind されていれば attachment backend
2. binding はあるが PASTE capability がなければ permission error
3. binding があるが agent が unreachable なら stale attachment error
4. SSH session で binding がなければ pairing guidance を含む error
5. SSH session でなければ local system backend

通常の SSH 接続で remote desktop の `DISPLAY` や `WAYLAND_DISPLAY` が設定されていても、`auto` paste が remote GUI clipboard を読まないようにする。

### 5.3 明示指定

- `--backend=attachment`: current TTY の binding または `--attachment` がなければ失敗
- `--backend=system`: 現在実行中の OS の clipboard を操作
- `--backend=osc52`: copy のみ。paste では CLI usage error
- `--backend=auto`: 上記規則

`--attachment=<id>` は、TTY のない script や current TTY 以外の attachment を明示するための advanced option である。attachment ID は秘密ではない。認証は lease 内の nonce が行う。

### 5.4 代表的な失敗メッセージ

未 attach の paste:

```text
kclip: no local clipboard attachment is bound to this terminal
hint:
  remote$ kclip pair --paste
  local$  kclip attach --paste=allow <same-ssh-destination>
```

paste denied:

```text
kclip: paste is denied for attachment KC-A7D2
The local user allowed clipboard writes only.
hint: pair again and attach with `--paste=allow`
```

stale attachment:

```text
kclip: attachment KC-A7D2 exists, but its local agent is unreachable
hint:
  local$  kclip attachments
  local$  kclip reconnect KC-A7D2
or replace it from this shell:
  remote$ kclip pair --replace --paste
```

protocol mismatch:

```text
kclip: attachment KC-A7D2 uses unsupported protocol version 2
hint: update kclip on both local and remote hosts, then pair again
```

---

## 6. 全体アーキテクチャ

### 6.1 primary architecture

```text
┌──────────────────────────── remote macOS/Linux ───────────────────────────┐
│                                                                           │
│  すでに存在する login shell / tmux pane                                   │
│                                                                           │
│  remote$ kclip pair --paste                                                │
│       │                                                                   │
│       ├─ pairing code と remote socket path を生成                         │
│       ├─ current controlling TTY を取得                                   │
│       └─ reverse-forwarded socket の出現を待つ                             │
│                                │                                           │
│  remote$ kclip copy/paste      │ /tmp/kclip-r-<socket-id>.sock             │
│       │                        ▼                                           │
│       └──────── AttachmentClient ───────────────────────────────┐          │
└─────────────────────────────────────────────────────────────────┼──────────┘
                                                                  │
                         OpenSSH remote stream-local forwarding    │
                         remote socket -> local Unix socket        │
                                                                  │
┌──────────────────────────── local macOS/Linux ──────────────────┼──────────┐
│                                                                 ▼          │
│  local$ kclip attach dev@example.com                                        │
│       │                                                                     │
│       ├─ per-attachment AgentSupervisor                                     │
│       │    ├─ local Unix socket listener                                    │
│       │    ├─ pairing verifier                                              │
│       │    ├─ capability policy                                             │
│       │    └─ ClipboardBackend                                              │
│       │                                                                     │
│       └─ ReverseForwardTransport                                            │
│            ├─ ControlMaster: ssh -O forward -R ...                          │
│            └─ Dedicated:   ssh -fNT -M -S ... -R ...                        │
│                                                                            │
│  ClipboardBackend                                                          │
│    pbcopy/pbpaste | wl-copy/wl-paste | xclip | xsel                         │
└────────────────────────────────────────────────────────────────────────────┘
```

### 6.2 attach の処理フロー

1. remote の `kclip pair` が secure random pairing code を生成する。
2. code から domain-separated hash を使って remote socket path と pair credential を導出する。
3. `kclip pair` は current TTY identity と要求 capability を保持して待機する。
4. local の `kclip attach` が code を TTY または stdin から読む。
5. local は per-attachment agent を起動し、local Unix socket を bind する。
6. local は ControlMaster を探索し、利用可能なら `ssh -O forward` を実行する。
7. ControlMaster がなければ、private ControlPath を持つ dedicated `ssh -fNT -M -R` connection を開始する。
8. remote の `kclip pair` が remote socket へ接続し、PAIR request を送る。
9. local agent が code、requested capability、local policy を検証する。
10. local agent が attachment ID と random nonce を返す。
11. remote は lease と TTY binding を temp file に書き、atomic rename する。
12. remote が PAIR_CONFIRM を送る。
13. local agent が code を消費し、attachment を ACTIVE にする。
14. local `attach` と remote `pair` の双方が success を表示する。
15. 以後、remote の `copy` / `paste` は TTY binding から lease を引き、agent に接続する。

PAIR_CONFIRM を入れるのは、local が成功を表示したのに remote 側の lease 保存に失敗した、という half-paired state を避けるためである。

### 6.3 process lifetime

local:

- `kclip attach` の foreground command は pairing 完了まで利用者の terminal を保持する。
- attachment ごとの `_attach-agent` process が local listener、capability、nonce、transport metadata を保持する。
- dedicated SSH は private ControlPath を持つ background master として動作し、agent が `ssh -O check` / `exit` で管理する。
- ControlMaster attach の場合、forward 自体は既存 master の寿命に依存する。
- agent は `kclip detach`、fatal error、明示 shutdown まで動作する。
- system-wide daemon は作らない。

remote:

- `kclip pair` は pairing 完了または timeout まで foreground で待つ。
- pairing 完了後は終了し、常駐 remote daemon を残さない。
- `copy` / `paste` は操作ごとに Unix socket connection を作る。
- lease と TTY binding は runtime file として残る。agent が消えれば stale と判定される。

### 6.4 controlling TTY scope

attachment の既定 scope は pairing を実行した controlling TTY である。

```kotlin
data class TtyIdentity(
    val device: ULong,
    val inode: ULong,
    val displayPath: String,
)
```

`displayPath` だけでなく `stat(2)` の device/inode を使い、PTY path の再利用を検出する。

- 同じ shell の subshell は同じ attachment を使う。
- tmux/screen の pane/window は通常それぞれ別 PTY なので、pane 単位に pair できる。
- tmux detach/reattach 後も pane の PTY が維持されれば binding は維持される。
- 同じ tmux pane を複数 local client が同時に見ている場合、どの local clipboard を使うべきか自動判定できない。v1 は last explicit binding wins とする。
- `sudo` などで UID が変わると runtime registry も変わる。別 UID から利用するにはその UID で改めて pair する。

TTY binding は routing と UX のための仕組みであり、remote 同一 UID 内の security boundary ではない。

### 6.5 copy のデータ経路

```text
remote stdin
  -> ClipboardPayload validation
  -> TTY binding / AttachmentLease lookup
  -> protocol COPY request + nonce
  -> remote Unix socket
  -> SSH reverse forwarding
  -> local AgentSupervisor
  -> local ClipboardBackend.copy()
```

### 6.6 paste のデータ経路

```text
local ClipboardBackend.paste()
  -> local AgentSupervisor
  -> protocol PASTE response
  -> SSH reverse forwarding
  -> remote AttachmentClient
  -> remote stdout
```

### 6.7 制約

- remote shell だけの操作では reverse forwarding を新設できない。
- attach には local 側で、対象 remote host へ SSH できる経路が必要である。
- SSH server が remote stream-local forwarding を禁止している場合、agent attach は利用できない。
- original SSH command の alias、jump host、custom option は remote から復元できない。local user が attach 時に再指定する。
- pair code は SSH destination を符号化しない。code は「待機中の pair request」と「local agent」を結ぶだけである。
- dedicated connection は original shell と別の SSH authentication を行う可能性がある。
- load-balanced alias、ephemeral VM、container namespace などで新しい SSH connection が original shell と同じ remote filesystem に着地しない場合、dedicated attach は成立しない。active ControlMaster または stable destination が必要になる。
- attachment agent が失われた場合、OSC 52 query による paste へ自動 fallback しない。

---

## 7. Kotlin Multiplatform / Native 構成

### 7.1 対象 target

v1 の release 方針:

| target | 扱い |
|---|---|
| `macosArm64` | 正式サポート、release gate |
| `linuxX64` | 正式サポート、release gate |
| `linuxArm64` | 正式 artifact、CI 可能な範囲で integration test |
| `macosX64` | upstream の deprecated target。提供できる期間のみ best effort |
| `mingwX64` | v1 では build しない。将来 target |

Intel Mac 対応は Kotlin/Native upstream の target 提供状況に依存する。`macosX64` が削除された時点で、v1 系の新規 release から外すことを許容する。

### 7.2 source set

```text
src/
  commonMain/kotlin/
    cli/
    domain/
    application/
    protocol/
    diagnostics/
  commonTest/kotlin/

  unixMain/kotlin/
    ipc/
    process/
    terminal/
    signals/
    filesystem/
  unixTest/kotlin/

  macosMain/kotlin/
    clipboard/
    platform/
  macosTest/kotlin/

  linuxMain/kotlin/
    clipboard/
    platform/
  linuxTest/kotlin/

  mingwMain/kotlin/          # 将来
    clipboard/
    ipc/
    process/
    terminal/
```

原則は次の通り。

- ドメイン interface と use case は `commonMain`
- POSIX socket、`poll`、`posix_spawnp`、signal、`/dev/tty` は `unixMain`
- clipboard command の discovery は `macosMain` / `linuxMain`
- `expect` / `actual` は composition root など、ごく小さい境界に限定
- OS 分岐を `commonMain` の `if (os == ...)` に散らさない

### 7.3 Gradle の骨格

以下は構成を示すための骨格であり、repository 作成時に Kotlin 2.4 系の実際の DSL で compile を通して固定する。Clikt の具体 version は version catalog と dependency lock で固定し、設計書には存在しない version を直書きしない。

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.0"
}

repositories {
    mavenCentral()
}

kotlin {
    val macosArm64Target = macosArm64 {
        binaries.executable { baseName = "kclip" }
    }
    val linuxX64Target = linuxX64 {
        binaries.executable { baseName = "kclip" }
    }
    val linuxArm64Target = linuxArm64 {
        binaries.executable { baseName = "kclip" }
    }

    // Upstream target が提供される期間だけ opt-in build。
    val macosX64Target =
        if (providers.gradleProperty("kclip.enableMacosX64").orNull == "true") {
            @Suppress("DEPRECATION")
            macosX64 {
                binaries.executable { baseName = "kclip" }
            }
        } else {
            null
        }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.clikt)
            }
        }
        val commonTest by getting

        val unixMain by creating {
            dependsOn(commonMain)
        }
        val unixTest by creating {
            dependsOn(commonTest)
        }
        val macosMain by creating {
            dependsOn(unixMain)
        }
        val macosTest by creating {
            dependsOn(unixTest)
        }
        val linuxMain by creating {
            dependsOn(unixMain)
        }
        val linuxTest by creating {
            dependsOn(unixTest)
        }

        macosArm64Target.compilations["main"].defaultSourceSet.dependsOn(macosMain)
        macosArm64Target.compilations["test"].defaultSourceSet.dependsOn(macosTest)
        linuxX64Target.compilations["main"].defaultSourceSet.dependsOn(linuxMain)
        linuxX64Target.compilations["test"].defaultSourceSet.dependsOn(linuxTest)
        linuxArm64Target.compilations["main"].defaultSourceSet.dependsOn(linuxMain)
        linuxArm64Target.compilations["test"].defaultSourceSet.dependsOn(linuxTest)

        macosX64Target?.let { target ->
            target.compilations["main"].defaultSourceSet.dependsOn(macosMain)
            target.compilations["test"].defaultSourceSet.dependsOn(macosTest)
        }
    }
}
```

`kotlinx-coroutines` は v1 の依存に含めない。agent は単一スレッドで十分であり、C interop と cancellation の境界を増やさないためである。

---
## 8. common 層の設計

### 8.1 Outcome と error

例外は programmer error と platform adapter 内の変換境界に限定し、通常の失敗は typed result で返す。

```kotlin
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val error: KclipError) : Outcome<Nothing>
}

sealed interface KclipError {
    val message: String
    val detail: String?
    val exitCode: Int

    data class InvalidInput(
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.USAGE
    }

    data class BackendUnavailable(
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.BACKEND_UNAVAILABLE
    }

    data class PermissionDenied(
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.PERMISSION_DENIED
    }

    data class AttachmentUnavailable(
        val attachmentId: AttachmentId?,
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.ATTACHMENT
    }

    data class ProtocolFailure(
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.PROTOCOL
    }

    data class ForwardingRejected(
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.FORWARDING
    }

    data class TooLarge(
        val actualBytes: Long?,
        val maxBytes: Int,
        override val message: String = "clipboard payload is too large",
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.TOO_LARGE
    }

    data class TimedOut(
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.TIMEOUT
    }

    data class SubprocessFailure(
        val command: String,
        val status: Int?,
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.SUBPROCESS
    }

    data class Internal(
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.INTERNAL
    }
}
```

`detail` に clipboard 本文、pairing code、pair credential、attachment nonce を入れてはならない。

### 8.2 clipboard payload

v1 は UTF-8 text のみ受け付ける。

```kotlin
class ClipboardPayload private constructor(
    private val content: ByteArray,
) {
    val size: Int get() = content.size

    fun copyBytes(): ByteArray = content.copyOf()

    companion object {
        fun fromUtf8Bytes(
            bytes: ByteArray,
            maxBytes: Int,
        ): Outcome<ClipboardPayload>
    }
}
```

実装要件:

- constructor で defensive copy
- `maxBytes` を超える前に読み取りを停止
- 不正 UTF-8 を拒否
- NUL byte は許可する。backend が扱えない場合は backend error とする
- newline normalization をしない
- `toString()` で本文を出さない

stdin の読み取りには size-limited reader を使い、`maxBytes + 1` より大きな配列を確保しない。

### 8.3 clipboard backend

```kotlin
enum class ClipboardCapability {
    COPY,
    PASTE,
}

interface ClipboardBackend {
    val id: String
    val capabilities: Set<ClipboardCapability>

    fun copy(payload: ClipboardPayload): Outcome<Unit>

    fun paste(maxBytes: Int): Outcome<ClipboardPayload>
}

interface ClipboardBackendResolver {
    fun resolve(
        operation: ClipboardOperation,
        preference: BackendPreference,
        context: RuntimeContext,
    ): Outcome<ClipboardBackend>
}
```

resolver は capability を満たす backend だけを返す。capability mismatch は agent への request 前に検出し、local agent でも再検証する。

### 8.4 pairing と attachment

```kotlin
data class AttachmentId(val value: String)

class PairingCode private constructor(
    private val entropy: ByteArray,
) {
    fun display(): String
    fun deriveSocketId(): SocketId
    fun deriveCredential(): PairCredential
    fun destroy()

    companion object {
        fun generate(random: SecureRandom): PairingCode
        fun parse(text: String): Outcome<PairingCode>
    }
}

class AttachmentNonce private constructor(
    private val bytes: ByteArray,
) {
    fun copyBytes(): ByteArray
    fun matches(candidate: ByteArray): Boolean

    companion object {
        fun generate(random: SecureRandom): AttachmentNonce
        fun fromBytes(bytes: ByteArray): Outcome<AttachmentNonce>
    }
}

data class TtyIdentity(
    val device: ULong,
    val inode: ULong,
    val displayPath: String,
)

data class PairingRequest(
    val code: PairingCode,
    val scope: TtyIdentity,
    val requestedCapabilities: Set<ClipboardCapability>,
    val expiresAt: MonotonicDeadline,
)

data class AttachmentLease(
    val formatVersion: UShort,
    val id: AttachmentId,
    val endpoint: IpcEndpoint,
    val nonce: AttachmentNonce,
    val capabilities: Set<ClipboardCapability>,
    val scope: TtyIdentity,
    val createdAtEpochSeconds: Long,
)
```

Kotlin/Native common codeでは secret を value class にしない。`ByteArray` の参照 equality、copy、破棄を明示する必要があるためである。

pairing code は Crockford Base32 形式の 80-bit entropy とする。

```text
KC1-6X4P-9Q2K-H7MT-W3DN
```

- 大文字小文字と hyphen を正規化して parse
- `I`、`L`、`O`、`U` など紛らわしい文字を生成しない
- socket ID と credential は別の domain separator を使って SHA-256 から導出
- path に credential そのものを含めない
- code は成功または expiry 後に memory から best-effort で zeroize

### 8.5 attachment registry

```kotlin
interface AttachmentRegistry {
    fun findForTty(tty: TtyIdentity): Outcome<AttachmentLease?>
    fun findById(id: AttachmentId): Outcome<AttachmentLease?>
    fun bind(
        tty: TtyIdentity,
        lease: AttachmentLease,
        replace: Boolean,
    ): Outcome<Unit>
    fun unbind(tty: TtyIdentity): Outcome<Unit>
    fun list(): Outcome<List<AttachmentRecord>>
    fun markStale(id: AttachmentId, reason: String): Outcome<Unit>
    fun prune(): Outcome<PruneResult>
}
```

registry の責務:

- current TTY から attachment ID を解決
- lease の strict parse と version 検証
- device/inode が current TTY と一致することを確認
- temp file + `fsync` + atomic rename による保存
- directory/file owner と mode の検証
- stale state と absent state の区別

`AttachmentRegistry` は remote と local の双方に存在するが、保存内容は異なる。

remote:

- TTY binding
- endpoint
- nonce
- capability
- attachment ID

local:

- supervisor control socket
- destination label
- transport kind
- PID
- capability
- status

local registry に nonce を永続保存しない。nonce は live supervisor の memory にのみ保持する。supervisor が失われたら re-pair を要求する。

### 8.6 reverse forwarding transport

```kotlin
enum class AttachTransportKind {
    CONTROL_MASTER,
    DEDICATED,
}

enum class AttachTransportPreference {
    AUTO,
    CONTROL_MASTER,
    DEDICATED,
}

data class ReverseForwardSpec(
    val destination: String,
    val sshOptions: List<String>,
    val remoteEndpoint: IpcEndpoint.UnixSocket,
    val localEndpoint: IpcEndpoint.UnixSocket,
    val connectTimeout: Duration,
)

sealed interface TransportHealth {
    data object Ready : TransportHealth
    data class Reconnecting(val attempt: Int) : TransportHealth
    data class NeedsUserAuthentication(val message: String) : TransportHealth
    data class Failed(val error: KclipError) : TransportHealth
}

interface ReverseForwardHandle : KclipCloseable {
    val kind: AttachTransportKind

    fun health(): Outcome<TransportHealth>
    fun reconnect(interactive: Boolean): Outcome<Unit>
    fun stop(): Outcome<Unit>
}

interface ReverseForwardTransport {
    fun establish(
        spec: ReverseForwardSpec,
        preference: AttachTransportPreference,
    ): Outcome<ReverseForwardHandle>
}
```

ControlMaster と dedicated connection は同じ interface の実装とし、agent protocol から分離する。

### 8.7 cryptographic hash

pairing material の domain separation と persisted-state checksum に SHA-256 を使う。

```kotlin
interface Sha256 {
    fun digest(parts: List<ByteArray>): ByteArray
}
```

- output は正確に32 bytes
- streaming/full-buffer の実装差を interface 外へ出さない
- RFC/NIST test vector、empty input、multi-part inputをgolden test
- secretを`String`へ変換しない
- platform APIまたは十分にレビューされたcommon implementationを使う
- 新しい独自 hash algorithm を設計しない

### 8.8 platform services

platform 固有実装は composition root でまとめる。

```kotlin
data class PlatformServices(
    val environment: Environment,
    val fileSystem: FileSystem,
    val executableLookup: ExecutableLookup,
    val processSpawner: ProcessSpawner,
    val ipc: LocalIpc,
    val terminal: Terminal,
    val clock: MonotonicClock,
    val wallClock: WallClock,
    val secureRandom: SecureRandom,
    val sha256: Sha256,
    val signalMailboxFactory: SignalMailboxFactory,
    val systemClipboardFactory: SystemClipboardFactory,
    val ttyIdentityProvider: TtyIdentityProvider,
    val attachmentRegistry: AttachmentRegistry,
    val sshConfigResolver: SshConfigResolver,
)

expect fun createPlatformServices(): PlatformServices
```

`expect/actual` はこの factory に限定する。各サービス自体は通常の common interface とし、fake を差し込めるようにする。

### 8.9 application use cases

```kotlin
class CopyUseCase(
    private val resolver: ClipboardBackendResolver,
    private val stdin: ByteInput,
) {
    fun execute(options: CopyOptions, context: RuntimeContext): Outcome<Unit>
}

class PasteUseCase(
    private val resolver: ClipboardBackendResolver,
    private val stdout: ByteOutput,
) {
    fun execute(options: PasteOptions, context: RuntimeContext): Outcome<Unit>
}

class PairUseCase(
    private val services: PlatformServices,
    private val pairingClient: PairingClient,
) {
    fun execute(options: PairOptions): Outcome<AttachmentLease>
}

class AttachUseCase(
    private val services: PlatformServices,
    private val agentLauncher: AttachmentAgentLauncher,
    private val transport: ReverseForwardTransport,
) {
    fun execute(options: AttachOptions): Outcome<AttachmentSummary>
}

class ReconnectUseCase(
    private val localRegistry: AttachmentRegistry,
    private val agentControl: AgentControlClient,
) {
    fun execute(id: AttachmentId): Outcome<Unit>
}

class DetachUseCase(
    private val localRegistry: AttachmentRegistry,
    private val agentControl: AgentControlClient,
) {
    fun execute(id: AttachmentId): Outcome<Unit>
}

class StartSshSessionUseCase(
    private val services: PlatformServices,
    private val agentLauncher: AttachmentAgentLauncher,
) {
    fun execute(options: SshOptions): Outcome<ExitStatus>
}
```

CLI class は option parsing と表示だけを担い、use case に platform API を直接持ち込まない。

---

## 9. process abstraction

### 9.1 理由

Kotlin/Native runtime はマルチスレッドになり得る。POSIX の `fork()` 後、`exec()` 前に安全に呼べる処理は大きく制限されるため、v1 は command 起動に `posix_spawnp()` を使う。

shell は使用しない。例外は OpenSSH の `RemoteCommand` がリモート側で shell 解釈される境界だけであり、そこでは専用 quoting を行う。

### 9.2 interface

```kotlin
sealed interface Stdio {
    data object Inherit : Stdio
    data object DevNull : Stdio
    data class Pipe(val direction: PipeDirection) : Stdio
    data class ExistingFd(val fd: Int) : Stdio
}

enum class PipeDirection {
    PARENT_READS,
    PARENT_WRITES,
}

data class SpawnSpec(
    val executable: String,
    val argv: List<String>,
    val environmentOverrides: Map<String, String?> = emptyMap(),
    val stdin: Stdio = Stdio.Inherit,
    val stdout: Stdio = Stdio.Inherit,
    val stderr: Stdio = Stdio.Inherit,
    val searchPath: Boolean = true,
)

interface ChildProcess {
    val pid: Long

    fun pollExit(): Outcome<ExitStatus?>
    fun waitForExit(): Outcome<ExitStatus>
    fun send(signal: ProcessSignal): Outcome<Unit>
}

interface ProcessSpawner {
    fun spawn(spec: SpawnSpec): Outcome<SpawnedProcess>
}

data class SpawnedProcess(
    val child: ChildProcess,
    val stdin: ByteOutput?,
    val stdout: ByteInput?,
    val stderr: ByteInput?,
)
```

### 9.3 command runner

clipboard command 用の上位 adapter。

```kotlin
data class CommandSpec(
    val executable: String,
    val args: List<String>,
    val timeout: Duration,
    val stdoutLimit: Int,
    val stderrLimit: Int = 16 * 1024,
)

data class CommandOutput(
    val exitStatus: ExitStatus,
    val stdout: ByteArray,
    val stderr: ByteArray,
)

interface CommandRunner {
    fun run(
        spec: CommandSpec,
        stdin: ByteArray?,
    ): Outcome<CommandOutput>
}
```

要件:

- stdin/stdout/stderr pipe を non-blocking にし、`poll()` で同時に pump する
- timeout 超過時は child に `SIGTERM`、短い grace period 後に `SIGKILL`
- stdout は操作ごとの上限を超えた時点で停止
- stderr は 16 KiB まで保持し、超過分は捨てる
- error detail に stderr を含める場合も control character を sanitize する
- command line に clipboard 本文を入れない

---
## 10. platform clipboard backend

### 10.1 macOS

```kotlin
class MacOsClipboardBackend(
    private val commandRunner: CommandRunner,
) : ClipboardBackend {
    override val id: String = "macos-pbcopy"
    override val capabilities =
        setOf(ClipboardCapability.COPY, ClipboardCapability.PASTE)

    override fun copy(payload: ClipboardPayload): Outcome<Unit>
    override fun paste(maxBytes: Int): Outcome<ClipboardPayload>
}
```

command:

```text
copy:  /usr/bin/pbcopy
paste: /usr/bin/pbpaste
```

- absolute path を使用し、`PATH` hijacking を避ける
- stdin/stdout は raw bytes
- locale 依存の暗黙変換を避けるため、必要に応じて子プロセス環境へ UTF-8 locale を設定する。ただし親環境を全面的に置き換えない
- exit status 0 以外は backend failure
- paste stdout を size limit 付きで読む

### 10.2 Linux discovery

優先順位:

1. Wayland environment かつ `wl-copy` / `wl-paste` が利用可能
2. X11 environment かつ `xclip` が利用可能
3. X11 environment かつ `xsel` が利用可能
4. unavailable

判定:

```kotlin
class LinuxClipboardDiscovery(
    private val environment: Environment,
    private val executableLookup: ExecutableLookup,
) : SystemClipboardFactory {
    override fun create(): Outcome<ClipboardBackend>
}
```

Wayland は `WAYLAND_DISPLAY`、X11 は `DISPLAY` を signal として使う。ただし環境変数だけで成功と判断せず、実行ファイルの存在も確認する。

### 10.3 Wayland

```text
copy:  wl-copy
paste: wl-paste
```

adapter は利用している `wl-clipboard` の option を固定し、次を integration test する。

- NUL、Unicode、末尾改行を含む payload の round trip
- synthetic newline を追加しないこと
- clipboard selection が失われないこと
- size limit を超えた paste を中止できること

必要な「末尾改行を追加しない」option は tool version ごとの差を adapter 内に閉じ込める。shell command string は作らず、argv list で渡す。

### 10.4 X11 / xclip

概念上の command:

```text
copy:  xclip -selection clipboard -in
paste: xclip -selection clipboard -out
```

### 10.5 X11 / xsel

概念上の command:

```text
copy:  xsel --clipboard --input
paste: xsel --clipboard --output
```

xclip/xsel の executable は `PATH` discovery 結果の絶対 path を保存し、その path を起動する。操作のたびに再探索しない。

### 10.6 backend failure の表示

例:

```text
kclip: no usable Linux clipboard backend was found
checked:
  - wl-copy / wl-paste (WAYLAND_DISPLAY is not set)
  - xclip (not found)
  - xsel (not found)
hint: install wl-clipboard for Wayland, or xclip for X11
```

`doctor --json` では機械可読な候補一覧を返す。

---
## 11. OSC 52 backend と paste 方針

### 11.1 v1 での位置付け

OSC 52 は、terminal output を通じて local terminal emulator に clipboard 操作を依頼する仕組みである。

v1 では次の範囲に限定する。

- **copy/write**: attachment がない SSH session での best-effort fallback
- **paste/read query**: 実装しない
- attachment が存在する場合: agent 経路を優先
- stale attachment や denied capability を OSC 52 で迂回しない

### 11.2 copy backend

```kotlin
class Osc52ClipboardBackend(
    private val terminal: Terminal,
    private val base64: Base64Codec,
    private val maxBytes: Int = 100 * 1024,
) : ClipboardBackend {
    override val id: String = "osc52"
    override val capabilities: Set<ClipboardCapability> =
        setOf(ClipboardCapability.COPY)

    override fun copy(payload: ClipboardPayload): Outcome<Unit>
    override fun paste(maxBytes: Int): Outcome<ClipboardPayload> =
        Outcome.Err(
            KclipError.BackendUnavailable(
                message = "OSC 52 paste is not supported in kclip v1",
            )
        )
}
```

clipboard selector `c` と String Terminator を使う。

```text
ESC ] 52 ; c ; BASE64(payload) ESC \
```

概念コード:

```kotlin
fun copy(payload: ClipboardPayload): Outcome<Unit> {
    if (payload.size > maxBytes) {
        return Outcome.Err(
            KclipError.TooLarge(
                actualBytes = payload.size.toLong(),
                maxBytes = maxBytes,
            )
        )
    }

    val encoded = base64.encode(payload.copyBytes())
    val frame = buildByteArray {
        appendAscii("\u001B]52;c;")
        appendAscii(encoded)
        appendAscii("\u001B\\")
    }

    return terminal.openControllingTty().useOutcome { tty ->
        tty.writeAll(frame, deadline = clock.after(2.seconds))
    }
}
```

重要な点:

- stdout ではなく `/dev/tty` に書く
- stdout は空のままにして pipeline を壊さない
- Base64 後の長さを計算してから buffer を確保
- 100 KiB を既定上限とする
- OSC sequence と payload を log に出さない
- success は「sequence を TTY に書けた」ことだけを意味し、terminal clipboard が実際に更新されたことを保証しない

### 11.3 paste 候補の比較

| 案 | 長所 | 問題 | v1 判断 |
|---|---|---|---|
| attachment agent | local 側で capability を管理できる。terminal 非依存。bytes/timeout/error が明確 | local attach が一度必要。SSH forwarding が必要 | **採用** |
| OSC 52 query を自動使用 | attach 不要。対応 terminal では短い | read support と policy が terminal ごとに異なる。TTY response 解析が必要。security boundary が曖昧 | 非採用 |
| OSC 52 query を明示 opt-in | 危険性を利用者に示せる。緊急 fallback になり得る | 実装・test matrix・tmux/screen の複雑さは残る | v1 非採用、将来 experimental 候補 |
| remote GUI clipboard | `DISPLAY` forwarding 等がある環境では動くことがある | local clipboard ではない。session ごとに意味が変わる | `--backend=system` 明示時のみ |

### 11.4 OSC 52 query を採用しない理由

OSC 52 の read/query は仕様上存在し、一部 terminal は対応している。しかし kclip v1 の正式な paste 経路には適さない。

#### terminal policy の差

terminal emulator は clipboard read を敏感な操作として扱う。

- read を常に拒否する実装
- user confirmation を出す実装
- 設定でのみ許可する実装
- query 自体を実装しない terminal

が混在する。copy/write より互換性が低く、成功判定も一貫しない。

#### TTY response の解析

query response は terminal input stream に返るため、実装には次が必要になる。

- controlling TTY を一時的に raw/noncanonical mode にする
- user keystroke と terminal response を区別する
- partial response、timeout、signal interruption を扱う
- termios を必ず復元する
- clipboard response が shell や foreground process に漏れないようにする

remote shell の interactive state を壊すリスクに対して、v1 で得られる価値が小さい。

#### tmux/screen

multiplexer 内では query が次のいずれになるかが構成依存である。

- multiplexer 自身の clipboard query
- outer terminal への passthrough
- drop
- nested multiplexer ごとの再包装

同じ pane を複数 client が表示している場合、どの local terminal の clipboard を読むべきか決められない。

#### 多段 SSH

OSC 52 query を deepest host から outermost terminal まで通すと、local 側の明示的な host/UID capability binding がないまま、深い remote host が local clipboard read を要求できる。

terminal の permission dialog が出る場合も、どの SSH host/UID が要求元かを kclip が確実に表示できない。

#### security semantics

attachment agent では local user が次を明示できる。

```text
destination: dev@example.com
reported UID: 1000
capability: paste
lifetime: this attachment
```

OSC 52 query では、この identity と lifetime を protocol 上で結び付けられない。kclip の paste security model と一致しない。

### 11.5 最終決定

v1 の paste は **attachment agent のみ**とする。

```text
remote$ kclip paste
```

が成功する条件は、current TTY に live attachment が bind され、local agent が PASTE capability を付与していることである。

attach できない環境では明示的に失敗する。

```text
kclip: paste requires a live local clipboard attachment
OSC 52 clipboard queries are intentionally disabled in kclip v1.
hint: run `kclip pair --paste`, then attach from your local machine
```

将来 OSC 52 query を追加する場合も、`auto` には入れず、少なくとも次のような experimental explicit backend とする。

```text
kclip paste --backend=osc52-query --unsafe
```

ただしこれは v1 の scope 外である。

### 11.6 tmux diagnostics

`doctor` は `$TMUX` や `$STY` を検出し、OSC 52 copy fallback の条件を説明する。

- tmux `set-clipboard`
- terminfo の `Ms`
- outer terminal の OSC 52 setting
- nested multiplexer の可能性

clipboard を実際に変更せず完全な疎通確認はできないため、結果は `available` ではなく `likely` / `unknown` とする。

attachment agent 経路は OSC passthrough を必要としない。tmux/screen 内でも current pane/window の PTY binding から agent へ直接接続する。

---

## 12. pairing、attachment、runtime state

### 12.1 用語

| 用語 | 意味 |
|---|---|
| pairing request | remote の `kclip pair` が一時的に待ち受けている状態 |
| pairing code | user が remote から local へ伝える one-time code |
| pair credential | pairing code から導出する protocol credential |
| attachment | 1つの local agent と remote TTY binding の論理的な関連 |
| attachment ID | user-facing な識別子。秘密ではない |
| nonce | attachment protocol request を認証する128-bit secret |
| lease | remote に保存する endpoint、nonce、capability、scope |
| binding | remote TTY identity から attachment ID への対応 |
| supervisor | local で agent と forwarding の寿命を管理する process |

### 12.2 pairing code

```kotlin
data class PairingMaterial(
    val displayCode: PairingCode,
    val pairCredential: PairCredential,
    val socketId: SocketId,
)
```

生成:

```text
entropy = SecureRandom(80 bits)
display = CrockfordBase32(entropy)
pairCredential =
  Truncate128(SHA256("kclip/pair-credential/v1" || entropy))
socketId =
  Truncate96(SHA256("kclip/remote-socket/v1" || entropy))
```

domain separator を分けるため、remote socket path から pair credential を直接導出できない。ただし両者の実効的な探索強度は元の80-bit pairing entropy を超えない。

表示形式:

```text
KC1-6X4P-9Q2K-H7MT-W3DN
```

pair code は:

- 既定10分で expiry
- pairing 成功時に one-time consume
- attach の local argv/environment に入れない
- `--pairing-code-stdin` または controlling TTY から受け取る
- log、diagnostic JSON、crash report に含めない

### 12.3 attachment ID

attachment ID は local agent が secure random から生成する128-bit identifier である。full ID は runtime state と protocol に使い、human output は十分に衝突しにくい prefix を表示する。

```text
full:    7A2D5F1E6B684F0DA41C5D8D8E4A9110
display: KC-A7D2
```

display prefix が local registry 内で衝突した場合、表示桁を伸ばす。

attachment ID は認証情報ではない。

### 12.4 nonce

```kotlin
class AttachmentNonce private constructor(
    private val bytes: ByteArray,
) {
    init {
        require(bytes.size == 16)
    }

    fun copyBytes(): ByteArray = bytes.copyOf()

    fun constantTimeEquals(other: ByteArray): Boolean

    fun destroy()
}
```

nonce は:

- local agent が生成
- PAIR response で SSH tunnel を通して remote に渡す
- remote lease file `0600` に保存
- local agent memory に保持
- local の永続 registry、argv、environment、log には保存しない
- attachment が detach された時点で破棄
- local supervisor が失われた場合は resume せず re-pair

このモデルは、local reboot 後に secret を自動復元する convenience より、attachment の明確な寿命を優先する。

### 12.5 capability

```kotlin
enum class AttachmentCapability(val wireBit: UInt) {
    COPY(1u shl 0),
    PASTE(1u shl 1),
}
```

authority は local agent である。

- remote lease の capability は UX と preflight check のため
- request ごとに local agent が再検証
- remote file を書き換えても capability は増えない
- capability は attachment 中に暗黙変更しない
- 変更には detach/re-pair を使う

### 12.6 remote runtime directory

優先順位:

1. `$XDG_RUNTIME_DIR/kclip` が存在し、current UID 所有、group/other access がなく、local filesystem と判断できる場合
2. `/tmp/kclip-<uid>`

fallback directory の要件:

- owner が current UID
- mode `0700`
- symlink ではない
- open 時に `O_NOFOLLOW` 相当を使用
- owner/mode 不一致なら自動修正せず fail
- directory entry の file type と owner を確認

構成:

```text
$RUNTIME/kclip/
  attachments/
    <full-attachment-id>.lease
  bindings/
    tty-<device>-<inode>.binding
  pairing/
    <socket-id>.request
```

`pairing/*.request` は code 自体を保存しない。pair process の PID、expiry、socket ID、requested capabilities、TTY identity だけを保存し、code/credential は process memory に保持する。

### 12.7 lease format

内部 state に外部 JSON library を要求しないため、versioned binary format とする。

```text
magic             4 bytes  "KCLL"
formatVersion     2 bytes  1
flags             2 bytes  0
attachmentId     16 bytes
nonce             16 bytes
capabilities      2 bytes
endpointType      1 byte
reserved          1 byte
ttyDevice         8 bytes
ttyInode          8 bytes
createdEpoch      8 bytes
endpointLength    2 bytes
endpoint          N bytes UTF-8
checksum         32 bytes SHA-256(previous bytes)
```

checksum は attacker に対する MAC ではなく、partial write/corruption 検出である。security は directory/file permission と nonce comparison に依存する。

保存要件:

1. temp file を同じ directory に作る
2. mode `0600`
3. full bytes を書く
4. `fsync`
5. atomic rename
6. 必要に応じて directory `fsync`

### 12.8 TTY binding

binding file は attachment ID と TTY identity の最小情報だけを持つ。

```text
magic             4 bytes "KCLB"
formatVersion     2 bytes 1
attachmentId     16 bytes
ttyDevice         8 bytes
ttyInode          8 bytes
```

lookup 時に current `/dev/tty` の device/inode と一致しなければ stale とする。path name だけを信頼しない。

`KCLIP_ATTACHMENT=<id>` または `--attachment=<id>` は TTY lookup の明示 override として利用できるが、nonce は環境変数へ展開しない。

### 12.9 local runtime state

```text
$LOCAL_RUNTIME/kclip/
  agents/
    <attachment-id>.ctl.sock
    <attachment-id>.meta
  ssh/
    <attachment-id>.control.sock
```

`.meta` に保存してよい情報:

- attachment ID
- destination label / original destination token
- transport kind
- agent PID
- agent control socket path
- SSH ControlPath
- remote forwarding socket path
- local agent socket path
- capabilities
- status
- created timestamp

endpoint/control path は秘密ではないが、runtime file は `0600` とする。これらは supervisor crash 後の orphan forward cleanup に使える。authentication に必要な full SSH option set は永続化せず、live agent memory にだけ保持する。

保存してはならない情報:

- nonce
- pairing code
- pair credential
- clipboard body
- SSH password/passphrase

### 12.10 lifecycle

```text
PAIRING
  ├─ timeout/cancel ──────────────> CLOSED
  └─ PAIR accepted
        └─ remote lease committed
              └─ PAIR_CONFIRM ───> ACTIVE
                                      ├─ transport loss ─> DEGRADED
                                      │                    ├─ reconnect ─> ACTIVE
                                      │                    └─ detach ────> CLOSED
                                      └─ detach ──────────> CLOSED
```

- PAIRING は既定10分
- ACTIVE は local supervisor の寿命
- DEGRADED 中も remote lease は残るが操作は明示エラー
- supervisor が生きていれば同じ endpoint と nonce で reconnect 可能
- supervisor が失われたら nonce も失われるため re-pair
- remote binding は local detach を直ちに知れないため stale になり得る
- `doctor`、`pair --replace`、`attachments prune` が stale state を扱う

---

## 13. agent wire protocol v1

### 13.1 方針

- 1 connection = 1 request + 1 response
- network byte order、big-endian
- 固定長 header
- clipboard body は raw UTF-8
- pairing metadata は小さな固定 binary structure
- JSON/Base64 を clipboard payload に使わない
- protocol version と application version を分離
- length を検証してから allocation
- unknown version、flag、operation は fail closed
- pair credential と attachment nonce を同じ header field で運ぶが、operation ごとに意味を固定する

### 13.2 request header

32 bytes:

| Offset | Size | Field | 内容 |
|---:|---:|---|---|
| 0 | 4 | magic | ASCII `KCLP` |
| 4 | 1 | version | `1` |
| 5 | 1 | opcode | 下表 |
| 6 | 2 | flags | v1 は `0` |
| 8 | 16 | credential | PAIR は pair credential、それ以外は attachment nonce |
| 24 | 4 | payloadLength | unsigned 32-bit、big-endian |
| 28 | 4 | reserved | `0` |

opcode:

| 値 | 名前 | credential |
|---:|---|---|
| 1 | PAIR | pairing code から導出した pair credential |
| 2 | PAIR_CONFIRM | pending attachment nonce |
| 3 | COPY | active attachment nonce |
| 4 | PASTE | active attachment nonce |
| 5 | PING | active attachment nonce |

### 13.3 response header

16 bytes:

| Offset | Size | Field | 内容 |
|---:|---:|---|---|
| 0 | 4 | magic | ASCII `KCLR` |
| 4 | 1 | version | `1` |
| 5 | 1 | status | 下表 |
| 6 | 2 | flags | v1 は `0` |
| 8 | 4 | payloadLength | unsigned 32-bit、big-endian |
| 12 | 4 | errorCode | success は `0` |

status:

| 値 | 名前 |
|---:|---|
| 0 | OK |
| 1 | BAD_REQUEST |
| 2 | UNAUTHORIZED |
| 3 | CAPABILITY_DENIED |
| 4 | TOO_LARGE |
| 5 | BACKEND_UNAVAILABLE |
| 6 | BACKEND_FAILURE |
| 7 | VERSION_UNSUPPORTED |
| 8 | TIMEOUT |
| 9 | PAIRING_EXPIRED |
| 10 | PAIRING_CONSUMED |
| 11 | ATTACHMENT_NOT_ACTIVE |
| 12 | INTERNAL |

### 13.4 PAIR request

PAIR request body:

```text
bodyVersion          2 bytes  1
requestedCaps        2 bytes  bitset
remoteUid            8 bytes
ttyDevice             8 bytes
ttyInode              8 bytes
usernameLength        2 bytes
hostnameLength        2 bytes
ttyPathLength         2 bytes
reserved              2 bytes  0
username              N bytes UTF-8
hostname              N bytes UTF-8
ttyPath               N bytes UTF-8
```

全 body は 4 KiB 以下とする。

metadata は local user への表示と remote lease の scope 確認に使う。remote host 上の hostile process は値を偽装できるため、cryptographic identity とみなさない。security boundary は OpenSSH destination と remote UID 全体への trust である。

PAIR success response body:

```text
attachmentId          16 bytes
attachmentNonce       16 bytes
grantedCaps            2 bytes
reserved               2 bytes  0
maxCopyBytes           4 bytes
maxPasteBytes          4 bytes
```

agent はこの時点では attachment を `PENDING_CONFIRM` とする。

### 13.5 PAIR_CONFIRM

remote `kclip pair` は PAIR response を受け取った後、lease と TTY binding を atomic commit する。その後、別 connection で PAIR_CONFIRM を送る。

request:

- credential: PAIR response の nonce
- body: attachment ID 16 bytes

local agent は pending record と照合し、成功したら:

- local registry entry を atomic commit
- pair code を consume
- attachment state を ACTIVE に変更
- OK response を返す
- local `kclip attach` の readiness waiter を起こす

OK response は durable local state と detached supervisor が成立した後にだけ送る。

PAIR response を返した時点で code を `ISSUED` とし、同じ code による2件目の PAIR は拒否する。PAIR_CONFIRM は attachment ID/nonce が一致する限り idempotent とし、ACTIVE 後の duplicate confirm にも OK を返す。

remote `kclip pair` は PAIR_CONFIRM response が timeout/切断で不明になった場合:

1. 同じ PAIR_CONFIRM を bounded retry
2. それでも不明なら同じ nonce で PING
3. PING が ACTIVE を返せば pairing success と確定
4. 明示 reject または endpoint unreachable なら remote lease/binding を rollback
5. user に新しい `kclip pair` を案内

local pending record が confirm deadline までに確定しなければ破棄する。issued code は再利用不可とする。曖昧な状態で同じ code から別 nonce を発行しない。

### 13.6 COPY / PASTE / PING

| operation | request body | success response body |
|---|---|---|
| COPY | clipboard UTF-8 bytes | empty |
| PASTE | empty | clipboard UTF-8 bytes |
| PING | empty | UTF-8 key/value lines |

PING response 例:

```text
application=kclip
version=1.0.0
protocol=1
attachment=7A2D5F1E6B684F0DA41C5D8D8E4A9110
state=active
capabilities=copy,paste
transport=dedicated
```

PING payload は最大 1 KiB。未知 key は無視できる。

error response body は利用者向けの短い UTF-8 message で最大 4 KiB。stack trace、credential、nonce、clipboard 本文を含めない。

### 13.7 protocol types

```kotlin
enum class AgentOperation(val wireValue: UByte) {
    PAIR(1u),
    PAIR_CONFIRM(2u),
    COPY(3u),
    PASTE(4u),
    PING(5u),
}

data class AgentRequest(
    val version: UByte,
    val operation: AgentOperation,
    val credential: Secret16,
    val payload: ByteArray,
)

data class AgentResponse(
    val version: UByte,
    val status: AgentStatus,
    val errorCode: UInt,
    val payload: ByteArray,
)

interface AgentProtocolCodec {
    fun readRequest(
        channel: ByteChannel,
        limits: ProtocolLimits,
        deadline: Deadline,
    ): Outcome<AgentRequest>

    fun writeRequest(
        channel: ByteChannel,
        request: AgentRequest,
        deadline: Deadline,
    ): Outcome<Unit>

    fun readResponse(
        channel: ByteChannel,
        limits: ProtocolLimits,
        deadline: Deadline,
    ): Outcome<AgentResponse>

    fun writeResponse(
        channel: ByteChannel,
        response: AgentResponse,
        deadline: Deadline,
    ): Outcome<Unit>
}
```

### 13.8 parser の必須防御

- magic 不一致で即時切断
- version 不一致は `VERSION_UNSUPPORTED`
- flags/reserved が non-zero なら `BAD_REQUEST`
- payload length が operation 上限を超えたら body を確保せず `TOO_LARGE`
- PAIR body は4 KiB以下
- PAIR_CONFIRM body は正確に16 bytes
- PASTE/PING request body が non-empty なら `BAD_REQUEST`
- COPY body は operation limit 以下かつ valid UTF-8
- EOF、short read、timeout を区別
- request timeout を connection 全体に適用し、各 read で更新しない
- credential comparison は constant time
- credential 比較より前に高コストな clipboard command を実行しない
- unknown credential に対して attachment の存在有無を詳細に返さない
- 同一 connection から2件目を読まない
- response header を送れない場合は静かに connection close
- protocol object の `toString()` は credential/body を表示しない

---

## 14. IPC abstraction と socket path

### 14.1 interface

```kotlin
interface KclipCloseable {
    fun close()
}

interface ByteChannel : KclipCloseable {
    fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
        deadline: Deadline,
    ): Outcome<Int>

    fun writeAll(
        source: ByteArray,
        deadline: Deadline,
    ): Outcome<Unit>
}

interface IpcListener : KclipCloseable {
    val endpoint: IpcEndpoint
    val pollHandle: PollHandle

    fun accept(): Outcome<ByteChannel?>
}

sealed interface IpcEndpoint {
    data class UnixSocket(val path: String) : IpcEndpoint

    // Windows 対応時の候補。v1 Unix build では生成しない。
    data class LoopbackTcp(
        val host: String,
        val port: UShort,
    ) : IpcEndpoint

    data class WindowsNamedPipe(
        val name: String,
    ) : IpcEndpoint
}

interface LocalIpc {
    fun bindUnix(
        path: String,
        socketMode: UInt,
    ): Outcome<IpcListener>

    fun connect(
        endpoint: IpcEndpoint,
        deadline: Deadline,
    ): Outcome<ByteChannel>
}
```

`ByteChannel` は socket 以外の transport にも使える。Windows では named pipe または loopback TCP adapter を追加できる。

### 14.2 local agent socket

```text
/tmp/kclip-<uid>/a-<attachment-id-prefix>.sock
```

または安全な `$XDG_RUNTIME_DIR/kclip` が利用できる場合:

```text
$XDG_RUNTIME_DIR/kclip/a-<attachment-id-prefix>.sock
```

要件:

- parent directory mode `0700`
- directory owner が current UID
- socket mode `0600`
- path lengthを `sockaddr_un.sun_path` の platform limit 未満に検証
- symlink を辿らない
- bind 前に既存 path の owner/type/activity を確認
- active socket を unlink しない
- process 終了時に socket file を unlink
- path に nonce、pairing code、pair credential を含めない

local OpenSSH process はこの socket path へ接続する。

### 14.3 remote pairing/attachment socket

remote socket path は pairing code から導出した socket ID を使う。

```text
/tmp/kclip-r-<24 lowercase hex>.sock
```

例:

```text
/tmp/kclip-r-7c32b10af81e9063df41a297.sock
```

特徴:

- path は local と remote が pairing code だけから同じ値を計算できる
- path から pair credential を導出できない
- random 80-bit code により別 pairing との衝突を現実的に避ける
- shell-safe character のみ
- OpenSSH `StreamLocalBindMask=0177` により owner-only permission を要求
- dedicated transport では `StreamLocalBindUnlink=yes` を指定
- ControlMaster transport では既存 master の設定と実装挙動に依存するため、初回は必ず新しい random path を使う

remote socket は sshd が listener として作り、接続を local agent socket へ reverse forward する。

remote `kclip pair` と `AttachmentClient` は接続前に endpoint guard を実行する。

1. `lstat` で symlink ではなく Unix socket であることを確認
2. owner が current UID であることを確認
3. group/other permission があれば owner socket に限り `chmod(0600)`
4. 再度 `lstat` して type/owner/mode を確認
5. path identity が検査中に変わっていないことを確認
6. 条件を満たさなければ接続しない

これにより、既存 ControlMaster の `StreamLocalBindMask` が緩い場合も active endpoint を owner-only に収束させる。protocol credential/nonce の検証は引き続き必須である。

### 14.4 socket path の寿命

pairing と active attachment は同じ remote socket path を使う。

- PAIR はその path を通って local agent へ到達
- remote lease はその endpoint を保存
- COPY/PASTE/PING も同じ endpoint を利用
- reconnect は可能な限り同じ path で forward を再作成

正常な forwarding 終了時、OpenSSH は listener を閉じる。異常終了後に stale path が残る場合がある。

reconnect 時の順序:

1. 既存 forward を `cancel` / `exit` できるなら停止
2. same path で再 forward
3. mux `-O forward` では `StreamLocalBindUnlink=yes` だけで stale socket を置換できると仮定しない
4. stale socket collision が継続する場合は automatic reconnect を停止
5. user に `kclip pair --replace` を案内し、新しい path/attachment を作る

remote の arbitrary file を local から削除する仕組みは持たない。

### 14.5 stream-local forwarding requirement

v1 attach は OpenSSH の remote Unix-domain socket forwarding を要求する。

概念上の指定:

```text
-R <remote-unix-socket>:<local-unix-socket>
```

server 側で stream-local forwarding または全 forwarding が禁止されている場合は失敗する。

TCP remote forwarding への自動 fallback は行わない。理由:

- remote loopback port の割当・伝達が増える
- bind address/port collision の扱いが複雑
- 誤設定時に network-visible listener を作るリスク
- Unix file permission による defense-in-depth を失う
- v1 の macOS/Linux scope では Unix socket が利用できる

### 14.6 pairing waiter

remote `kclip pair` は socket server ではなく client である。sshd が remote listener を作るまで、以下の bounded polling を行う。

```kotlin
interface PairingEndpointWaiter {
    fun awaitAndConnect(
        endpoint: IpcEndpoint.UnixSocket,
        expiresAt: Deadline,
        retryPolicy: RetryPolicy,
    ): Outcome<ByteChannel>
}
```

- initial interval: 100 ms
- max interval: 1 s
- bounded jitter
- expiry までの total deadline
- `ENOENT` / `ECONNREFUSED` は retryable
- permission error、path type mismatch は fatal
- signal interruption では user cancel を優先

pairing process は path の出現だけで成功とせず、PAIR/PAIR_CONFIRM protocol が完了して初めて success とする。

---

## 15. local attachment supervisor / agent

### 15.1 責務

attachment ごとの supervisor は次を所有する。

- local agent Unix socket listener
- expected pair credential
- attachment ID と nonce
- granted capabilities
- local clipboard backend
- reverse forwarding transport handle
- attach readiness state
- reconnect state
- local control socket
- redacted metadata

```kotlin
data class AgentPolicy(
    val allowCopy: Boolean = true,
    val allowPaste: Boolean = false,
    val maxCopyBytes: Int = 1 * 1024 * 1024,
    val maxPasteBytes: Int = 1 * 1024 * 1024,
    val pairingTimeout: Duration = 10.minutes,
    val requestTimeout: Duration = 5.seconds,
    val clipboardCommandTimeout: Duration = 5.seconds,
)

sealed interface AgentLifecycleState {
    data object Pairing : AgentLifecycleState
    data object PendingConfirm : AgentLifecycleState
    data object Active : AgentLifecycleState
    data class Degraded(val reason: String) : AgentLifecycleState
    data object Stopping : AgentLifecycleState
    data object Closed : AgentLifecycleState
}

class AttachmentAgent(
    private val listener: IpcListener,
    private val controlListener: IpcListener,
    private val codec: AgentProtocolCodec,
    private val expectedPairCredential: PairCredential,
    private val clipboard: ClipboardBackend,
    private val policy: AgentPolicy,
    private val transport: ReverseForwardHandle,
    private val signalMailbox: SignalMailbox,
    private val clock: MonotonicClock,
    private val logger: Logger,
) {
    fun run(): Outcome<Unit>
}
```

### 15.2 agent process の起動

`kclip attach` は、secret を argv/environment に置かず、inherited pipe を使って `_attach-agent` へ渡す。

```text
parent kclip attach
  ├─ private config pipe
  ├─ bootstrap control socketpair
  └─ posix_spawn(kclip _attach-agent --config-fd=N --bootstrap-fd=M)
```

config pipe に含めるもの:

- pair credential
- remote socket ID
- destination と SSH options
- copy/paste policy
- transport preference
- timeouts

`_attach-agent` は:

1. config を読み、pipe を閉じる
2. local runtime directory と listener を作る
3. forwarding を確立し、dedicated SSH の対話認証が終わるまで待つ
4. `setsid()` で caller の terminal/session から離れ、stdio を `/dev/null` または dedicated log sink に切り替える
5. PAIR request を受け、pending attachment を作る
6. PAIR_CONFIRM 受信時に local registry を commit し、attachment を ACTIVE にする
7. PAIR_CONFIRM の OK response を返す
8. bootstrap control socket へ success summary を返す
9. attachment supervisor として動作を継続する

agent child は親の foreground process group を継承して spawn し、process-group leader にはしない。これにより SSH authentication 完了後の `setsid()` を可能にする。`setsid()` またはstdio切替に失敗した場合は PAIR を受け付けず、attach failure として cleanup する。

PAIR_CONFIRM の OK は、local registry commit と durable supervisor化の後にだけ送る。これにより remote/local のどちらか一方だけが success を表示する状態を避ける。

bootstrap control socket は双方向とする。

- parent は success/failure summary を待つ
- `Ctrl-C`、parent error、timeout では CANCEL を送るか socket を close
- agent は PAIRING 中も bootstrap FD を `poll()` し、parent 消失時に forwarding と listener を cleanup
- ACTIVE transition 後に bootstrap socket を close

Kotlin/Native runtime 初期化後の `fork()` は使わない。process 作成は `posix_spawnp()` と re-exec を使う。

### 15.3 dedicated SSH の background 化

ControlMaster がない場合、agent は private control socket を持つ OpenSSH master を起動する。

dedicated transport は2段階で作る。まず、config file に書かれた既存 forwarding を持ち込まない private master を開始する。

```sh
ssh \
  -fNT \
  -M \
  -S /tmp/kclip-UID/ssh/ATTACHMENT.control.sock \
  -o ControlPersist=no \
  -o ClearAllForwardings=yes \
  -o PermitLocalCommand=no \
  -o StreamLocalBindMask=0177 \
  -o StreamLocalBindUnlink=yes \
  -o ForwardAgent=no \
  -o ForwardX11=no \
  -o RequestTTY=no \
  -o Tunnel=no \
  USER_OPTIONS... \
  DESTINATION
```

次に、その private master へ kclip の forwarding だけを動的に追加する。

```sh
ssh \
  -F /dev/null \
  -S /tmp/kclip-UID/ssh/ATTACHMENT.control.sock \
  -o ClearAllForwardings=no \
  -o PermitLocalCommand=no \
  -O forward \
  -R REMOTE_SOCKET:LOCAL_AGENT_SOCKET \
  RESOLVED_HOSTNAME
```

この2段階にする理由は、`ClearAllForwardings=yes` が config file だけでなく command-line の forwarding も clear するためである。同じ command へ `-R` を混在させてはならない。2段目の mux control operation は `-F /dev/null` で config forwarding と local command を隔離する。

Phase 0.5 の macOS-to-macOS spike では、private master 起動時と mux `-O forward` の両方に `StreamLocalBindUnlink=yes` を指定しても、既存 stale socket は置換されず `remote port forwarding failed for listen path ...` で失敗した。したがって production transport は mux `-O forward` による stale socket 自動修復を前提にせず、明示的な stale collision として扱う。

- `-f` により authentication が完了した後に OpenSSH master 自身が background になる。
- password/passphrase prompt は `kclip attach` が foreground の間に current TTY へ表示される。
- `-O forward` の exit status と PAIR protocol の双方を success 条件にする。
- private ControlPath により `check`、`cancel`、`exit` が可能になる。
- user options 内の `ControlMaster`、`ControlPath`、`ControlPersist` は dedicated mode では拒否または kclip の private 値で固定する。
- `ForwardAgent`、`ForwardX11`、`RequestTTY`、`Tunnel`、`PermitLocalCommand` は kclip の安全側の値を command-line 先頭で固定し、競合する explicit option は validator で拒否する。
- user の `-L`、`-R`、`-D` は dedicated sidecar へ持ち込まない。
- kclip forwarding 以外の remote command、PTY、forwarding を作らない。

OpenSSH 実装・version 差で `-fNT -M` の挙動が integration test を通らない platform では、foreground bootstrap process と private master process を分離する。ただし shell を介して background 化しない。

### 15.4 ControlMaster attach

既存 master を使う場合、agent 自身は SSH child を保持しない。

概念形:

```sh
ssh \
  -F /dev/null \
  -S CONTROL_PATH \
  -o ClearAllForwardings=no \
  -o PermitLocalCommand=no \
  -O forward \
  -R REMOTE_SOCKET:LOCAL_AGENT_SOCKET \
  RESOLVED_HOSTNAME
```

stop:

```sh
ssh \
  -F /dev/null \
  -S CONTROL_PATH \
  -o ClearAllForwardings=no \
  -o PermitLocalCommand=no \
  -O cancel \
  -R REMOTE_SOCKET:LOCAL_AGENT_SOCKET \
  RESOLVED_HOSTNAME
```

control operation では user/system SSH config を再読込しない。`LocalForward` / `RemoteForward` / `LocalCommand` 等を意図せず再実行しないためである。master discovery で解決済みの exact `ControlPath` と、構文上必要な resolved hostname だけを使う。

agent は master を所有しないため、`-O exit` を送ってはならない。追加した forward だけを exact specification で cancel する。

### 15.5 event loop

single-threaded `poll()` loop とする。

```text
while state != CLOSED:
    poll(
        agent listener,
        local control listener,
        bootstrap control fd while pairing,
        signal self-pipe,
        reconnect timer,
        transport health timer
    )

    if signal:
        begin graceful stop

    if agent listener readable:
        accept one connection
        handle exactly one protocol request with deadline
        close connection

    if control listener readable:
        handle status/reconnect/detach request

    if transport health timer:
        inspect ControlMaster/private master
        transition ACTIVE <-> DEGRADED
        try bounded noninteractive reconnect

    if pairing deadline expired:
        report failure and stop
```

clipboard command が数秒 block しても remote interactive shell 自体は別 process なので動き続ける。2件目の clipboard request は待つ。v1 はこの単純性を優先し、connection ごとの thread/coroutine は作らない。

同時接続数は小さな backlog とし、一度に処理する request は1件。slow client は connection-wide deadline で切断する。

### 15.6 local control protocol

`kclip attachments`、`reconnect`、`detach` は local private control socket を使う。

operation:

- STATUS
- GET_RECONNECT_PLAN
- TRANSPORT_READY
- DETACH
- SHUTDOWN

control socket:

- local runtime directory 配下
- mode `0600`
- remote forwarding の対象にしない
- nonce を要求せず、local UID file permission を authority とする
- clipboard body を運ばない
- reconnect plan は current local UID にだけ返す

`kclip reconnect` は live agent から、memory 上の destination/options、remote/local endpoint、transport preference を含む reconnect plan を取得する。caller process 自身が current TTY を使って OpenSSH を起動し、成功後に `TRANSPORT_READY` を通知する。password/passphrase、pairing code、nonce を control protocol に流さない。

SSH spec は agent memory に保持し、local metadata file へ永続化しない。agent が失われた場合は interactive reconnect もできず、re-pair を要求する。

### 15.7 transport health と再接続

agent は transport kind ごとに health を判定する。

ControlMaster:

```text
ssh -F /dev/null -S CONTROL_PATH -O check RESOLVED_HOSTNAME
```

dedicated:

```text
ssh -F /dev/null -S PRIVATE_CONTROL_PATH -O check RESOLVED_HOSTNAME
```

loss 検出後:

1. state を DEGRADED にする
2. current request には attachment unavailable を返す
3. exponential backoff + jitter で noninteractive reconnect を試す
4. `BatchMode=yes` で認証できる場合は same endpoint を再作成
5. user interaction が必要なら `NeedsUserAuthentication`
6. local `kclip reconnect ID` が interactive retry を行う
7. agent process/nonce は維持するため、remote の再 pairing は不要

自動 reconnect の既定:

```text
1s, 2s, 4s, 8s, 15s, 30s, then every 60s
```

最大試行回数では停止せず、60秒間隔で health を維持する。ただし network/process resource を消費する SSH attempt は、連続失敗後に user-triggered only へ移行してよい。最終値は実装時の test で固定する。

same remote socket を再 bind できない場合:

```text
attachment KC-A7D2 could not reclaim its remote socket
hint: run `kclip pair --replace --paste` in the remote shell
```

### 15.8 detach

`kclip detach ID`:

1. new clipboard request を拒否
2. ControlMaster transport なら `-O cancel -R ...`
3. dedicated transport なら private master に `-O exit`
4. local listener/control socket を close
5. secret buffer を best-effort zeroize
6. local runtime metadata/socket を削除
7. agent process を終了

remote lease/binding は tunnel を介して能動削除しない。detach 後の remote request は connection failure となり、remote `doctor` が stale と表示する。

将来 protocol に remote cleanup を追加できるが、v1 では cleanup のためだけに remote command/session channel を増やさない。

### 15.9 signal

- `SIGTERM`、`SIGHUP`、`SIGQUIT`、`SIGCHLD` は self-pipe へ通知
- signal handler 内では async-signal-safe な `write()` だけを行う
- Kotlin allocation、logging、file cleanup を signal handler で行わない
- parent attach command が cancel された場合、PAIRING 中の agent を停止
- ACTIVE agent は caller shell の終了だけでは停止しない
- local logout/shutdown で signal を受けた場合は best-effort detach

---

## 16. 既存 SSH セッションへの attach

### 16.1 変更不能な制約

すでに開始済みの通常 SSH session に対して、remote shell 内の process が自力で local 側へ reverse forwarding を追加する一般的な経路はない。

remote shell から見えるものは、既存 connection 上の terminal/session channel である。新しい `-R` forwarding は OpenSSH client 側の control operation であり、local 側で次のどちらかを行う必要がある。

1. 既存 ControlMaster に `ssh -O forward` を送る
2. 新しい SSH connection を local から張る

したがって `kclip pair` だけでは paste を有効化できない。`kclip pair` は pairing request と socket coordinate を作り、local の `kclip attach` を待つ。

この制約は failure message と documentation で隠さず説明する。

```text
kclip: a local-side attachment is required for paste
An already-open remote shell cannot add reverse forwarding by itself.
hint: run `kclip pair --paste` here, then `kclip attach` locally
```

### 16.2 recommended attach path

```text
remote existing shell                 local machine
────────────────────                  ─────────────
kclip pair --paste
  generate code
  wait on derived socket
                         code
                         ────────▶    kclip attach --paste=allow DEST
                                      start local agent
                                      try ControlMaster
                                      else open dedicated SSH
                         reverse
                         forwarding
                         ◀────────
  PAIR request ─────────────────────▶ local agent
  write lease/binding
  PAIR_CONFIRM ─────────────────────▶ local agent
  return success                      return success
```

この方式を v1 の primary UX とする理由:

- original SSH session の開始方法を変えなくてよい
- remote→local network reachability を要求しない
- exact remote TTY を pairing process 自身が知っている
- dedicated fallback は remote command を必要とせず forwarding-only connection にできる
- ControlMaster があれば追加 authentication を避けられる
- paste capability を local attach 時に明示できる
- tmux/screen の terminal sequence forwarding に依存しない

### 16.3 ControlMaster path

#### discovery

`kclip attach --transport=auto` はまず effective SSH config を解決する。

概念上:

```sh
ssh -G [USER_OPTIONS...] DESTINATION
```

確認する項目:

- `controlmaster`
- `controlpath`
- `controlpersist`
- hostname
- user
- port
- proxyjump/proxycommand の存在

次に:

```sh
ssh \
  -F /dev/null \
  -S RESOLVED_CONTROL_PATH \
  -O check \
  RESOLVED_HOSTNAME
```

active master が確認できた場合のみ ControlMaster path を使う。

`ssh -G` は、元の interactive command で command-line override された値を自動で復元できない。元の接続が custom `-S`、`-F`、`-p`、`-J` 等を使った場合、attach 側でも同じ値を渡す必要がある。

明示 override:

```sh
kclip attach \
  --control-path ~/.ssh/cm/dev.sock \
  --paste=allow \
  dev@example.com
```

#### forwarding add

control operation は、discovery に使った user SSH config から切り離す。`-O forward` が config 内の別 `LocalForward` / `RemoteForward` を同時に追加したり、`LocalCommand` を実行したりしないようにするためである。

概念上:

```sh
ssh \
  -F /dev/null \
  -S CONTROL_PATH \
  -o ClearAllForwardings=no \
  -o PermitLocalCommand=no \
  -O forward \
  -R /tmp/kclip-r-SOCKET_ID.sock:/tmp/kclip-UID/a-ID.sock \
  RESOLVED_HOSTNAME
```

`-F /dev/null` により user/system config の forwarding を持ち込まず、`ClearAllForwardings=no` により command-line の kclip forwarding は保持する。`RESOLVED_HOSTNAME` は構文上の destination であり、新しい network connection は作らない。

`-O forward` command が success を返しても、pair protocol が完了するまでは attach success とみなさない。

#### forwarding cancel

```sh
ssh \
  -F /dev/null \
  -S CONTROL_PATH \
  -o ClearAllForwardings=no \
  -o PermitLocalCommand=no \
  -O cancel \
  -R /tmp/kclip-r-SOCKET_ID.sock:/tmp/kclip-UID/a-ID.sock \
  RESOLVED_HOSTNAME
```

cancel failure は warning として記録し、local agent は必ず停止する。master 終了時には forward も失われる。

#### ControlMaster path の制約

- current interactive shell 自体がその master 経由で開始されている必要はない。同じ remote account と filesystem に到達する active master であれば pair protocol が最終確認になる
- target destination/control path が一致する必要がある
- active master が存在しない既存 connection を、後から multiplexing master に変換することはできない
- active master を所有する別 local user には attach できない
- server 側 forwarding policy は依然として適用される
- original master の `StreamLocalBindMask` / `StreamLocalBindUnlink` 等を mux client 側から変更できるとは仮定しない。random path、remote owner/type/mode check、protocol credential を併用する
- master が終了すると attachment は DEGRADED になる
- agent は同じ master の復帰を検出するか、dedicated transport へ移行する
- load balancer、ephemeral host、container namespace 等により master の remote filesystem と pair を実行した shell の filesystem が異なる場合、socket は見えず pairing は成立しない

失敗例:

```text
kclip attach: no active ControlMaster matches this destination
checked control path:
  /Users/daichi/.ssh/cm/dev@example.com:22
hint:
  use the same SSH alias/options as the existing connection,
  pass `--control-path`, or allow the automatic dedicated fallback
```

`--transport=controlmaster` の場合は dedicated fallback を行わず失敗する。

#### `auto` の fallback 規則

`--transport=auto` は、すべての mux failure を同じものとして扱わない。

- active master が見つからない: dedicated へ進む
- `-O check` と `-O forward` の間に master が消えた、control socket が stale、または local mux operation が到達不能: dedicated へ進む
- `-O forward` が remote forwarding の明示的な reject を返した: 自動で再認証せず失敗する。server policy は dedicated connection でも同じ可能性が高いためである
- user が明示 reject 後も別 connection を試したい場合: `--transport=dedicated` を指定する
- `-O forward` が成功した後に PAIR が失敗した: duplicate forward を作らず、追加した forward を cancel して pairing error を返す
- `-O forward` 成功直後に master 消失が確認でき、PAIR がまだ到達していない: 同じ agent/socket を維持したまま dedicated transport を一度だけ試せる

fallback の判定には OpenSSH の exit status と sanitized stderr classification を使う。locale 依存の文字列だけに依存せず、`-O check` の再確認と socket/process state を組み合わせる。分類不能な failure は fail closed とし、`--verbose` で dedicated を明示指定する案内を出す。

### 16.4 dedicated SSH sidecar path

ControlMaster がない場合、local から同じ destination へ forwarding-only connection を追加する。

概念上、まず kclip 専用 private master を作る。

```sh
ssh \
  -fNT \
  -M \
  -S /tmp/kclip-UID/ssh/ATTACHMENT.control.sock \
  -o ControlPersist=no \
  -o ClearAllForwardings=yes \
  -o PermitLocalCommand=no \
  -o StreamLocalBindMask=0177 \
  -o StreamLocalBindUnlink=yes \
  -o ForwardAgent=no \
  -o ForwardX11=no \
  -o RequestTTY=no \
  -o Tunnel=no \
  [USER_OPTIONS...] \
  DESTINATION
```

その後、private master に kclip forward だけを追加する。

```sh
ssh \
  -F /dev/null \
  -S /tmp/kclip-UID/ssh/ATTACHMENT.control.sock \
  -o ClearAllForwardings=no \
  -o PermitLocalCommand=no \
  -O forward \
  -R /tmp/kclip-r-SOCKET_ID.sock:/tmp/kclip-UID/a-ID.sock \
  RESOLVED_HOSTNAME
```

`ClearAllForwardings=yes` と `-R` を同じ invocation に置かない。前者は command-line の forwarding も clear するためである。2段目は `-F /dev/null` により private master の制御以外の user/system config を持ち込まない。

stale remote socket は mux `-O forward` の失敗として分類し、自動 unlink には依存しない。Phase 0.5 spike では `StreamLocalBindUnlink=yes` を指定しても stale socket 置換は成功しなかった。

特徴:

- original interactive session を変更しない
- local から開始するため NAT/firewall 越しの remote→local direct connection は不要
- original connection と別の認証が発生し得る
- `ssh-agent`、key、password、MFA は通常の OpenSSH UX のまま
- PTY と remote command を作らない
- server が session channel を制限していても forwarding が許可されていれば利用可能な構成がある
- private ControlPath で health check と shutdown を行える

attach output:

```text
No usable ControlMaster was found.
Opening a dedicated SSH forwarding connection.
This may request SSH authentication again.
```

専用 connection が切断された場合、local agent は同じ destination/options で reconnect する。interactive authentication が必要なら:

```text
Attachment KC-A7D2 needs SSH authentication to reconnect.
Run:
  kclip reconnect KC-A7D2
```

### 16.5 forwarding policy failure

server が stream-local forwarding を拒否した場合、ControlMaster と dedicated のどちらでも agent attach はできない。

代表的な原因:

- `AllowStreamLocalForwarding no` または `local` only
- `DisableForwarding yes`
- authorized_keys の `restrict` / `no-port-forwarding`
- Match block
- server/client が Unix-domain socket forwarding をサポートしない
- filesystem permission/path length
- stale socket collision

表示:

```text
kclip attach: remote Unix-socket forwarding was rejected

The SSH server may disable stream-local or all forwarding.
An already-open SSH shell cannot create a reverse path to the local
clipboard without an allowed forwarding channel.

copy may still work through OSC 52.
paste is unavailable until forwarding is allowed.
```

`--verbose` 時だけ OpenSSH stderr の sanitized excerpt を追加する。server config を断定せず「may」と表現する。

### 16.6 manual `~C` escape commandline

OpenSSH interactive client は、構成によっては escape commandline `~C` から runtime forwarding を追加できる。

しかし v1 の primary/automatic path にはしない。

理由:

- local terminal で手動操作が必要
- modern OpenSSH では escape commandline が設定で無効な場合がある
- kclip が既存 SSH client process へ安全に control sequence を注入できない
- local agent socket path と remote socket path を user が扱う必要がある
- script/doctor から success を確実に判定できない
- tmux/local terminal nesting で escape key の到達先が曖昧

将来 expert command として、forward spec を表示する機能は検討できる。

```text
kclip attach --print-manual-forward ...
```

v1 では実装しない。

### 16.7 local SSH server / reverse SSH を使わない

次の方式は非採用とする。

```text
remote kclip -> ssh back to local -> pbcopy/pbpaste
```

理由:

- local 側で Remote Login/sshd を有効化する必要がある
- local host が NAT/firewall 越しに到達可能とは限らない
- credential と host key 管理が逆方向にも必要
- local attack surface を増やす
- corporate network で禁止されやすい
- kclip の setup-free という価値を損なう

### 16.8 多段 SSH

#### ProxyJump を local から再現できる場合

元の path:

```sh
ssh -J bastion target
```

attach:

```sh
kclip attach --paste=allow target -- -J bastion
```

または `~/.ssh/config` alias:

```sshconfig
Host target-via-bastion
    HostName target.internal
    User dev
    ProxyJump bastion
```

```sh
kclip attach --paste=allow target-via-bastion
```

これは通常の OpenSSH forwarding として動作する。

#### remote からさらに手動 `ssh` した場合

```text
local -> jump shell -> target shell
```

local は inner `ssh target` の command、credential、alias を知ることができない。jump への既存 ControlMaster だけでは target の remote socket を作れない。

v1 の選択肢:

1. local から target へ `-J jump` で attach
2. target 用 SSH alias を作る
3. target への forwarding が不可能なら copy は OSC 52 best effort、paste は不可

この制約を magic で隠さない。

### 16.9 tmux / screen

推奨 flow は multiplexer 内で直接 pair する。

```text
remote tmux pane$ kclip pair --paste
```

binding はその pane/window の PTY identity に紐づくため、outer SSH session の開始方法とは独立する。

- pane を split した場合、新 pane は別 TTY なので必要に応じて別 pair
- pane process tree 内では同じ binding
- tmux session を detach/reattachしても PTY が同じなら維持
- 同一 pane を複数 client が同時表示する場合、last paired local clipboard を使用
- `screen` も同じ原則

OSC 52 passthrough 設定は agent path には不要である。

### 16.10 reconnect

#### transport だけが切れた場合

local supervisor が生きていれば attachment ID、nonce、remote endpoint は維持される。

- automatic noninteractive reconnect
- 必要なら local `kclip reconnect ID`
- remote の再 pair は不要
- current TTY binding も維持

remote operation 中:

```text
kclip: attachment KC-A7D2 is temporarily disconnected
local action required:
  kclip reconnect KC-A7D2
```

#### local supervisor が失われた場合

nonce の local copy が失われるため、既存 remote lease を安全に再利用しない。

```text
remote$ kclip pair --replace --paste
local$  kclip attach --paste=allow dev@example.com
```

#### remote shell/TTY が変わった場合

新しい TTY は既定では attachment を継承しない。新しい pane/session で pair する。

必要に応じて advanced command で既存 attachment ID を bind する機能は将来検討するが、v1 の通常 flow は re-pair とする。

### 16.11 pairing timeout と cancel

remote:

- `Ctrl-C` で pairing request を cancel
- request file を cleanup
- code を zeroize
- remote socket path は forwarding がまだ存在すれば local supervisor が detach 時に停止

local:

- SSH authentication 中の `Ctrl-C` で agent/forward setup を cleanup
- remote pair は expiry まで待つため、即時に別 code を生成してよい
- local attach が pair code expiry を受けた場合、agent と forward を停止

message:

```text
kclip attach: pairing code expired or no waiting `kclip pair` process accepted it
hint: run `kclip pair` again in the intended remote terminal
```

### 16.12 `kclip attach` の option policy

user SSH options は allowlist parser を通す。

許可候補:

- `-4`, `-6`
- `-A`, `-a`
- `-C`
- `-F`
- `-i`
- `-J`
- `-l`
- `-p`
- `-S`
- `-v`
- `-o` の host/auth/proxy/keepalive 系

競合するため拒否または kclip が上書きするもの:

- remote command
- `-N`, `-T`, `-t`, `-W`, `-s`
- user の `-L`、`-R`、`-D`。attach transport へ任意 forwarding を混在させない
- `ExitOnForwardFailure`
- `StreamLocalBindMask`
- `StreamLocalBindUnlink`
- dedicated mode の `ControlMaster` / `ControlPath` / `ControlPersist`
- host key checking を弱める option は pass-through してもよいが、kclip 自身は生成・推奨しない

unknown option は安全に token 数を判断できないため usage error とする。allowlist は test と release note で拡張する。

---

## 17. attachment client backend と `kclip ssh`

### 17.1 attachment client backend

```kotlin
class AttachmentClipboardBackend(
    private val lease: AttachmentLease,
    private val ipc: LocalIpc,
    private val codec: AgentProtocolCodec,
    private val clock: MonotonicClock,
    private val timeout: Duration = 5.seconds,
) : ClipboardBackend {
    override val id: String = "attachment:${lease.id.value}"
    override val capabilities: Set<ClipboardCapability> =
        lease.capabilities

    override fun copy(payload: ClipboardPayload): Outcome<Unit>
    override fun paste(maxBytes: Int): Outcome<ClipboardPayload>
}
```

copy:

1. lease に COPY capability があるか preflight
2. endpoint に deadline 付きで接続
3. nonce を credential とした COPY request を送信
4. response を読む
5. agent status を `KclipError` に変換
6. connection close

paste:

1. lease に PASTE capability があるか preflight
2. endpoint に deadline 付きで接続
3. body なし PASTE request
4. response header の length を上限検証
5. body を読み、UTF-8 検証
6. stdout へ返す

agent に到達できない場合、socket path を既定出力に出さない。

```text
kclip: attachment KC-A7D2 is not reachable
hint: run `kclip doctor` remotely and `kclip attachments` locally
```

`--verbose` 時だけ sanitized OS error と endpoint basename を表示する。nonce は常に非表示。

### 17.2 current attachment resolution

```kotlin
interface CurrentAttachmentResolver {
    fun resolve(
        explicitId: AttachmentId?,
        tty: TtyIdentity?,
        environment: Map<String, String>,
    ): Outcome<AttachmentLease?>
}
```

優先順位:

1. `--attachment`
2. `KCLIP_ATTACHMENT`
3. current controlling TTY binding
4. none

`KCLIP_ATTACHMENT` は ID のみで、secret を含まない。managed `kclip ssh` や script で explicit routing に使える。

lease 取得後、operation 前に軽量 PING を毎回行わない。COPY/PASTE request 自体の response が health check になる。`doctor` のみ PING を使う。

### 17.3 `kclip ssh` の位置付け

`kclip ssh` は v1 に残すが、設計上は **新しい session の attach 自動化**である。

- primary requirement ではない
- 既存通常 SSH session の attach と同じ protocol/capability/backend を使う
- `kclip ssh` 専用の paste semantics を作らない
- `kclip ssh` でしか使えない機能を作らない

user-facing:

```sh
kclip ssh --paste=allow dev@example.com
```

概念フロー:

1. local agent と nonce を生成
2. remote socket と reverse forwarding を SSH initial command に含める
3. remote command として `kclip _managed-session` を実行
4. `_managed-session` が current PTY を取得
5. 同じ PAIR/PAIR_CONFIRM 相当の bootstrap を自動実行
6. TTY binding を作る
7. login shell に `exec`
8. shell 終了時に local agent/forward を停止

user に pairing code の転記を求めない点だけが attach flow との違いである。

### 17.4 managed session bootstrap

```kotlin
data class ManagedSessionBootstrap(
    val endpoint: IpcEndpoint,
    val bootstrapCredential: PairCredential,
    val requestedCapabilities: Set<ClipboardCapability>,
)
```

bootstrap credential は local secure random から生成し、short-lived とする。

remote command に secret を含める必要があるため、次を守る。

- local shell を介さず OpenSSH argv に渡す
- POSIX shell word encoder で remote command を quote
- logging/diagnostics では redact
- remote process list から一時的に見える可能性を threat model に明記
- bootstrap 完了後に無効化
- long-lived nonce は別に生成

より強い secret transport を OpenSSH extension なしで得るのは難しいため、managed path の bootstrap token exposure は attach pairing code path より弱い。したがって security-sensitive な primary design は existing-session pair/attach とし、managed wrapper は convenience と位置付ける。

### 17.5 `kclip ssh` と process lifetime

- local `kclip ssh` process が agent と OpenSSH child を所有
- background supervisor は作らない
- SSH child 終了時に agent/forward/local socket を cleanup
- exit code は原則 OpenSSH child を返す
- `--paste=allow` はその managed attachment のみ有効
- shell 内の `kclip doctor` は通常 attachment と同じ表示をする

### 17.6 `kclip ssh` 失敗時

remote に kclip がない:

```text
kclip ssh: kclip is not available on the remote host
hint: install kclip remotely, or use:
  kclip ssh --remote-kclip=/absolute/path/to/kclip ...
```

forwarding rejected:

```text
kclip ssh: the SSH server rejected remote Unix-socket forwarding
The shell was not started because clipboard attachment setup failed.
```

`--without-clipboard-on-failure` のような silent degraded mode は v1 では提供しない。利用者が通常 SSH に fallback するか判断する。

---

## 18. セキュリティ設計

### 18.1 security goal

kclip の security goal は、local clipboard access を「どの remote host/account に、どの capability を、どの attachment の寿命だけ許可したか」と結び付けることである。

特に paste は local clipboard read であり、password、token、private URL、one-time code などを remote 側へ流出させ得る。したがって v1 は次を不変条件とする。

- paste は default deny
- paste は local 側の明示 `--paste=allow` なしに有効化しない
- OSC 52 query で permission model を迂回しない
- capability は request ごとに local agent が検証
- attachment が stale/unknown のとき fail closed
- secret と clipboard body を log に出さない

### 18.2 trust boundary

local user が attachment を許可するとき、実質的に信頼する単位は次である。

```text
SSH destination / verified host key
+ remote login account
+ その account と同等以上の権限を持つ remote process
+ attachment lifetime
+ granted capabilities
```

TTY/pane binding は「どの shell から自然に使うか」を決める routing mechanism であり、remote 同一 UID 内の isolation ではない。

remote 上で以下は attachment lease/nonce を読み、利用できる可能性がある。

- 同一 UID の process
- remote root
- ptrace/procfs/file permission を迂回できる privileged process

したがって local attach の paste warning は、「この shell だけ」ではなく remote UID/root を許可することを明記する。

```text
Allowing paste lets processes running as dev (uid 1000),
and remote root, read your local clipboard while this attachment is active.
```

remote username/UID/hostname は remote process が報告する metadata であり、local agent が cryptographically attest するものではない。OpenSSH の host key verification と user の destination selection が主要 identity である。

### 18.3 threat model

守る対象:

- local clipboard の内容
- clipboard payload の integrity/confidentiality in transit
- paste permission の意図
- attachment 間の accidental cross-talk
- pairing code と attachment nonce
- oversized/slow request による resource exhaustion
- command injection
- local/remote runtime directory の path replacement

想定する攻撃者:

- remote host 上の別 UID
- network observer
- malformed protocol client
- stale socket/path を悪用する local/remote process
- clipboard backend command の異常動作
- untrusted shell content が copy を実行する状況

防御しないもの:

- attachment を許可した remote UID の完全侵害
- remote root
- local 同一 UID または local root
- OpenSSH、terminal emulator、OS clipboard service 自体の侵害
- user が host key checking を自ら無効化した結果
- user が明示的に paste を許可した hostile host による clipboard read
- copy permission を許可した host による clipboard poisoning

### 18.4 paste permission

local policy:

```kotlin
enum class PastePolicy {
    DENY,
    ALLOW,
}
```

default は `DENY`。

- remote `kclip pair --paste` は request
- local `kclip attach --paste=allow` が grant
- request がなく local が allow を指定しても PASTE は grant しない
- request と grant の intersection が final capability
- noninteractive attach でも default は deny
- attachment 中に remote が capability escalation request を送っても拒否
- capability 変更は新しい pairing を要求

将来 `ASK` policy を追加できるが、v1 では prompt timing と automation を単純にするため CLI flag による明示許可に限定する。

### 18.5 copy permission と clipboard poisoning

remote の `copy` は local clipboard write である。秘密情報の read ではないが、次の攻撃になり得る。

- cryptocurrency address の差し替え
- shell command の差し替え
- URL の差し替え
- misleading text の注入

copy は primary UX のため default allow とするが、local user は拒否できる。

```sh
kclip attach --copy=deny --paste=allow dev@example.com
```

両方 deny の場合、attach は usage error とする。

copy payload に control character が含まれていても clipboard data として許可する。ただし diagnostics/logging へ raw 表示しない。

### 18.6 secret と lifetime

| 値 | entropy | 生成場所 | 保存場所 | lifetime |
|---|---:|---|---|---|
| pairing code | 80 bit | remote `kclip pair` | process memory | 10分または pairing 完了 |
| pair credential | 128-bit output、実効強度は pairing code の80 bit以下 | local/remote | process memory | pairing 完了まで |
| socket ID | 96-bit output、実効強度は pairing code の80 bit以下 | local/remote | path/state | attachment endpoint lifetime |
| attachment ID | 128 bit | local agent | local/remote metadata | attachment lifetime |
| nonce | 128 bit | local agent | local memory + remote lease `0600` | attachment lifetime |
| capability | N/A | local policy | local memory + remote lease copy | attachment lifetime |

pairing code:

- local argv/environment に載せない
- remote terminal 画面には表示される
- user が terminal log/scrollback を保存している場合は残り得る
- short-lived one-time valueであることを明記
- code から endpoint path と credential を domain separation して導出

nonce:

- local supervisor が失われた時点で local copy も失われる
- local disk へ永続保存しない
- remote lease file から同一 UID/root は読める
- constant-time comparison
- diagnostic error へ含めない
- crash dump の完全防止は保証できないが、scope を小さくする

### 18.7 socket permission

local:

- runtime directory `0700`
- agent/control socket `0600`
- owner/type を検証
- symlink を辿らない

remote:

- random derived path
- `StreamLocalBindMask=0177`
- 接続前に socket type、owner、mode を検証
- current UID owner の socket は必要に応じて `chmod(0600)`
- 別 UID の接続を mode で拒否
- pair credential/nonce でも protocol level verification
- path の存在だけでは attachment を使えない

file permission と protocol secret の二重防御とする。

### 18.8 OpenSSH security

kclip は次を行わない。

- `StrictHostKeyChecking=no` の自動指定
- `UserKnownHostsFile=/dev/null` の自動指定
- password/passphrase の取得・保存
- 独自暗号化
- SSH agent forwarding の強制
- server forwarding policy の迂回
- SSH stderr に含まれる secret の無差別 log

host key verification、authentication、ProxyJump、certificate、FIDO key 等は OpenSSH に委ねる。

ControlMaster を使う場合、control socket の owner/mode は OpenSSH security model に依存する。`kclip` は current UID 所有でない control socket を使わない。

### 18.9 command injection

kclip が生成する local command:

- `posix_spawnp` と argv list
- kclip 自身は shell を介さない
- clipboard body を command-line argument にしない
- executable discovery 後は absolute path を保持
- user SSH option を token-aware parser で検証
- dedicated sidecar では `PermitLocalCommand=no`
- arbitrary user forwarding を混在させない

OpenSSH は user の `ProxyCommand`、`Match exec`、`KnownHostsCommand` 等で shell/外部 command を使い得る。これは user の SSH configuration の trust boundary であり、kclip は内容を生成・展開・log しない。

remote command が必要なのは convenience `kclip ssh` の bootstrap のみ。

- dedicated attach は `-N` で remote command を実行しない
- `kclip ssh` は dedicated POSIX shell word encoder を使う
- endpoint/token character set を制限
- input validation と quoting の双方を行う

### 18.10 logging/redaction

絶対に log しないもの:

- clipboard body
- Base64 clipboard body
- pairing code
- pair credential
- attachment nonce
- full protocol frame
- password/passphrase
- environment 全体
- full SSH argv に secret が含まれる可能性がある場合の argv dump

log 可能なもの:

```text
operation=copy
attachment=KC-A7D2
backend=attachment
transport=controlmaster
bytes=421
status=ok
duration_ms=8
```

remote socket path は debug で basename の短い prefix のみ。full attachment ID は `--verbose` でも必要最小限とする。

error message へ protocol credential を含めないことを automated test する。

### 18.11 pairing race

同じ pair code に複数 local attach が競合した場合:

- 最初に正しい PAIR credential を受け、PAIR_CONFIRM まで完了した agent だけが成功
- remote pair process は1つの leaseだけを commit
- code は consume
- 後続は `PAIRING_CONSUMED`
- attachment ID/nonce を混在させない

hostile local process が code を知って先に attach できる場合、その local UID はすでに local user と同じ権限を持つため threat model 外である。

remote 別 UID が socket path を発見しても file mode と pair credential により拒否する。同一 UID/root は trust boundary 内である。

### 18.12 clipboard request flood

- 1 connection 1 request
- bounded listen backlog
- single active request
- connection-wide deadline
- operation-specific size limit
- clipboard command timeout
- malformed credential は clipboard backend を起動せず拒否
- repeated auth failure の rate-limited logging
- clipboard body を error body に echo しない

v1 は remote 同一 UID の intentional denial-of-service を完全に防ぐことを目標にしない。local agent の memory/CPU allocation を bounded にする。

---

## 19. logging と diagnostics

### 19.1 logger

```kotlin
enum class LogLevel {
    ERROR,
    WARN,
    INFO,
    DEBUG,
    TRACE,
}

interface Logger {
    fun log(
        level: LogLevel,
        event: String,
        fields: Map<String, String> = emptyMap(),
    )
}
```

logger は structured field を受け取るが、secret type を `String` へ変換しない設計にする。

```kotlin
sealed interface LogValue {
    data class Public(val value: String) : LogValue
    data class Count(val value: Long) : LogValue
    data object Redacted : LogValue
}
```

禁止 field:

- clipboard body
- Base64 clipboard body
- pairing code
- pair credential
- nonce
- paste response
- password/passphrase
- unfiltered environment
- full protocol bytes

### 19.2 remote `doctor`: 未 attach

すでに通常 SSH で入った shell:

```text
remote$ kclip doctor

kclip
  version: 1.0.0
  platform: linux-x86_64
  context: SSH
  terminal: /dev/pts/7

local clipboard attachment
  bound to this terminal: no
  copy: OSC 52 fallback may be available
  paste: unavailable

To enable copy and paste:
  1. run here:
       kclip pair --paste
  2. run on your local machine:
       kclip attach --paste=allow <same-ssh-destination>

Note: an existing remote shell cannot add reverse forwarding by itself.
```

OSC 52 copy の status は terminal に問い合わせず推測するため、`available` と断定しない。

tmux:

```text
osc52 copy fallback
  tmux: detected
  status: unknown
  note: attachment mode does not require tmux clipboard passthrough
```

### 19.3 remote `doctor`: live attachment

```text
remote$ kclip doctor

kclip
  version: 1.0.0
  context: SSH
  terminal: /dev/pts/7

local clipboard attachment
  id: KC-A7D2
  state: reachable
  protocol: 1
  copy: allowed
  paste: allowed
  endpoint: Unix socket
  latency: 7 ms

security
  scope: this terminal for routing
  trust: remote uid 1000 and root
```

`doctor` は PING のみを送り、clipboard を変更せず、paste body を読まない。

### 19.4 remote `doctor`: paste denied

```text
local clipboard attachment
  id: KC-A7D2
  state: reachable
  copy: allowed
  paste: denied by local policy

To enable paste, create a new attachment:
  remote$ kclip pair --replace --paste
  local$  kclip attach --paste=allow <same-ssh-destination>
```

### 19.5 remote `doctor`: stale attachment

```text
local clipboard attachment
  id: KC-A7D2
  state: unreachable
  last error: connection to attachment endpoint failed
  copy: unavailable
  paste: unavailable

Local recovery:
  kclip attachments
  kclip reconnect KC-A7D2

If the local agent no longer exists:
  remote$ kclip pair --replace --paste
```

nonce、full socket path、raw `errno` は既定表示に含めない。

### 19.6 local `attachments`

```text
local$ kclip attachments

ID       DESTINATION       TRANSPORT       COPY   PASTE   STATE
KC-A7D2  dev@example.com   controlmaster   allow  allow   active
KC-94F1  prod              dedicated       allow  deny    needs-auth
KC-0B31  old-host          dedicated       allow  allow   stopped
```

詳細:

```text
local$ kclip attachments --json
```

```json
{
  "schemaVersion": 1,
  "attachments": [
    {
      "id": "7A2D5F1E6B684F0DA41C5D8D8E4A9110",
      "displayId": "KC-A7D2",
      "destination": "dev@example.com",
      "transport": "controlmaster",
      "capabilities": ["copy", "paste"],
      "state": "active",
      "createdAt": "2026-06-24T10:15:00Z"
    }
  ]
}
```

JSON に endpoint path、pair code、nonce を含めない。

### 19.7 local attach failure messages

ControlMaster が見つからず dedicated fallback:

```text
No usable ControlMaster was found for dev@example.com.
Opening a dedicated SSH forwarding connection.
```

explicit ControlMaster only:

```text
kclip attach: no active ControlMaster matches dev@example.com
hint:
  pass the same alias/options used by the original ssh command,
  pass `--control-path`, or use `--transport=auto`
```

forwarding rejected:

```text
kclip attach: remote Unix-socket forwarding was rejected

Possible causes include SSH server forwarding policy, authorized_keys
restrictions, an unsupported OpenSSH version, or a stale socket path.

copy may still work through OSC 52.
paste cannot be enabled without an attachment.
```

pair request not waiting:

```text
kclip attach: no matching pairing request completed before the deadline
hint: run `kclip pair` again in the intended remote terminal
```

wrong destination:

```text
kclip attach: the pairing credential was not accepted on this SSH destination
The pairing code may belong to another host/session, may have expired,
or may already have been consumed.
```

paste not requested:

```text
kclip attach: `--paste=allow` was supplied, but the remote pairing request
did not request paste. The attachment was created with copy only.
```

### 19.8 local `reconnect`

success:

```text
local$ kclip reconnect KC-A7D2
Reconnected KC-A7D2 using a dedicated SSH forwarding connection.
```

needs new pair:

```text
kclip reconnect: local agent KC-A7D2 is no longer running
Its attachment nonce was intentionally not persisted.
hint: run `kclip pair --replace` remotely and attach again
```

socket reclaim failure:

```text
kclip reconnect: the previous remote socket could not be reclaimed
hint: create a new pairing from the remote shell with `kclip pair --replace`
```

### 19.9 `doctor --json`

remote example:

```json
{
  "schemaVersion": 1,
  "applicationVersion": "1.0.0",
  "platform": {
    "os": "linux",
    "arch": "x86_64"
  },
  "context": {
    "ssh": true,
    "tty": "/dev/pts/7",
    "tmux": false,
    "screen": false
  },
  "attachment": {
    "bound": true,
    "displayId": "KC-A7D2",
    "state": "active",
    "reachable": true,
    "protocol": 1,
    "capabilities": ["copy", "paste"]
  },
  "osc52": {
    "copy": "unknown",
    "paste": "unsupported-by-kclip-v1"
  }
}
```

field 追加は同じ schema version で許可する。field 削除、型変更、意味変更は `schemaVersion` を更新する。

### 19.10 diagnostic safety

`doctor` は次を行わない。

- clipboard を書き換える
- clipboard body を読む
- OSC 52 query を送る
- host key checking を変更する
- stale remote socket を自動 unlink する
- paste permission を暗黙変更する
- pairing code を生成する

`doctor` は diagnosis と exact next action の提示に限定する。

---

## 20. exit code

| code | 意味 |
|---:|---|
| 0 | success |
| 2 | CLI usage / invalid input |
| 3 | clipboard backend unavailable |
| 4 | permission/capability denied |
| 5 | attachment unavailable / stale |
| 6 | protocol failure |
| 7 | SSH forwarding rejected / transport failure |
| 8 | payload too large |
| 9 | timeout / pairing expiry |
| 10 | subprocess failure |
| 70 | internal software error |

`copy` / `paste` / `pair` / `attach` / `reconnect` は上表を返す。

`kclip ssh` は OpenSSH 起動後、原則として OpenSSH child の exit status を返す。

- normal exit: child exit code
- signal exit: `128 + signal`
- OpenSSH 起動前の setup failure: kclip 固有 exit code
- attachment bootstrap failure: kclip forwarding/protocol code
- local clipboard request failureだけでは SSH shell を終了しない

kclip 固有 code と OpenSSH code は衝突し得る。v1 では shell wrapper からの厳密な provenance 判定を提供しない。必要になった場合は将来 `--status-json-fd` を検討する。

---

## 21. resource limit と timeout

既定値:

| 対象 | 値 |
|---|---:|
| system/attachment copy | 1 MiB |
| system/attachment paste | 1 MiB |
| OSC 52 copy | 100 KiB |
| PAIR metadata body | 4 KiB |
| protocol error body | 4 KiB |
| PING body | 1 KiB |
| clipboard command stderr | 16 KiB |
| pairing code entropy | 80 bit |
| attachment nonce | 128 bit |
| attachment ID | 128 bit |
| pairing expiry | 10分 |
| pair socket retry initial | 100 ms |
| pair socket retry max | 1秒 |
| attach foreground deadline | pairing expiry以下 |
| agent request deadline | 5秒 |
| clipboard command timeout | 5秒 |
| attachment connect timeout | 5秒 |
| OSC 52 TTY write timeout | 2秒 |
| automatic reconnect initial | 1秒 |
| automatic reconnect steady | 最大60秒間隔 |
| local control response | 5秒 |

`--max-bytes` は copy/paste の操作上限を既定値より小さくする用途を主とする。既定値より大きくできるとしても hard limit 16 MiB を超えない。OSC 52 の hard limit は 1 MiB とし、通常は100 KiBの既定を維持する。

protocol header の 32-bit length をそのまま allocation size に使わず、operation limit と Kotlin `Int.MAX_VALUE` の両方を先に比較する。

pairing expiry は monotonic clock で判定する。表示用 timestamp のみ wall clock を使う。system clock change で code の寿命が延びないようにする。

reconnect loop は jitter を使い、同時に多数 attachment が切れた場合の SSH reconnect storm を避ける。

---

## 22. filesystem と cleanup

### 22.1 scope helper

RAII 相当の scope helper を common 層に用意する。

```kotlin
inline fun <T : KclipCloseable, R> T.useOutcome(
    block: (T) -> Outcome<R>,
): Outcome<R>
```

cleanup error は primary error を上書きしない。success path で cleanup だけが失敗した場合は warning とする。

### 22.2 remote pairing cleanup

`kclip pair` が success、timeout、Ctrl-C、fatal error のいずれで終了しても:

1. pairing request marker を削除
2. pair credential/code buffer を best-effort zeroize
3. open channel を close
4. temp lease/binding file を削除
5. committed lease/binding は success 時だけ残す

PAIR response 後、PAIR_CONFIRM 前に失敗した場合は half-committed lease/binding を rollback する。rollback 自体が失敗した場合、binding を `pending` state として mark し、通常の `copy` / `paste` では使わない。

### 22.3 local attach cleanup

attach が pairing 前に失敗:

1. reverse forward を cancel/stop
2. local agent listener/control socket を close
3. expected pair credential を zeroize
4. local metadata/temp file を削除
5. `_attach-agent` process を終了

attach success 後:

- attachment supervisor が resource owner
- foreground `kclip attach` は resource を閉じない
- local registry entry を agent readiness と同時に commit

### 22.4 detach cleanup

ControlMaster transport:

1. `ssh -O cancel -R ...`
2. agent listener close
3. local socket unlink
4. control socket unlink
5. metadata remove
6. nonce/credential zeroize

dedicated transport:

1. private master に `ssh -O exit`
2. 必要なら process terminationを確認
3. private ControlPath unlink
4. agent/listener/metadata cleanup

SSH cancel/exit が失敗しても secret と local listener の cleanup は続ける。

### 22.5 abrupt termination

`SIGKILL`、kernel crash、power loss では cleanup できない。

local 起動時の stale detection:

- runtime directory owner/mode を検証
- metadata PID が存在するか
- control socket PING が成功するか
- private SSH master `-O check` が成功するか
- agent socket connect が成功するか

remote `doctor` の stale detection:

- TTY identity が current TTY と一致するか
- lease checksum/version が正しいか
- endpoint PING が成功するか

自動削除は保守的に行う。

削除可能な local entry:

- current UID owned
- naming pattern が kclip
- control socket inactive
- PID が存在しない、または process identity が kclip agent と一致しない
- mtime が grace period より古い

削除可能な remote binding/lease:

- current UID owned
- strict parse 可能
- endpoint unreachable
- binding の TTY device/inode が存在しない、または明示 `prune`
- live pair process が参照していない

v1 初期 release では `doctor` に candidate を表示し、`kclip attachments prune` / `kclip unbind` に明示操作を要求してよい。安全な integration test が揃った後に automatic prune を有効化する。

### 22.6 remote socket cleanup

remote Unix socket は sshd/OpenSSH が所有する。kclip remote process が arbitrary path を unlink しない。

- normal cancel/connection close による OpenSSH cleanupを期待
- reconnect時も mux `-O forward` の stale socket 置換には依存しない
- ControlMaster transport では `-O cancel`
- stale collision が残れば new pairingで new random path
- `kclip doctor` は path を削除しない

### 22.7 file descriptor hygiene

- secret config pipe は agent setup 後すぐ close
- child process へ不要 FD を継承しない
- `FD_CLOEXEC` を原則設定
- clipboard command に agent/control socket FD を渡さない
- OpenSSH に必要なstdioとcontrol/listener関連以外を渡さない
- crash/error path も同じ cleanup table で test する

---

## 23. Windows を見据えた境界

v1 で Windows 実装を始めないが、既存 SSH attach architecture のうち OS 固有部分を interface の外へ漏らさない。

| concern | Unix v1 | Windows 候補 |
|---|---|---|
| clipboard | external commands | Win32 `OpenClipboard` / `CF_UNICODETEXT` |
| process | `posix_spawnp` | `CreateProcessW` |
| local agent IPC | Unix domain socket | named pipe または loopback TCP |
| SSH local target | Unix socket path | loopback TCP が現実的候補 |
| remote endpoint | Unix socket | remote Unix hostなら Unix socketを継続可能 |
| polling | `poll` + self-pipe | wait handles / IOCP / event adapter |
| terminal | `/dev/tty` | console/ConPTY、OSC 52 は terminal capability 次第 |
| signal | POSIX signal | console control handler |
| runtime state | owner/mode | ACL / user SID |
| path | UTF-8 POSIX path | UTF-16 Windows path |
| SSH | OpenSSH executable | Windows OpenSSH executable |

重要なのは、Windows のために Unix v1 を抽象化し過ぎないことである。共通契約として固定するもの:

- `ClipboardBackend`
- `AttachmentRegistry`
- `PairingCode` / `AttachmentNonce`
- `ProcessSpawner`
- `CommandRunner`
- `LocalIpc`
- `ByteChannel`
- `ReverseForwardTransport`
- `Terminal`
- `SecureRandom`
- agent wire protocol
- capability/policy
- application use cases

### 23.1 Windows local + Unix remote

候補 architecture:

```text
remote Unix socket
  -> OpenSSH remote forwarding
  -> local 127.0.0.1 ephemeral port
  -> Windows kclip agent
```

OpenSSH の `-R remote_socket:local_host:local_port` を利用できるため、remote endpoint は Unix socket のまま、local target だけ loopback TCP にできる。

要件:

- agent は `127.0.0.1` / `::1` のみ bind
- random port
- protocol nonce を必須
- Windows Firewall prompt を発生させない構成を検証
- local SID単位の control channel
- TCP listener informationを local registry に限定

Windows named pipe を OpenSSH が直接 stream forward できることは設計前提にしない。

### 23.2 wire protocol

wire protocol は OS 非依存の UTF-8 bytes と fixed-width integer のまま維持する。

Windows clipboard は UTF-16 native API との変換が必要だが、conversion は Windows `ClipboardBackend` 内に閉じ込める。

### 23.3 TTY scope

Windows OpenSSH server/ConPTY 上の remote側実装を将来行う場合、Unix device/inode に相当する scope identity が必要になる。

```kotlin
sealed interface TerminalScopeIdentity {
    data class UnixTty(
        val device: ULong,
        val inode: ULong,
    ) : TerminalScopeIdentity

    data class WindowsConsole(
        val sessionId: String,
    ) : TerminalScopeIdentity
}
```

v1 の persisted format には scope type/version field を持たせ、Unix 固定 struct を protocol の普遍概念にしない。現在のPAIR bodyは v1 Unix target用であり、Windows remote support時には protocol v2または extensible scope fieldを導入する。

---

## 24. test strategy

### 24.1 common unit test

clipboard:

- `ClipboardPayload` UTF-8 validation
- empty payload
- NUL、emoji、combining character
- 末尾改行を保持
- max size の `N-1`、`N`、`N+1`
- payload が error/log/toString に出ない

resolver:

- live attachment を system/OSC 52 より優先
- binding absent の SSH copy は OSC 52
- binding stale の copy は OSC 52 に fallback しない
- SSH paste without attachment は guidance error
- paste denied
- explicit `--attachment`
- local context の system backend

pairing:

- 80-bit code generation
- Crockford Base32 round trip
- ambiguous character normalize
- invalid length/check character
- domain-separated socket ID / credential
- code expiry
- code consume
- 同一 code への競合 attach
- secret type の equality/zeroize behavior

attachment state:

- TTY device/inode match
- reused PTY path with different inode
- atomic lease/binding write
- corrupt/truncated/checksum mismatch
- unknown format version
- owner/mode violation
- local metadataにnonceが含まれない

protocol:

- golden request/response bytes
- PAIR body encode/decode
- PAIR_CONFIRM state transition
- COPY/PASTE/PING
- short header
- bad magic
- unknown version/opcode
- non-zero flags/reserved
- oversized lengthをallocation前に拒否
- bad pair credential
- bad nonce
- error status mapping
- response body limit
- one connection/one request
- protocol object/logにsecret/bodyが出ない

SSH command generation:

- ControlMaster `-O check/forward/cancel`
- dedicated `-fNT -M -S -R`
- destination/options ordering
- ProxyJump
- custom port/identity/config
- conflict option rejection
- shellを使わない
- managed-session remote word quoting
- host key checkingをkclipが弱めない

### 24.2 Unix integration test

IPC/filesystem:

- Unix socket bind/connect
- directory `0700` / socket `0600`
- symlink/path replacement rejection
- Unix socket path length boundary
- partial read/write
- peer close
- connection-wide deadline
- stale socket handling
- local control socket

process/signal:

- `posix_spawnp` stdin/stdout/stderr mapping
- secret config pipeのFD inheritance
- `FD_CLOEXEC`
- self-pipe wakeup
- `SIGCHLD` と `waitpid`
- child timeout、SIGTERM、SIGKILL
- attach cancel cleanup
- agent detach cleanup
- agent processがcaller終了後もactive
- dedicated private master stop

OSC 52:

- pseudo terminalへのexact bytes
- stdoutにsequenceが漏れない
- payload limit
- no paste/query sequence
- signal/error時のTTY状態非変更

### 24.3 platform clipboard backend test

fake executable を一時 directory に配置し、argv、stdin、stdout、exit status を記録する。

macOS:

- `/usr/bin/pbcopy` / `pbpaste` adapterのcommand contract
- binary/newline preservation
- subprocess timeout/error
- headless CIではfake adapter

Linux:

- Wayland priority
- xclip fallback
- xsel fallback
- `WAYLAND_DISPLAY`/`DISPLAY` と executable availability の組合せ
- tool-specific no-newline options
- backend command failure
- size-limited paste

real clipboard integration は、利用可能な desktop session を持つ runner/manual matrix に限定する。

### 24.4 dedicated attach end-to-end

ephemeral OpenSSH server と fake local clipboard backend を使う。

1. 通常の `ssh` で interactive remote shell を開始
2. remote shell で `kclip pair --paste`
3. 別 local processで `kclip attach --transport=dedicated --paste=allow`
4. PAIR/PAIR_CONFIRM 完了
5. remote copy が fake local clipboardへ到達
6. remote paste が fake clipboard bodyを受け取る
7. bytes/newlineを保持
8. local detach後、remote operationがstale error
9. local reconnect後、same remote bindingで復旧
10. agent kill後はre-pairが必要

test variation:

- passwordless key
- password/askpassを除くinteractive auth smoke
- remote forwarding rejected
- `AllowStreamLocalForwarding` disabled
- authorized_keys forwarding restriction
- stale remote socket
- path length
- server disconnect
- network interruption
- sidecar private ControlMaster health

### 24.5 ControlMaster attach end-to-end

1. local で ControlMaster を開始
2. その masterを使って通常 shellを開く
3. remote `kclip pair`
4. local `kclip attach --transport=controlmaster`
5. `ssh -O forward` success
6. copy/paste
7. `kclip detach` が `-O cancel` だけを行い、original master/shellを終了しない
8. master終了でattachment DEGRADED
9. master再作成またはdedicated fallback
10. wrong ControlPath/destinationで明示error

確認事項:

- masterに既存forwardがあっても壊さない
- kclip forwardだけcancel
- original SSH sessionはattach/detachを通して継続
- custom `-S`
- `ControlPersist`
- `ProxyJump` + ControlMaster
- no active masterならauto dedicated

### 24.6 existing-session UX acceptance

最重要 acceptance scenario:

```text
local-A$ ssh dev@example.com
remote$ kclip doctor
remote$ kclip pair --paste

local-B$ kclip attach --paste=allow dev@example.com

remote$ printf 'hello' | kclip copy
remote$ kclip paste
```

合格条件:

- original `ssh` commandを`kclip ssh`へ置き換えなくてよい
- original shellを再起動しない
- original shellのenvironment変更を要求しない
- pair/attach以降はplain `kclip copy/paste`
- paste capabilityがlocalで明示される
- localからremoteへの新規connectだけで動く
- remoteからlocalへのdirect network path不要

### 24.7 tmux / screen

tmux:

- pane Aでpair、pane Aからcopy/paste
- pane Bは未bound
- split後のnew pane
- detach/reattach
- server restartでPTY変化
- 同一 pane を2 clientで表示し last binding wins
- agent pathはtmux OSC settingに依存しない
- OSC 52 fallbackは別matrix

screen:

- window単位pair
- detach/reattach
- nested screen/tmux
- current TTY identity

### 24.8 多段 SSH

- `ProxyJump` config aliasでdedicated attach
- `-J` command-line option
- ControlMaster over ProxyJump
- manual nested SSHではlocalからtargetへrouteを再指定
- targetへrouteがない場合のclear failure
- deep hostからOSC52 copy fallback
- deep host pasteがOSC52 queryへfallbackしない

### 24.9 permission test

matrix:

| remote request | local copy policy | local paste policy | result |
|---|---|---|---|
| copy | allow | deny | copy |
| copy,paste | allow | deny | copy only |
| copy,paste | allow | allow | copy,paste |
| copy,paste | deny | allow | paste only |
| copy | deny | allow | error: no granted capability |
| copy,paste | deny | deny | error |

- remote lease capability tamperでescalateできない
- wrong nonce
- 同一 UID の lease read は threat model 通り利用可能
- 別 UID は file/socket permission で拒否
- root isolationをtest expectationにしない
- clipboard body/tokenがlogに出ない

### 24.10 protocol robustness

- random byte streamをdecoderへ入力
- header length fieldの全境界
- 1 byteずつ到着するslow client
- body未完で切断
- response途中disconnect
- malformed connection連続
- bad credential rate limiting
- PAIR accepted後confirmなし
- duplicate PAIR_CONFIRM
- expired/consumed code
- large UTF-8 multi-byte boundary
- invalid UTF-8 body

Kotlin/Native で利用可能な fuzzing infrastructure が release workflow に適合しない場合も、deterministic seed の property test harness を repository 内に持つ。

### 24.11 manual acceptance matrix

最低限:

- macOS Terminal
- iTerm2
- kitty
- WezTerm
- Linux Wayland + wl-clipboard
- Linux X11 + xclip
- xsel fallback
- tmux内外
- GNU screen内外
- ProxyJump
- 複数同時 attachment
- paste denied/allowed
- ControlMaster existing attach
- dedicated fallback
- remote forwarding denied
- reconnect
- Intel Mac artifactは提供期間のみ

OSC 52 copy は terminal setting に依存するため、manual matrix の結果を README に「確認済み構成」として記録し、一般的な互換性保証と混同しない。

---

## 25. repository 構成

repository は `../kmp-template-impl` の方針を踏襲し、root の `settings.gradle.kts` で `includeBuild("build-logic")` と `TYPESAFE_PROJECT_ACCESSORS` を有効化する。各 module の `build.gradle.kts` は薄く保ち、target/source set、Detekt、dependency convention は build-logic の primitive plugin に寄せる。

Android/iOS app template の module 名をそのまま移植するのではなく、役割を CLI 向けに読み替える。

- template の `androidApp`: kclip では `:cli`
- template の `shared`: kclip では `:cli` から呼ばれる application composition
- template の `core:*`: kclip でも `:core:*` として domain / protocol / platform 境界を分割
- template の `feature:*`: kclip では CLI subcommand を `:cli` 内の feature-like package として扱い、初期 v1 では module 分割しない

```text
kclip/
  README.md
  LICENSE
  SECURITY.md
  CONTRIBUTING.md
  CHANGELOG.md
  docs/
    design-v1.md
    protocol-v1.md
    threat-model.md
    attach-troubleshooting.md
  gradlew
  gradlew.bat
  build.gradle.kts
  settings.gradle.kts
  build-logic/
    settings.gradle.kts
    build.gradle.kts
    src/main/kotlin/
      io/github/kclip/
        GradleDsl.kt
        VersionCatalogDsl.kt
        Detekt.kt
      primitive/
        KmpCommonPlugin.kt
        KmpUnixNativePlugin.kt
        KmpExecutablePlugin.kt
        DetektPlugin.kt
  gradle/
    wrapper/
      gradle-wrapper.jar
      gradle-wrapper.properties
    libs.versions.toml
    verification-metadata.xml
  config/
    detekt/
      detekt.yml
  cli/
    build.gradle.kts
    src/commonMain/kotlin/io/github/kclip/
      Main.kt
      cli/
        RootCommand.kt
        CopyCommand.kt
        PasteCommand.kt
        PairCommand.kt
        AttachCommand.kt
        ReconnectCommand.kt
        DetachCommand.kt
        AttachmentsCommand.kt
        SshCommand.kt
        DoctorCommand.kt
        ShimCommand.kt
        ManagedSessionCommand.kt
        AttachAgentCommand.kt
  core/
    domain/
      build.gradle.kts
      src/commonMain/kotlin/io/github/kclip/core/domain/
        Attachment.kt
        Pairing.kt
        Clipboard.kt
        Errors.kt
        Outcome.kt
        Time.kt
        Secrets.kt
    protocol/
      build.gradle.kts
      src/commonMain/kotlin/io/github/kclip/core/protocol/
        AgentProtocol.kt
        AgentProtocolCodec.kt
        PairingFrames.kt
    application/
      build.gradle.kts
      src/commonMain/kotlin/io/github/kclip/core/application/
        CopyUseCase.kt
        PasteUseCase.kt
        PairUseCase.kt
        AttachUseCase.kt
        ReconnectUseCase.kt
        DetachUseCase.kt
        DoctorUseCase.kt
    diagnostics/
      build.gradle.kts
      src/commonMain/kotlin/io/github/kclip/core/diagnostics/
        Doctor.kt
        Logging.kt
        Redaction.kt
    platform/
      build.gradle.kts
      src/commonMain/kotlin/io/github/kclip/core/platform/
        PlatformServices.kt
        Environment.kt
        FileSystem.kt
        ProcessSpawner.kt
        CommandRunner.kt
        LocalIpc.kt
        Terminal.kt
        SecureRandom.kt
        Sha256.kt
      src/unixMain/kotlin/io/github/kclip/core/platform/
        attachment/
          FileAttachmentRegistry.kt
          TtyIdentityProvider.kt
        ipc/
          UnixSocketIpc.kt
        process/
          PosixSpawnProcessSpawner.kt
          PollingCommandRunner.kt
        signals/
          SelfPipeSignalMailbox.kt
        ssh/
          OpenSshConfigResolver.kt
          OpenSshOptionPolicy.kt
          ControlMasterTransport.kt
          DedicatedSshTransport.kt
          OpenSshCommandBuilder.kt
        terminal/
          DevTtyTerminal.kt
          Osc52ClipboardBackend.kt
        filesystem/
          SecureRuntimeDirectory.kt
      src/macosMain/kotlin/io/github/kclip/core/platform/
        clipboard/
          MacOsClipboardBackend.kt
        PlatformServices.macos.kt
      src/linuxMain/kotlin/io/github/kclip/core/platform/
        clipboard/
          LinuxClipboardDiscovery.kt
          WaylandClipboardBackend.kt
          XclipClipboardBackend.kt
          XselClipboardBackend.kt
        PlatformServices.linux.kt
    agent/
      build.gradle.kts
      src/commonMain/kotlin/io/github/kclip/core/agent/
        AttachmentAgent.kt
        AgentControlProtocol.kt
        AttachmentAgentLauncher.kt
      src/unixMain/kotlin/io/github/kclip/core/agent/
        UnixAttachmentAgentProcess.kt
  packaging/
    homebrew/
    completions/
  integration/
    README.md
    openssh/
      fixtures/
      scripts/
  .github/
    workflows/
```

初期 plugin 構成:

```text
kclip.primitive.kmp.common
  - org.jetbrains.kotlin.multiplatform
  - Kotlin BOM

kclip.primitive.kmp.unix-native
  - macosArm64()
  - linuxX64()
  - linuxArm64()
  - explicit unixMain / macosMain / linuxMain hierarchy
  - kotlinx.cinterop.ExperimentalForeignApi opt-in は cinterop 利用箇所に限定

kclip.primitive.kmp.executable
  - kclip executable binary
  - baseName = "kclip"
  - executable entrypoint は :cli に限定

kclip.primitive.detekt
  - root config/detekt/detekt.yml
  - report merge
```

module dependency direction:

```text
:cli
  -> :core:application
  -> :core:agent
  -> :core:platform
  -> :core:diagnostics

:core:application
  -> :core:domain
  -> :core:protocol
  -> :core:diagnostics

:core:agent
  -> :core:domain
  -> :core:protocol
  -> :core:platform
  -> :core:diagnostics

:core:platform
  -> :core:domain
  -> :core:diagnostics

:core:protocol
  -> :core:domain
```

`build-logic` 自体は project domain に依存しない。package 名や plugin ID は repository 名に合わせるが、template の helper pattern、VersionCatalog access、Detekt setup、thin module build files の考え方を維持する。

legacy single-module layout は採用しない。小さく始める場合も、空 module を増やし過ぎるのではなく、上記の dependency boundary を崩さない最小 skeleton から開始する。

設計上の package dependency direction:

```text
cli
  -> application
      -> domain + protocol interfaces
          <- unix/platform adapters
```

- `commonMain` は POSIX/OpenSSH command detail を知らない
- `unixMain/ssh` は agent protocol payload を知らない
- clipboard backend は attachment/pairing を知らない
- agent は Clikt type を受け取らない
- secret type の formatting は diagnostics package から直接利用できない

package name は publish group を決めた時点で調整する。OSS 名と package namespace を密結合しない。

---

## 26. build と release

### 26.1 toolchain baseline

設計時点の初期 baseline:

- Kotlin 2.4 系
- Clikt 5 系。具体 version は `libs.versions.toml` と dependency lock で固定
- Gradle wrapper を repository に固定
- dependency lock/checksum verification を有効化

release 時点で存在する stable version を CI で固定し、設計書だけが先行した架空の patch/minor version を記載しない。

compiler/library update は CI matrix を通して随時行う。wire protocol version は compiler/library version と独立させる。

### 26.2 artifact

```text
kclip-v1.0.0-macos-aarch64.tar.gz
kclip-v1.0.0-macos-x86_64.tar.gz       # 提供可能な期間のみ
kclip-v1.0.0-linux-x86_64.tar.gz
kclip-v1.0.0-linux-aarch64.tar.gz
SHA256SUMS
```

archive:

```text
kclip
LICENSE
README.md
completions/
```

Linux Kotlin/Native binary は完全な universal static binary とみなさない。glibc baseline を明記し、可能な限り古い正式サポート runner で build して互換範囲を広げる。musl 対応は v1 の約束に含めない。

### 26.3 distribution

v1:

- GitHub Releases
- Homebrew formula/tap
- shell completion: bash、zsh、fish

後続:

- Debian/RPM packages
- Nix package
- Scoop/winget は Windows 実装時

### 26.4 release gate

- common/unit test 全 target success
- macosArm64 integration success
- linuxX64 integration success
- local clipboard backend integration
- existing normal SSH + dedicated attach E2E
- existing ControlMaster + `-O forward` attach E2E
- paste default deny test
- paste explicit allow test
- forwarding rejected failure-message test
- transport disconnect/reconnect test
- tmux pane binding smoke test
- protocol golden test unchanged、または protocol version 更新
- pairing/nonce/log redaction test
- dependency license notice
- checksum
- `SECURITY.md`
- `kclip doctor --json` schema compatibility test

---

## 27. compatibility と versioning

### 27.1 application version

Semantic Versioning を使用する。

### 27.2 protocol version

agent protocol は単一 byte の整数 version。

- 同じ protocol v1 なら application version が異なっても通信可能
- unsupported version は silent fallback せず明示エラー
- PAIR、PAIR_CONFIRM、COPY、PASTE、PING は protocol v1 の一部
- v2 を追加する際は PING または bootstrap negotiation で共通 version を選べる設計へ拡張
- v1 initial implementation では完全一致のみ

### 27.3 persisted state version

次は wire protocol と別に version を持つ。

- remote lease format
- remote TTY binding format
- local agent metadata
- `doctor --json`
- `attachments --json`

state reader は:

- current version を strict parse
- future version を書き換えず unsupported error
- old version migration は atomic
- secret を migration log に出さない

### 27.4 CLI compatibility

v1.0 以降:

- command/option 削除は major
- primary existing-session flow `pair -> attach -> copy/paste` の破壊変更は major
- paste default deny を緩める変更は major
- default permission を厳しくする変更は security release では minor/patch の可能性を許容し、CHANGELOG に明記
- `kclip ssh` は convenience であり、attach flow より強い compatibility guarantee を持たない
- JSON schema の破壊変更は schema version 更新
- human-readable diagnostics の文面は stable API としない
- exit code の意味変更は major

### 27.5 OpenSSH compatibility

v1 は次を要求する。

- local OpenSSH client が remote Unix-domain socket forwarding を扱える
- remote sshd が該当 forwarding を許可
- ControlMaster path は `-O forward` / `-O cancel` を扱える client
- dedicated path は ControlMaster/private ControlPath と `-fNT` を扱える client

minimum OpenSSH version は implementation CI で実測し、README と release notes に固定する。設計書だけで古い version の互換性を推測しない。

ControlMaster がなくても dedicated path が動けば v1 supported とする。ControlMaster attach は optimization だが、正式 test 対象である。

---

## 28. 実装フェーズ

### Phase 0: repository と core

- Kotlin Multiplatform / Native build
- target/source set
- `build-logic` include build
- primitive convention plugins
- `:cli` と `:core:*` module skeleton
- Clikt root command
- `Outcome` / `KclipError`
- byte IO、clock、deadline、limits
- secret/redaction types
- CI skeleton

exit criterion:

- macosArm64 と linuxX64 の hello CLI
- `:cli` executable artifact
- `:core:*` modules の dependency direction check
- common test
- dependency verification
- secret `toString()` redaction

### Phase 0.5: OpenSSH transport feasibility spike

本実装に入る前に、Kotlin code へ閉じ込めない小さな shell / integration spike で OpenSSH transport の前提を確認する。成果物は `integration/openssh/README.md` と再実行可能な script とし、production code へ直接依存させない。

検証項目:

- remote Unix-domain socket forwarding の `-R remote.sock:local.sock`
- dedicated private master の `ssh -fNT -M -S ...`
- private master への `ssh -F /dev/null -S ... -O forward -R ...`
- `ClearAllForwardings=yes` と command-line `-R` を同一 invocation に置かない制約
- `ssh -O cancel -R ...` による exact forward cancel
- `StreamLocalBindMask=0177` と `StreamLocalBindUnlink=yes` の実測
- forwarding 拒否時の exit status と stderr classification
- path length、stale socket、permission error の代表ケース
- ProxyJump / custom port / custom identity の最小ケース

exit criterion:

- macOS local から test sshd へ dedicated sidecar attach の最小 forward が成功する
- Linux CI または containerized OpenSSH server で同じ spike が成功する
- ControlMaster がある場合に `-O forward` / `-O cancel` が original session を終了しない
- forwarding rejected と stale socket collision の失敗メッセージ設計に使える sanitized evidence が残る
- spike の結果に基づき、第16章と Phase 3/4 の command shape を必要なら更新する

### Phase 1: local clipboard

- macOS backend
- Linux discovery
- Wayland/xclip/xsel adapters
- `copy` / `paste`
- shim installer
- local `doctor`

exit criterion:

- local clipboard round trip
- bytes/newline preservation
- size/time limit
- fake backend CI

### Phase 2: attachment state と protocol

- pairing code generation/parse
- domain-separated credential/socket ID
- attachment ID/nonce
- protocol codec
- PAIR/PAIR_CONFIRM
- COPY/PASTE/PING
- secure runtime directory
- remote lease/TTY binding
- local metadata registry
- Unix socket client/listener

exit criterion:

- fake local agent と remote PairUseCase の integration
- protocol golden tests
- malformed/oversized tests
- atomic binding commit

### Phase 3: dedicated existing-session attach

- `kclip pair`
- `kclip attach --transport=dedicated`
- per-attachment `_attach-agent`
- private OpenSSH master
- local control protocol
- `attachments` / `detach`
- paste default deny / explicit allow
- existing normal SSH E2E

この phase で kclip の中核価値を成立させる。`kclip ssh` や OSC 52 fallback より先に完成させる。

exit criterion:

```text
ssh dev@example.com
remote$ kclip pair --paste
local$  kclip attach --paste=allow dev@example.com
remote$ kclip copy
remote$ kclip paste
```

が CI E2E と manual test で成功する。

### Phase 4: ControlMaster attach と reconnect

- `ssh -G` config resolver
- ControlPath discovery
- `ssh -O check`
- `ssh -O forward`
- `ssh -O cancel`
- auto fallback
- transport health
- noninteractive reconnect
- `kclip reconnect`
- stale diagnostics
- ProxyJump tests

exit criterion:

- original interactive session を止めず attach/detach
- master終了でDEGRADED
- dedicated reconnect
- explicit ControlMaster failure messages

### Phase 5: convenience と fallback

- `kclip ssh`
- managed session bootstrap
- OSC 52 copy
- tmux/screen diagnostics
- enhanced `doctor`
- shell completions

`kclip ssh` は Phase 3/4 の abstractions を再利用し、別 architecture を作らない。

### Phase 6: hardening と release

- permission/path ownership
- signal/cleanup
- pairing race
- reconnect storm protection
- fuzz/property tests
- logging redaction
- packaging
- SECURITY/README
- attach troubleshooting guide
- Homebrew
- release artifacts

---

## 29. v1 Definition of Done

### 29.1 primary existing-session UX

- [ ] user が通常の `ssh dev@example.com` で入った後から attach できる
- [ ] original SSH shell を終了・再起動しない
- [ ] remote `kclip pair` が current TTY を scope にする
- [ ] local `kclip attach` が pairing code を argv/environment に入れず受け取れる
- [ ] ControlMaster がある場合 `ssh -O forward` を使う
- [ ] ControlMaster がない場合 dedicated SSH connection を使う
- [ ] remote→local direct network path と local sshd を要求しない
- [ ] attach 後の remote command が plain `kclip copy` / `kclip paste`
- [ ] pair/attach success が protocol confirm 後にだけ表示される

### 29.2 copy/paste

- [ ] remote copy が local clipboard を更新する
- [ ] remote paste が local clipboard body を stdout に返す
- [ ] paste は既定拒否
- [ ] remote の `--paste` だけでは許可されない
- [ ] local `--paste=allow` の attachmentだけpaste可能
- [ ] copyは既定許可、`--copy=deny`で禁止可能
- [ ] capability tamperでescalationできない
- [ ] bytesと末尾改行を改変しない
- [ ] UTF-8不正入力と上限超過を安全に拒否
- [ ] clipboard bodyをlogに出さない

### 29.3 OSC 52

- [ ] attachmentなしの通常SSHでOSC 52 copy fallback
- [ ] OSC 52 sequenceがstdoutを汚さない
- [ ] attachment stale/denied時にsilent fallbackしない
- [ ] OSC 52 query/pasteを実装しない
- [ ] unsupported pasteでpair/attach guidanceを出す

### 29.4 transport

- [ ] ControlMaster attachがoriginal master/sessionを終了しない
- [ ] detachがkclipのforwardだけcancelする
- [ ] dedicated sidecarがPTY/remote commandを作らない
- [ ] forwarding拒否を明確に説明する
- [ ] ProxyJumpでattachできる
- [ ] manual nested SSHの制約をdocumentする
- [ ] transport lossをDEGRADEDとして検出
- [ ] supervisor存続中のreconnectでre-pair不要
- [ ] supervisor消失後はnonceを復元せずre-pair

### 29.5 pairing / attachment state

- [ ] pairing codeが80-bit one-time、10分expiry
- [ ] socket pathとcredentialをdomain separation
- [ ] nonceが128-bit secure random
- [ ] nonceがlocal disk/argv/env/logに残らない
- [ ] remote lease/bindingが`0600`
- [ ] runtime directoryが`0700`
- [ ] TTY path再利用をdevice/inodeで検出
- [ ] PAIR_CONFIRM前のhalf-pairをactiveにしない
- [ ]複数同時attachmentが分離
- [ ] stale attachmentをdoctorが説明

### 29.6 security

- [ ] local attachがpasteのremote UID/root trustを警告
- [ ]別remote UIDがsocket/file permissionで拒否される
- [ ]同一UID/rootはtrust boundary内と明記
- [ ] host key checkingをkclipが無効化しない
- [ ] local commandにshellを使わない
- [ ] managed remote commandを専用quote
- [ ] pair code、credential、nonce、payloadのredaction test
- [ ] malformed/slow clientで無制限allocation/blockしない

### 29.7 terminal / multiplexer

- [ ] tmux paneでpair/copy/paste
- [ ] new paneは別binding
- [ ] detach/reattachでPTY維持時にbinding維持
- [ ] screen windowで基本動作
- [ ] agent pathがOSC passthrough設定に依存しない
- [ ]複数viewerのlast-binding-wins制約を明記

### 29.8 local platform

- [ ] macOS `pbcopy`/`pbpaste` round trip
- [ ] Wayland + wl-clipboard round trip
- [ ] X11 + xclip round trip
- [ ] xsel fallback
- [ ] `pbcopy`/`pbpaste` shim
- [ ] headless/backend unavailable diagnostics

### 29.9 tooling / release

- [ ] `kclip doctor`が未attach、live、denied、staleを説明
- [ ] `kclip attachments` / `reconnect` / `detach`
- [ ] `doctor --json` / `attachments --json` schema test
- [ ] `kclip ssh`が同じattachment architectureを再利用
- [ ] macosArm64 と linuxX64 release gate
- [ ] linuxArm64 artifact
- [ ] protocol v1 golden test固定
- [ ] SECURITY.mdとattach troubleshooting guide
- [ ] release checksum と dependency verification

---

## 30. 将来拡張

優先候補:

1. Windows `mingwX64`
2. rich MIME
3. paste/copy の per-request `ask` policy
4. SSH host key identity に結び付いた永続 policy
5. local login agent と attachment restore
6. OSC 52 query の明示 experimental backend
7. mosh transport
8. remote attachment rebind/use command
9. clipboard access notification
10. protocol negotiation
11. TCP/QUIC等の代替transport
12. remote cleanup notification
13. desktop GUI status/menu

### 30.1 Windows

Windows local clipboard backend、process、IPC、OpenSSH transport を実装する。wire protocol と capability model は維持する。

### 30.2 persistent policy

永続 paste allow は単なる SSH alias string と結び付けない。

最低限必要な identity 候補:

- verified host key fingerprint
- effective SSH user
- port
- jump/proxy path の扱い
- local profile

identity ambiguity と host key rotation UX を設計してから導入する。

### 30.3 OSC 52 query

将来追加する場合も:

- `auto` には入れない
- explicit `--unsafe` / experimental
- terminal allowlist/capability probe
- raw TTY restoration test
- tmux/screen/nested SSH warning
- local user confirmation
- response size/timeout
- request source identityを表示できない制約

を必須とする。

### 30.4 per-request approval

```text
kclip attach --paste=ask
```

候補だが、headless local agent でどのUIにpromptを出すかが未解決である。

- terminal notification
- macOS notification/action
- Linux desktop portal
- local `kclip approvals`

v1 はattachment-level explicit allowに留める。

### 30.5 mosh

mosh はOpenSSH forwarding connectionと寿命/roaming modelが異なる。dedicated sidecarを併用できる可能性はあるが、remote host migration、network reconnect、pairing endpoint寿命を別設計として扱う。

### 30.6 remote attachment rebind

将来:

```sh
kclip use KC-A7D2
kclip bind KC-A7D2
```

で別TTYへ既存attachmentをbindする機能を追加できる。

ただし同一UID trust model、tmux複数viewer、stale nonceの扱いを明確にする必要がある。v1はpairし直す単純なUXを採る。

### 30.7 design principle

将来機能は次を崩さない。

- paste default deny
- capability authorityはlocal agent
- stale/unknownはfail closed
- token/bodyはnon-logging
- normal SSH sessionへのattachがfirst-class
- `kclip ssh`だけの専用機能を作らない

---

## 31. 参考資料

### Kotlin / CLI

- [Kotlin/Native binaries](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html)
- [Kotlin/Native target support tiers](https://kotlinlang.org/docs/native-target-support.html)
- [Kotlin Multiplatform expect/actual](https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html)
- [Kotlin/Native memory manager](https://kotlinlang.org/docs/native-memory-manager.html)
- [Kotlin Base64 API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.io.encoding/-base64/)
- [Clikt](https://github.com/ajalt/clikt)

### OpenSSH

- [OpenSSH ssh(1)](https://man.openbsd.org/ssh.1)
  - `-O check` / `forward` / `cancel`
  - `-R remote_socket:local_socket`
  - interactive escape commandline
- [OpenSSH ssh_config(5)](https://man.openbsd.org/ssh_config.5)
  - `ControlMaster`
  - `ControlPath`
  - `ControlPersist`
  - `RemoteForward`
  - `StreamLocalBindMask`
  - `StreamLocalBindUnlink`
  - `EnableEscapeCommandline`
- [OpenSSH sshd_config(5)](https://man.openbsd.org/sshd_config.5)
  - `AllowStreamLocalForwarding`
  - `DisableForwarding`
  - `MaxSessions`
- [OpenSSH release notes](https://www.openssh.com/releasenotes.html)
- [OpenSSH portable source](https://github.com/openssh/openssh-portable)

### OSC 52 / terminal

- [xterm control sequences / OSC 52](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html)
- [tmux clipboard integration](https://github.com/tmux/tmux/wiki/Clipboard)
- [kitty clipboard protocol configuration](https://sw.kovidgoyal.net/kitty/conf/#opt-kitty.clipboard_control)
- [kitty clipboard kitten](https://sw.kovidgoyal.net/kitty/kittens/clipboard/)
- [iTerm2 proprietary escape codes / clipboard](https://iterm2.com/documentation-escape-codes.html)

### Clipboard backends / POSIX / Windows

- [wl-clipboard](https://github.com/bugaevc/wl-clipboard)
- [xclip](https://github.com/astrand/xclip)
- [POSIX posix_spawn](https://pubs.opengroup.org/onlinepubs/007904975/functions/posix_spawn.html)
- [Windows Named Pipes](https://learn.microsoft.com/en-us/windows/win32/ipc/named-pipes)
- [Windows Clipboard](https://learn.microsoft.com/en-us/windows/win32/dataxchg/using-the-clipboard)

---
