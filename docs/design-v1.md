# kclip v1 設計書

| 項目 | 内容 |
|---|---|
| Status | Proposed |
| 対象 | `kclip` v1 / wire protocol v1 |
| 対応OS | macOS、Linux |
| 実装 | Kotlin/Native CLI |
| 作成日 | 2026-06-24 |
| 想定ライセンス | Apache-2.0 |

## 1. 概要

`kclip` は、ローカル環境と SSH 接続先の双方で同じ操作感を提供する、テキスト専用のクリップボード CLI である。

```sh
# ローカル
printf 'hello' | kclip copy
kclip paste

# kclip が管理する SSH セッション
kclip ssh --paste=allow dev@example.com

# 接続先
printf 'from remote' | kclip copy
kclip paste
```

v1 の中心は次の3点である。

1. **ローカルのネイティブクリップボード操作**
   - macOS: `/usr/bin/pbcopy`、`/usr/bin/pbpaste`
   - Linux/Wayland: `wl-copy`、`wl-paste`
   - Linux/X11: `xclip`、次点で `xsel`

2. **SSH セッションを介したローカルクリップボード操作**
   - `kclip ssh` が SSH プロセスと同じ寿命のローカル agent を起動する。
   - OpenSSH の reverse forwarding で、リモート Unix domain socket をローカル agent へ接続する。
   - リモートの `kclip copy` / `kclip paste` は、バージョン付きバイナリプロトコルで agent に要求する。

3. **通常の SSH セッションでの copy フォールバック**
   - agent がない場合、`kclip copy` は OSC 52 を利用できる。
   - `kclip paste` に OSC 52 query は使わない。読み取り対応が不均一で、端末応答の解析とセキュリティ上の扱いが複雑になるためである。

常駐デーモンは導入しない。`kclip ssh` 自身がセッション限定 agent となり、SSH 終了時に消える。

---

## 2. 目標

### 2.1 v1 の目標

- macOS/Linux 上で Kotlin/Native の単一実行ファイルとして動作する。
- ローカルで `copy` / `paste` を提供する。
- SSH 接続先から、ローカルのクリップボードへ安全側の既定値でアクセスできる。
- `pbcopy` / `pbpaste` 互換 shim を提供する。
- クリップボード本文を改変しない。改行を追加・削除しない。
- ペイロードは UTF-8 の `text/plain` に限定する。
- agent/protocol/backend を分離し、将来の Windows 実装で common 層を再利用できるようにする。
- エラー時に「何が不足しているか」を `doctor` で診断できるようにする。
- SSH の暗号化、ホスト鍵検証、認証設定は OpenSSH に委ね、独自 SSH 実装を持たない。

### 2.2 非目標

v1 では以下を実装しない。

- 画像、HTML、RTF、複数 MIME type
- クリップボード履歴、同期、監視
- 常駐デーモン、OS ログイン時の自動起動
- OSC 52 による paste/query
- mosh 対応
- SSH の非対話コマンド実行、`-N`、`-W`、subsystem
- Windows バイナリ
- Android、iOS、JVM 版
- リモートホスト上の同一 UID プロセスからの隔離
- OpenSSH 以外の SSH クライアントのサポート

---

## 3. 設計判断の要約

| 判断 | v1 の選択 |
|---|---|
| 実装言語 | Kotlin 2.4 系 Kotlin/Native |
| CLI ライブラリ | Clikt 5.1 系 |
| 非同期モデル | coroutine なし、`poll(2)` ベースの単一スレッド event loop |
| SSH | システムの OpenSSH クライアントを子プロセスとして起動 |
| agent | `kclip ssh` プロセス内、SSH セッション限定 |
| IPC | Unix domain socket |
| wire format | 固定長ヘッダー + raw UTF-8 body のバイナリ形式 |
| local clipboard | OS コマンドを shell を介さず起動 |
| remote copy | agent 優先。agent がない通常 SSH では OSC 52 |
| remote paste | agent のみ。既定では禁止 |
| MIME | `text/plain;charset=utf-8` のみ |
| 通常上限 | 1 MiB |
| OSC 52 上限 | 100 KiB |
| 構成ファイル | v1 では持たず、CLI option と内部環境変数のみ |
| Windows の準備 | common interface と protocol は OS 非依存。Windows transport は未決定 |

---

## 4. ユーザー向け CLI

### 4.1 コマンド一覧

```text
kclip copy
    [--backend=auto|session|system|osc52]
    [--max-bytes=<bytes>]

kclip paste
    [--backend=auto|session|system]
    [--max-bytes=<bytes>]

kclip ssh
    [--copy=allow|deny]
    [--paste=deny|allow]
    [--ssh=<path>]
    [--remote-kclip=<command>]
    <destination>
    [-- <ssh-options...>]

kclip doctor [--json]

kclip shim install
    [--dir=<path>]
    [--force]

kclip version
```

`_session` は内部コマンドであり、通常の help には表示しない。

```text
kclip _session
    --endpoint=<unix-socket>
    --nonce=<32-hex-digits>
    --capabilities=<copy[,paste]>
```

### 4.2 `copy`

- stdin を EOF まで読む。
- UTF-8 とサイズ上限を検証する。
- 成功時は stdout に何も書かない。
- 改行を追加・削除しない。
- `--backend=auto` の選択規則は後述する。
- stdin が TTY の場合も読み取りを開始する。誤操作防止の対話プロンプトは出さない。

例:

```sh
printf 'hello' | kclip copy
cat README.md | kclip copy
```

### 4.3 `paste`

- クリップボードの bytes を stdout にそのまま出す。
- 末尾改行を追加しない。
- 診断メッセージは stderr のみに出す。
- stdout への書き込み失敗、たとえば downstream の終了による `EPIPE` は、Unix CLI として自然に終了する。

例:

```sh
kclip paste > note.txt
value="$(kclip paste)"
```

### 4.4 `ssh`

v1 の構文では destination を `kclip` が明示的に受け取り、`--` より後ろを OpenSSH option として destination より前に配置する。

```sh
kclip ssh dev@example.com
kclip ssh --paste=allow dev@example.com -- -J bastion -p 2222
kclip ssh dev -- -i ~/.ssh/id_ed25519 -F ~/.ssh/config
```

内部では概ね次の形で OpenSSH を起動する。

```sh
ssh \
  -o ExitOnForwardFailure=yes \
  -o StreamLocalBindMask=0177 \
  -o StreamLocalBindUnlink=yes \
  -o RequestTTY=force \
  -o 'RemoteCommand=exec kclip _session ...' \
  -R /tmp/kclip-r-SESSION.sock:/tmp/kclip-UID/a-SESSION.sock \
  <user-options...> \
  <destination>
```

次の SSH option は `kclip ssh` の対話セッションモデルと競合するため拒否する。

- `-T`
- `-N`
- `-W`
- `-s`
- `-o RemoteCommand=...`
- `-o RequestTTY=...`
- 明示的な remote command

`-R` はユーザー指定分との併用を許可する。ただし `kclip` 自身が生成した socket path と同一の指定は拒否する。

`--remote-kclip` は既定で `kclip`。空白を含む shell fragment は受け付けず、単一 command name または絶対 path のみを許可する。

```sh
kclip ssh --remote-kclip=/home/dev/.local/bin/kclip dev@example.com
```

### 4.5 paste 権限

既定値:

```text
copy  = allow
paste = deny
```

リモートホストからローカルクリップボードを読む paste は、セッション開始時に明示的に許可する。

```sh
kclip ssh --paste=allow dev@example.com
```

許可はその SSH セッションだけに有効で、永続 trust store は v1 では持たない。

コピーも無効化できる。

```sh
kclip ssh --copy=deny dev@example.com
```

これは、信頼度の低いホストへ接続する際の clipboard poisoning 対策になる。

### 4.6 `pbcopy` / `pbpaste` shim

`argv[0]` に依存した multi-call binary にはしない。Kotlin の common entry point から実行ファイル名を安定して取得するための platform 特有コードを増やす価値が低いため、POSIX shell wrapper を生成する。

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
    SESSION,
    SYSTEM,
    OSC52,
}

data class RuntimeContext(
    val session: SessionDescriptor?,
    val isSshSession: Boolean,
    val hasControllingTty: Boolean,
    val environment: Map<String, String>,
)
```

### 5.2 `auto` の規則

#### copy

1. `KCLIP_ENDPOINT` などから妥当な session descriptor が得られたら session backend
2. session descriptor がなく、SSH セッションかつ controlling TTY があれば OSC 52
3. それ以外は local system backend

session metadata が存在するのに agent 接続に失敗した場合は、OSC 52 へ黙って fallback しない。セッション破損を隠さず、接続エラーとして返す。

#### paste

1. 妥当な session descriptor があれば session backend
2. SSH セッションだが session descriptor がなければエラー
3. それ以外は local system backend

通常の SSH 接続で remote desktop の `DISPLAY` や `WAYLAND_DISPLAY` が偶然設定されていても、`auto` paste がリモート側 GUI のクリップボードを読まないようにする。

エラーメッセージ例:

```text
kclip: paste requires a kclip-managed SSH session
hint: reconnect with: kclip ssh --paste=allow <destination>
```

### 5.3 明示指定

- `--backend=session`: session metadata がなければ失敗
- `--backend=system`: 現在の OS のクリップボードを操作
- `--backend=osc52`: copy のみ。paste では CLI parse error
- `--backend=auto`: 上記規則

---

## 6. 全体アーキテクチャ

```text
┌──────────────────────────── local macOS/Linux ────────────────────────────┐
│                                                                           │
│  kclip ssh                                                                │
│  ┌──────────────────────┐       ┌──────────────────────────────────────┐  │
│  │ AgentServer          │       │ OpenSSH child                        │  │
│  │                      │       │                                      │  │
│  │ Unix socket listener │◀──────│ reverse forwarding                   │  │
│  │ Protocol v1          │       │ remote.sock -> local.sock            │  │
│  │ Permission policy    │       │ RemoteCommand=kclip _session         │  │
│  └──────────┬───────────┘       └───────────────────┬──────────────────┘  │
│             │                                       │                     │
│             ▼                                       │ encrypted SSH       │
│  ┌──────────────────────┐                           │                     │
│  │ ClipboardBackend     │                           │                     │
│  │ pbcopy / wl-copy /   │                           │                     │
│  │ xclip / xsel         │                           │                     │
│  └──────────────────────┘                           │                     │
└─────────────────────────────────────────────────────┼─────────────────────┘
                                                      │
┌──────────────────────────── remote Unix ────────────┼─────────────────────┐
│                                                     ▼                     │
│  login shell started by kclip _session                                    │
│  KCLIP_ENDPOINT=unix:/tmp/kclip-r-....sock                                │
│  KCLIP_NONCE=...                                                          │
│  KCLIP_CAPABILITIES=copy[,paste]                                          │
│                                                                            │
│  printf ... | kclip copy             kclip paste                           │
│              │                             │                               │
│              └──────── Protocol v1 ────────┘                               │
│                           over Unix socket                                 │
└────────────────────────────────────────────────────────────────────────────┘
```

### 6.1 プロセス寿命

- `kclip ssh` が親プロセス。
- OpenSSH は子プロセス。
- agent は親プロセス内の event loop。
- SSH が終了すると agent も停止し、local socket を削除する。
- 常駐プロセス、pid file、background service は作らない。
- `kclip _session` は環境変数を設定した後、login shell に `exec` する。リモート側に wrapper process を残さない。

### 6.2 データ経路

copy:

```text
remote stdin
  -> kclip copy
  -> protocol COPY request
  -> SSH reverse forwarding
  -> local AgentServer
  -> local ClipboardBackend.copy()
```

paste:

```text
local ClipboardBackend.paste()
  -> local AgentServer
  -> protocol PASTE response
  -> SSH reverse forwarding
  -> remote kclip paste
  -> remote stdout
```

OSC 52 copy:

```text
remote stdin
  -> kclip copy
  -> Base64
  -> /dev/tty
  -> terminal emulator
  -> local clipboard
```

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

以下は構成を示すための骨格であり、repository 作成時に Kotlin 2.4 系の実際の DSL で compile を通して固定する。

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
                implementation("com.github.ajalt.clikt:clikt:5.1.0")
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

    data class ProtocolFailure(
        override val message: String,
        override val detail: String? = null,
    ) : KclipError {
        override val exitCode: Int = ExitCodes.PROTOCOL
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

`detail` に clipboard 本文、nonce、認証情報を入れてはならない。

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
- NUL byte は許可する。`text/plain` の consumers が扱えない場合は backend error とする
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
```

unsupported operation は `BackendUnavailable` ではなく、capability mismatch として内部で検出する。通常、resolver は capability を満たす backend だけを返す。

```kotlin
interface ClipboardBackendResolver {
    fun resolve(
        operation: ClipboardOperation,
        preference: BackendPreference,
        context: RuntimeContext,
    ): Outcome<ClipboardBackend>
}
```

### 8.4 platform services

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
    val secureRandom: SecureRandom,
    val signalMailboxFactory: SignalMailboxFactory,
    val systemClipboardFactory: SystemClipboardFactory,
)

expect fun createPlatformServices(): PlatformServices
```

`expect/actual` はこの factory に限定する。各サービス自体は通常の common interface とし、fake を差し込めるようにする。

### 8.5 application use cases

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

class StartSshSessionUseCase(
    private val services: PlatformServices,
    private val sshCommandBuilder: SshCommandBuilder,
    private val agentFactory: AgentFactory,
) {
    fun execute(options: SshOptions): Outcome<ExitStatus>
}
```

CLI classesは option parsing と表示だけを担い、use case に platform API を直接持ち込まない。

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

## 11. OSC 52 backend

### 11.1 対象

v1 では copy のみ。

```kotlin
class Osc52ClipboardBackend(
    private val terminal: Terminal,
    private val base64: Base64Codec,
    private val maxBytes: Int = 100 * 1024,
) : ClipboardBackend
```

### 11.2 出力

clipboard selector `c` と String Terminator を使う。

```text
ESC ] 52 ; c ; BASE64(payload) ESC \
```

Kotlin の概念コード:

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
- stdout は空のままなので pipeline を壊さない
- Base64 後の長さも計算してから buffer を確保する
- 100 KiB を既定上限にする。端末・multiplexer ごとの制限差が大きいため、agent/system の 1 MiB より小さくする
- OSC 52 sequence 自体を log に出さない

Kotlin stdlib の Base64 API に opt-in annotation が必要な間は、利用を `KotlinBase64Codec` の1ファイルに隔離する。

```kotlin
interface Base64Codec {
    fun encode(bytes: ByteArray): String
}
```

RFC 4648 の test vector と、0〜3 byte 境界、large payload を unit test する。

### 11.3 tmux

v1 は controlling TTY に標準 OSC 52 sequence を送るだけとする。tmux の passthrough 用 DCS wrapping は実装しない。

`doctor` は `$TMUX` を検出し、可能な範囲で次を診断する。

- `set-clipboard`
- terminfo の `Ms`
- 外側 terminal の OSC 52 設定

ただし terminal clipboard を実際に変更しない限り完全な疎通確認はできないため、結果は `supported` ではなく `likely` / `unknown` と表現する。

### 11.4 paste/query を行わない理由

OSC 52 query は端末からの応答を読み取る必要がある。

- raw terminal mode の一時設定
- user input と terminal response の区別
- timeout と partial response
- tmux/screen の多段化
- terminal ごとの read permission
- clipboard 内容を untrusted remote process に返すことの明示性

これらを v1 の agent path と並行して実装する価値は低い。paste は `kclip ssh --paste=allow` に一本化する。

---

## 12. session descriptor

### 12.1 型

```kotlin
enum class SessionCapability(val wireBit: Int) {
    COPY(1 shl 0),
    PASTE(1 shl 1),
}

sealed interface IpcEndpoint {
    data class UnixSocket(val path: String) : IpcEndpoint

    // v1 では生成しない。Windows での選択肢を common model に残す。
    data class LoopbackTcp(
        val host: String,
        val port: UShort,
    ) : IpcEndpoint

    data class WindowsNamedPipe(
        val name: String,
    ) : IpcEndpoint
}

class SessionNonce private constructor(
    private val bytes: ByteArray,
) {
    fun toHex(): String
    fun matches(candidate: ByteArray): Boolean
    fun copyBytes(): ByteArray

    companion object {
        fun generate(random: SecureRandom): SessionNonce
        fun parseHex(value: String): Outcome<SessionNonce>
    }
}

data class SessionDescriptor(
    val protocolVersion: UByte,
    val endpoint: IpcEndpoint,
    val nonce: SessionNonce,
    val capabilities: Set<SessionCapability>,
)
```

`SessionNonce` は 128 bit。`ByteArray` の参照 equality を避けるため value class にせず、defensive copy と constant-time comparison を実装する。

### 12.2 環境変数

`kclip _session` が login shell に設定する。

```text
KCLIP_PROTOCOL=1
KCLIP_ENDPOINT=unix:/tmp/kclip-r-<session-id>.sock
KCLIP_NONCE=<32 lowercase hex digits>
KCLIP_CAPABILITIES=copy
```

paste 許可時:

```text
KCLIP_CAPABILITIES=copy,paste
```

parser の規則:

- 不明 protocol は session descriptor として採用せず、明示的な version mismatch error
- endpoint path に NUL を許可しない
- nonce は正確に32桁の lowercase/uppercase hex
- capability の未知値は無視せず error。設定事故を早期発見するため
- いずれか一つでも session 変数が欠けた partial state は error
- diagnostics で nonce の値を表示しない

---

## 13. agent wire protocol v1

### 13.1 方針

- 1 connection = 1 request + 1 response
- network byte order、big-endian
- 固定長 header
- body は raw UTF-8
- JSON/Base64 は clipboard payload に使わない
- protocol version と application version を分離
- length を検証してから allocation
- unknown field は v1 では fail closed

### 13.2 request header

32 bytes:

| Offset | Size | Field | 内容 |
|---:|---:|---|---|
| 0 | 4 | magic | ASCII `KCLP` |
| 4 | 1 | version | `1` |
| 5 | 1 | opcode | COPY=`1`, PASTE=`2`, PING=`3` |
| 6 | 2 | flags | v1 は `0` |
| 8 | 16 | nonce | session nonce |
| 24 | 4 | payloadLength | unsigned 32-bit、big-endian |
| 28 | 4 | reserved | `0` |

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
| 9 | INTERNAL |

### 13.4 body

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
capabilities=copy,paste
```

PING payload は最大 1 KiB。未知 key は無視できる。

error response body は利用者向けの短い UTF-8 message で、最大 4 KiB。stack trace、nonce、clipboard 本文を含めない。

### 13.5 codec interface

```kotlin
data class AgentRequest(
    val version: UByte,
    val operation: AgentOperation,
    val nonce: SessionNonce,
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

### 13.6 parser の必須防御

- magic 不一致で即時切断
- version 不一致は `VERSION_UNSUPPORTED`
- flags/reserved が non-zero なら `BAD_REQUEST`
- payload length が operation の上限を超えたら body を確保せず `TOO_LARGE`
- PASTE/PING request body が non-empty なら `BAD_REQUEST`
- EOF、short read、timeout を区別
- request timeout を connection 全体に適用し、各 read で更新しない
- response header を送れない場合は静かに connection close
- nonce 比較より前に高コスト処理をしない
- 同一 connection から2件目を読まない

---

## 14. IPC abstraction

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

### 14.2 local socket path

local:

```text
/tmp/kclip-<uid>/a-<16 hex>.sock
```

要件:

- parent directory mode `0700`
- directory owner が current UID でなければ使用しない
- socket mode `0600`
- path length を `sockaddr_un.sun_path` の platform limit 未満に検証
- symlink を辿らず、既存 path があれば session ID を再生成
- bind 後に `chmod(0600)`
- process 終了時に socket file を unlink
- directory が空なら best-effort で削除

local directory が `/tmp` 配下なのは path 長を短くするためである。`$XDG_RUNTIME_DIR` は Linux では好適だが macOS との一貫性がなく、長い path が Unix socket 上限に触れやすいため、v1 の既定にはしない。

### 14.3 remote socket path

```text
/tmp/kclip-r-<32 hex>.sock
```

- session ID は secure random 128 bit
- OpenSSH に `StreamLocalBindMask=0177` を渡す
- `StreamLocalBindUnlink=yes` を渡し、同名 stale path があれば bind 前に unlink させる
- random path により複数セッションを分離する
- remote path は shell-safe character のみにする

OpenSSH/sshd が異常終了して stale path が残っても、新しい session は別 path を使う。v1 では remote cleanup daemon は持たない。

---

## 15. agent server

### 15.1 policy

```kotlin
data class AgentPolicy(
    val allowCopy: Boolean = true,
    val allowPaste: Boolean = false,
    val maxCopyBytes: Int = 1 * 1024 * 1024,
    val maxPasteBytes: Int = 1 * 1024 * 1024,
    val requestTimeout: Duration = 5.seconds,
    val clipboardCommandTimeout: Duration = 5.seconds,
)
```

### 15.2 server

```kotlin
class AgentServer(
    private val listener: IpcListener,
    private val codec: AgentProtocolCodec,
    private val nonce: SessionNonce,
    private val clipboard: ClipboardBackend,
    private val policy: AgentPolicy,
    private val sshChild: ChildProcess,
    private val signalMailbox: SignalMailbox,
    private val clock: MonotonicClock,
    private val logger: Logger,
) {
    fun run(): Outcome<ExitStatus>
}
```

### 15.3 event loop

single-threaded `poll()` loop:

```text
while true:
    poll(
        listener fd,
        signal self-pipe fd,
        timeout for child check
    )

    if signal:
        handle termination request

    if listener readable:
        accept one connection
        handle exactly one request with deadline
        close connection

    if ssh child exited:
        cleanup
        return ssh exit status
```

`SIGCHLD` handler は async-signal-safe な `write()` だけを self-pipe に行う。実際の `waitpid(..., WNOHANG)` は event loop で実行する。

clipboard command が数秒間 block しても interactive SSH の stdin/stdout は OpenSSH child が直接扱うため、shell 自体は動き続ける。2件目の clipboard request は待つ。v1 ではこの単純性を優先し、connection ごとの thread は作らない。

### 15.4 signal

- OpenSSH child は親と同じ foreground process group で起動する。
- `SIGWINCH` と terminal input は OpenSSH に直接届く。
- 親は `SIGTERM`、`SIGHUP`、`SIGQUIT`、`SIGCHLD` を self-pipe へ通知する。
- `SIGINT` は親では無視し、foreground group 内の OpenSSH に処理させる。
- 外部から親だけに終了 signal が届いた場合、OpenSSH child へ同じ signal を転送する。
- grace period 後も終了しなければ `SIGTERM`、最後に `SIGKILL`。
- signal handler 内で Kotlin allocation、logging、socket cleanup を行わない。

### 15.5 exit status

`kclip ssh` は原則として OpenSSH child の status を返す。

- normal exit: child exit code
- signal exit: `128 + signal`
- OpenSSH 起動前の setup failure: kclip 固有 exit code
- agent internal failure: SSH を終了させ、kclip internal/protocol code
- local clipboard request failureだけでは SSH session を終了しない

---

## 16. SSH session startup

### 16.1 起動フロー

1. CLI option を検証
2. stdin/stdout が対話 TTY であることを確認
3. `ssh` executable を解決
4. local clipboard backend を解決
5. session ID と nonce を生成
6. local Unix socket listener を作成
7. remote socket path を生成
8. `RemoteCommand` を構築
9. OpenSSH argv を構築・検証
10. OpenSSH child を spawn
11. agent event loop を実行
12. child exit 後、socket と signal handler を cleanup
13. child status を返す

local clipboard backend が unavailable なら、SSH を起動する前に失敗させる。セッション開始後に初めて判明するより、利用者に明確である。

### 16.2 command builder

```kotlin
data class SshSessionSpec(
    val destination: String,
    val sshExecutable: String,
    val userSshOptions: List<String>,
    val remoteKclipCommand: String,
    val localEndpoint: IpcEndpoint.UnixSocket,
    val remoteEndpoint: IpcEndpoint.UnixSocket,
    val nonce: SessionNonce,
    val capabilities: Set<SessionCapability>,
)

interface SshCommandBuilder {
    fun build(spec: SshSessionSpec): Outcome<SpawnSpec>
}
```

生成する argv の概念形:

```text
ssh
-o
ExitOnForwardFailure=yes
-o
StreamLocalBindMask=0177
-o
StreamLocalBindUnlink=yes
-o
RequestTTY=force
-o
RemoteCommand=exec 'kclip' '_session' '--endpoint=...' '--nonce=...' '--capabilities=...'
-R
<remote-socket>:<local-socket>
<user-options...>
<destination>
```

`SpawnSpec` の argv は各 token を分離し、local shell を通さない。

### 16.3 remote command quoting

OpenSSH の `RemoteCommand` は remote shell の解釈境界を通る。専用 encoder を使う。

```kotlin
interface PosixShellWordEncoder {
    fun encode(word: String): Outcome<String>
}
```

実装は単一引用符方式とする。

```text
abc        -> 'abc'
a'b        -> 'a'"'"'b'
empty      -> ''
```

さらに次を制限する。

- `remoteKclipCommand`: command name または absolute path の1 word
- endpoint: `/tmp/` + `[a-z0-9-]+`
- nonce: `[0-9a-f]{32}`
- capabilities: `copy` または `copy,paste`

入力制限と shell quoting を両方行う。どちらか一方だけに依存しない。

### 16.4 SSH option policy

```kotlin
interface SshOptionPolicy {
    fun validate(options: List<String>): Outcome<Unit>
}
```

validator は少なくとも以下を認識する。

- 引数を取る short option
- `-oKey=Value`
- `-o`, `Key=Value`
- `-J`, `-p`, `-i`, `-F`, `-L`, `-R`, `-D`
- no-argument short option の結合形

未知 option は「たぶん pass-through」せず、v1 では安全側に倒してエラーにする。利用者は issue を報告でき、次 release で allowlist を追加する。

destination 後の remote command は構文上指定できない。destination は `kclip ssh` の positional parameter であり、追加 SSH option は `--` 後ろだけである。

### 16.5 `_session`

```kotlin
class RemoteSessionCommand(
    private val environment: MutableEnvironment,
    private val userAccount: UserAccountLookup,
    private val processImage: ProcessImage,
) {
    fun execute(options: RemoteSessionOptions): Nothing
}
```

処理:

1. endpoint、nonce、capability、protocol を検証
2. `KCLIP_*` を environment に設定
3. `getpwuid_r(getuid())` から login shell を得る
4. shell が空または実行不能なら `/bin/sh`
5. login shell として `execve`

argv の概念:

```text
argv[0] = "-" + basename(shell)
```

leading `-` により login shell として起動する。`_session` process は残らないため、shell の exit status がそのまま SSH の status になる。

`kclip` が remote PATH にない場合は RemoteCommand が失敗する。エラーには次を案内する。

```text
Install kclip on the remote host, or specify:
  kclip ssh --remote-kclip=/absolute/path/to/kclip <destination>
```

---

## 17. session client backend

```kotlin
class SessionClipboardBackend(
    private val descriptor: SessionDescriptor,
    private val ipc: LocalIpc,
    private val codec: AgentProtocolCodec,
    private val clock: MonotonicClock,
    private val timeout: Duration = 5.seconds,
) : ClipboardBackend
```

copy:

1. descriptor に COPY capability があるか確認
2. endpoint に接続
3. COPY request header/body を送信
4. response を読む
5. status を `KclipError` に変換
6. connection close

paste:

1. descriptor に PASTE capability があるか確認
2. endpoint に接続
3. body なし PASTE request
4. response header の length を上限検証
5. body を読み、UTF-8 検証
6. stdout へ返す

agent に到達できない場合:

```text
kclip: the clipboard agent for this SSH session is unavailable
detail: connection to the session socket failed
hint: reconnect using kclip ssh
```

socket path や OS error は `--verbose` 時のみ表示し、nonce は常に非表示。

---

## 18. セキュリティ設計

### 18.1 threat model

守る対象:

- ローカルクリップボードの内容
- SSH セッションを通る clipboard payload
- paste permission の意図
- 別セッションとの accidental cross-talk
- oversized/slow request による agent resource exhaustion
- command injection

想定する攻撃者:

- 接続先で別 UID として動く process
- network 上の observer
- malformed protocol client
- clipboard command の異常動作

v1 が防御しないもの:

- 接続先で同一 UID を完全に掌握した process
- 接続先 root
- ローカル同一 UID または local root
- OpenSSH、terminal emulator、OS clipboard service 自体の侵害
- 利用者が明示的に paste を許可した hostile host からの clipboard read

### 18.2 security properties

- 通信は OpenSSH tunnel 内
- local socket directory `0700`
- local socket `0600`
- remote forwarding socket は owner-only mask
- session ごとに 128-bit random path と nonce
- paste は default deny
- copy も option で deny 可能
- request body は operation ごとに上限
- deadline は connection 全体に適用
- payload を log しない
- local command は shell を使わない
- remote shell 境界では単語ごとに quote
- unknown protocol version/flag は fail closed
- host key checking を無効化しない
- SSH config、agent、ProxyJump などは OpenSSH に委ねる

### 18.3 nonce の意味

nonce は暗号学的な session binding と誤接続防止である。SSH 自体の認証を置き換えるものではない。

同じ remote UID の process は、環境や socket path を観測し、active session を利用できる可能性がある。paste を許可した場合、その UID 全体を信頼したとみなす必要がある。この制約は help と README に明記する。

### 18.4 clipboard poisoning

copy 許可済みの remote process は、ローカル clipboard を任意の文字列で上書きできる。秘密情報の窃取ではないが、暗号資産 address、shell command、URL の差し替えにつながる。

対策:

- copy capability を session 単位で `--copy=deny` にできる
- clipboard 変更通知は v1 では出さない。頻繁な操作で UX を損なうため
- 将来 `--copy=ask` を検討
- control character を含む payload も clipboard data としては許すが、log/UI へ直接表示しない

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

禁止 field:

- clipboard body
- Base64 化した body
- session nonce
- paste response
- private key path 以外の SSH secret。原則として argv 全体も出さない

許可 field の例:

```text
operation=copy
backend=session
bytes=421
status=ok
duration_ms=8
session_id_prefix=7a31
```

session ID は必要な場合も先頭4桁まで。既定 log level では session ID を出さない。

### 19.2 `doctor`

人間向け:

```text
$ kclip doctor

kclip
  version: 1.0.0
  platform: macos-arm64
  controlling tty: available

system clipboard
  backend: macos-pbcopy
  copy: available
  paste: available

ssh
  executable: /usr/bin/ssh
  stream-local forwarding: expected
  managed session: no

osc52
  environment: ssh
  tmux: detected
  status: unknown
  note: verify tmux set-clipboard and outer terminal settings
```

managed session 内:

```text
managed session
  protocol: 1
  agent: reachable
  copy: allowed
  paste: denied
```

`doctor` は clipboard を変更せず、paste 内容も読まない。agent の PING だけを使う。

JSON:

```json
{
  "schemaVersion": 1,
  "applicationVersion": "1.0.0",
  "platform": {
    "os": "linux",
    "arch": "x86_64"
  },
  "systemClipboard": {
    "backend": "wayland-wl-clipboard",
    "copy": "available",
    "paste": "available"
  },
  "session": {
    "present": true,
    "reachable": true,
    "protocol": 1,
    "capabilities": ["copy"]
  },
  "osc52": {
    "status": "unknown",
    "tmux": true
  }
}
```

JSON schema は `schemaVersion` で versioning する。field 追加は minor、意味変更・削除は schema version 更新とする。

---

## 20. exit code

| code | 意味 |
|---:|---|
| 0 | success |
| 2 | CLI usage / invalid input |
| 3 | backend unavailable |
| 4 | permission/capability denied |
| 5 | session/protocol failure |
| 6 | payload too large |
| 7 | timeout |
| 8 | subprocess failure |
| 70 | internal software error |

`kclip ssh` は OpenSSH 起動後、原則 OpenSSH の exit code をそのまま返す。kclip 固有 code と SSH code が衝突し得るため、script で厳密に区別したい場合は将来 `--status-json-fd` を検討する。v1 では増やさない。

---

## 21. resource limit と timeout

既定値:

| 対象 | 値 |
|---|---:|
| system/session copy | 1 MiB |
| system/session paste | 1 MiB |
| OSC 52 copy | 100 KiB |
| protocol error body | 4 KiB |
| PING body | 1 KiB |
| clipboard command stderr | 16 KiB |
| agent request deadline | 5秒 |
| clipboard command timeout | 5秒 |
| session connect timeout | 5秒 |
| OSC 52 tty write timeout | 2秒 |

`--max-bytes` は copy/paste の操作上限を既定値より小さくする用途を主とする。既定値より大きくできるとしても hard limit 16 MiB を超えない。OSC 52 の hard limit は 1 MiB とし、通常は 100 KiB の既定を維持する。

protocol header の 32-bit length をそのまま allocation size に使わず、operation limit と Kotlin `Int.MAX_VALUE` の両方を先に比較する。

---

## 22. filesystem と cleanup

### 22.1 local

RAII 相当の scope helper を common 層に用意する。

```kotlin
inline fun <T : KclipCloseable, R> T.useOutcome(
    block: (T) -> Outcome<R>,
): Outcome<R>
```

cleanup 順序:

1. listener close
2. local socket unlink
3. signal handler restore
4. self-pipe close
5. empty local temp directory remove

cleanup error は primary error を上書きしない。成功 path で cleanup だけが失敗した場合は warning とする。

### 22.2 abrupt termination

`SIGKILL`、kernel crash、power loss では cleanup できない。次回起動時に local `/tmp/kclip-<uid>` を検査し、次をすべて満たす socket path だけを削除できる。

- owner が current UID
- file type が socket
- naming pattern が kclip のもの
- connect が `ECONNREFUSED` または path が inactive
- mtime が十分古い

v1 の初期 release では自動削除せず、`doctor` に stale candidate を表示するだけでもよい。安全な判定 test が揃ってから cleanup を有効化する。

---

## 23. Windows を見据えた境界

v1 で Windows 実装を始めないが、次を Unix 固有コードから分離する。

| concern | Unix v1 | Windows 候補 |
|---|---|---|
| clipboard | external commands | Win32 `OpenClipboard` / `CF_UNICODETEXT` |
| process | `posix_spawnp` | `CreateProcessW` |
| local IPC | Unix domain socket | named pipe または loopback TCP |
| polling | `poll` + self-pipe | wait handles / IOCP / dedicated event adapter |
| terminal | `/dev/tty` | console/ConPTY、OSC 52 は terminal capability 次第 |
| signal | POSIX signal | console control handler |
| path | UTF-8 POSIX path | UTF-16 Windows path |
| SSH | OpenSSH executable | Windows OpenSSH executable |

重要なのは、Windows のために v1 の Unix 実装を抽象化し過ぎないことである。以下だけを共通契約として固定する。

- `ClipboardBackend`
- `ProcessSpawner`
- `CommandRunner`
- `LocalIpc`
- `ByteChannel`
- `Terminal`
- `SecureRandom`
- wire protocol
- session capability/policy
- application use cases

`UnixSocket` を agent domain model の前提にはしない。`IpcEndpoint` の variant として扱う。

将来、Windows local + Unix remote の組み合わせでは、local agent を loopback TCP に bind し、OpenSSH forwarding で remote endpoint から接続する方式が候補となる。Windows named pipe を SSH が直接転送できることは設計前提にしない。

Windows clipboard は UTF-16 native API との変換が必要になるが、wire protocol は UTF-8 のまま維持する。

---

## 24. test strategy

### 24.1 common unit test

- `ClipboardPayload` UTF-8 validation
- empty payload
- NUL、emoji、combining character
- 末尾改行を保持
- max size の `N-1`、`N`、`N+1`
- backend resolver の全分岐
- partial `KCLIP_*` environment
- capability parsing
- nonce hex encode/decode と constant-time comparator
- protocol golden bytes
- short header、bad magic、unknown version、non-zero flags
- oversized length を allocation 前に拒否
- COPY/PASTE/PING body rule
- response status mapping
- POSIX shell word quoting
- SSH argv generation
- incompatible SSH option rejection
- exit code mapping
- payload を error/log に含めないこと

### 24.2 Unix integration test

- Unix socket bind/connect
- directory/socket permission
- partial read/write
- peer close
- deadline
- self-pipe wakeup
- `SIGCHLD` と `waitpid`
- child timeout、SIGTERM、SIGKILL
- `posix_spawnp` の stdin/stdout/stderr mapping
- local socket cleanup
- pseudo terminal への OSC 52 exact bytes
- stdout に OSC 52 が漏れないこと

### 24.3 backend test

実 clipboard を汚さない unit/integration test では fake executable を一時 `PATH` に配置し、argv、stdin、stdout、exit status を記録する。

macOS の absolute path backend は command path を production composition では固定し、test composition だけ差し替えられる constructor を用意する。

CI 上で real clipboard session がない場合、command-level integration test と manual test を分離する。

### 24.4 SSH end-to-end

Linux CI:

1. ephemeral OpenSSH server を起動
2. test key と known_hosts を生成
3. local clipboard は fake command backend
4. remote に同じ protocol 対応 `kclip` を配置
5. pseudo terminal 付きで `kclip ssh` を開始
6. remote shell から copy/paste を実行
7. paste deny/allow、version mismatch、agent termination を確認

macOS CI:

- OpenSSH child と local Unix socket forwarding の smoke test
- `pbcopy`/`pbpaste` の real test は clipboard session が得られる runner のみ
- headless runner では fake adapter test

### 24.5 protocol robustness

- random byte stream を decoder に入力
- header length field の全境界
- 1 byte ずつ到着する slow client
- body 未完で切断
- 連続した malformed connection
- bad nonce
- error response write 中の disconnect

Kotlin/Native で利用可能な fuzzing infrastructure が release workflow に適合しない場合も、deterministic seed の property test harness を repository 内に持つ。

### 24.6 manual acceptance matrix

最低限:

- macOS Terminal
- iTerm2
- kitty
- WezTerm
- Linux Wayland + wl-clipboard
- Linux X11 + xclip
- tmux 内外
- ProxyJump
- 複数同時 SSH session
- paste denied/allowed
- Intel Mac artifact は提供期間のみ

OSC 52 は terminal 設定に依存するため、manual matrix の結果を README に「確認済み構成」として記録し、一般的な互換性保証と混同しない。

---

## 25. repository 構成

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
  build.gradle.kts
  settings.gradle.kts
  gradle/
  src/
    commonMain/kotlin/io/github/kclip/
      Main.kt
      cli/
        RootCommand.kt
        CopyCommand.kt
        PasteCommand.kt
        SshCommand.kt
        DoctorCommand.kt
        ShimCommand.kt
        RemoteSessionCommand.kt
      application/
      domain/
      protocol/
      diagnostics/
    unixMain/kotlin/io/github/kclip/
      ipc/
      process/
      signals/
      terminal/
      filesystem/
    macosMain/kotlin/io/github/kclip/
      clipboard/
      PlatformServices.macos.kt
    linuxMain/kotlin/io/github/kclip/
      clipboard/
      PlatformServices.linux.kt
    commonTest/
    unixTest/
    macosTest/
    linuxTest/
  packaging/
    homebrew/
    completions/
  .github/
    workflows/
```

package name は publish group を決めた時点で調整する。OSS 名と package namespace を密結合しない。

---

## 26. build と release

### 26.1 toolchain baseline

設計時点の初期 baseline:

- Kotlin 2.4.0
- Clikt 5.1.0
- Gradle wrapper を repository に固定
- dependency lock/checksum verification を有効化

minor update は CI matrix を通して随時行う。wire protocol version は compiler/library version と独立させる。

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
- protocol golden test unchanged、または protocol version 更新
- dependency license notice
- checksum
- `SECURITY.md`
- `kclip doctor --json` schema compatibility test

---

## 27. compatibility と versioning

### 27.1 application version

Semantic Versioning を使用する。

### 27.2 protocol version

protocol は単一 byte の整数 version。

- 同じ protocol v1 なら application version が異なっても通信可能
- unsupported version は silent fallback せず明示エラー
- v2 を追加する際は client が PING または connection negotiation で共通 version を選べる設計へ拡張
- v1 の initial implementation では negotiation を行わず、完全一致のみ

### 27.3 CLI compatibility

v1.0 以降:

- command/option 削除は major
- default permission を緩める変更は major
- default permission を厳しくする変更は、security release では minor/patch の可能性を許容し CHANGELOG に明記
- JSON doctor schema の破壊変更は schema version 更新
- human-readable diagnostics の文面は stable API としない

---

## 28. 実装フェーズ

### Phase 0: repository と core

- KMP/Kotlin Native build
- target/source set
- Clikt root command
- `Outcome` / `KclipError`
- byte IO、clock、limits
- CI skeleton

### Phase 1: local clipboard

- macOS backend
- Linux discovery
- Wayland/xclip/xsel adapters
- `copy` / `paste`
- shim installer
- local `doctor`

### Phase 2: OSC 52

- Base64 adapter
- `/dev/tty` writer
- size limit
- pseudo-TTY test
- tmux diagnostics

### Phase 3: protocol と Unix IPC

- protocol codec
- Unix socket client/listener
- session descriptor
- session backend
- fake agent integration test

### Phase 4: SSH orchestration

- secure random session IDs
- SSH command builder
- option policy
- `posix_spawnp`
- agent event loop
- signal self-pipe
- `_session`
- OpenSSH E2E test

### Phase 5: hardening と release

- malformed protocol tests
- timeout/resource limits
- logging redaction tests
- packaging
- completion
- SECURITY/README
- Homebrew
- release artifacts

---

## 29. v1 Definition of Done

- [ ] `printf x | kclip copy` と `kclip paste` が macOS で round trip する
- [ ] Wayland + wl-clipboard で round trip する
- [ ] X11 + xclip で round trip する
- [ ] xsel fallback が test 済み
- [ ] bytes と末尾改行を改変しない
- [ ] UTF-8 不正入力と上限超過を安全に拒否
- [ ] 通常 SSH で OSC 52 copy が stdout を汚さない
- [ ] `kclip ssh` 経由の remote copy が local clipboard を更新
- [ ] paste は既定拒否
- [ ] `--paste=allow` の session だけ remote paste が成功
- [ ] 複数 session の socket/nonce が分離
- [ ] agent 終了後に local socket が cleanup
- [ ] malformed/slow client で agent が無制限 allocation/block しない
- [ ] clipboard payload/nonce が log に出ない
- [ ] SSH host key checking を変更しない
- [ ] OpenSSH child の exit code を返す
- [ ] `doctor` が system/session/OSC 52 の状態を説明
- [ ] macosArm64 と linuxX64 が release gate を通る
- [ ] protocol v1 golden test が固定
- [ ] SECURITY.md に threat model と paste の注意点を記載

---

## 30. 将来拡張

優先候補:

1. Windows `mingwX64`
2. rich MIME
3. `--paste=ask` / `--copy=ask`
4. host policy file
5. OSC 52 の terminal-specific compatibility
6. mosh transport
7. persistent local agent
8. remote command mode
9. clipboard access notification
10. protocol negotiation

将来機能は v1 の安全側の既定値を崩さない。特に paste の永続許可は、host alias ではなく SSH host key identity と結び付けられる設計ができるまで導入しない。

---

## 31. 参考資料

- [Kotlin/Native binaries](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html)
- [Kotlin/Native target support tiers](https://kotlinlang.org/docs/native-target-support.html)
- [Kotlin Multiplatform expect/actual](https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html)
- [Kotlin/Native memory manager](https://kotlinlang.org/docs/native-memory-manager.html)
- [Kotlin Base64 API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.io.encoding/-base64/)
- [Clikt](https://github.com/ajalt/clikt)
- [OpenSSH ssh(1)](https://man.openbsd.org/ssh.1)
- [OpenSSH ssh_config(5)](https://man.openbsd.org/ssh_config.5)
- [OpenSSH release notes](https://www.openssh.com/releasenotes.html)
- [xterm control sequences / OSC 52](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html)
- [tmux clipboard integration](https://github.com/tmux/tmux/wiki/Clipboard)
- [wl-clipboard](https://github.com/bugaevc/wl-clipboard)
- [POSIX posix_spawn](https://pubs.opengroup.org/onlinepubs/007904975/functions/posix_spawn.html)
- [Windows Named Pipes](https://learn.microsoft.com/en-us/windows/win32/ipc/named-pipes)
- [Windows Clipboard](https://learn.microsoft.com/en-us/windows/win32/dataxchg/using-the-clipboard)
