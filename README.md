# kclip

[![CI](https://github.com/matsumo0922/kclip/actions/workflows/ci.yml/badge.svg)](https://github.com/matsumo0922/kclip/actions/workflows/ci.yml)
[![ライセンス: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

`kclip` は、ローカル環境と SSH 接続先の間でテキストクリップボードを扱うための Kotlin/Native 製 CLI です。

通常の `ssh` でログインしたあとに、リモートのシェルを後からローカルのクリップボードエージェントへアタッチできます。リモートからローカルへ直接接続できる必要はありません。ローカル側から OpenSSH の Unix ドメインソケット転送を張り、リモートの `kclip copy` / `kclip paste` をローカルクリップボードへ安全に中継します。

```text
リモートシェル             SSH 逆方向転送                  ローカル環境
----------------           ----------------              ----------------
kclip copy   ----->  /tmp/kclip-*.sock  ========>  アタッチメントエージェント -> pbcopy / wl-copy / xclip
kclip paste  <-----  /tmp/kclip-*.sock  <========  アタッチメントエージェント <- pbpaste / wl-paste / xclip
```

## 現在の状態

`kclip` は現在 `0.1.0-dev` の開発版です。macOS / Linux 向けの Kotlin/Native CLI として、以下の機能を実装しています。

- ローカルクリップボードの `copy` / `paste`
- 既存 SSH セッションに対する `pair` / `attach`
- OpenSSH ControlMaster を使ったアタッチ
- ControlMaster がない場合の専用 SSH サイドカーによるアタッチ
- ローカルアタッチメントの `attachments` / `detach` / `reconnect`
- ローカルクリップボード利用可否を確認する `doctor`
- OpenSSH 転送の統合検証用スパイク / 自己テスト

まだパッケージマネージャーや GitHub Releases による配布はありません。現時点ではソースからビルドして使います。

## なぜ kclip なのか

SSH 先でクリップボードを扱う方法はいくつかありますが、どれも少しずつ不便です。

- `pbcopy` / `pbpaste` は macOS ローカルでは便利だが、リモートにはそのまま届かない
- OSC 52 は端末エミュレーター依存で、特に貼り付けには向かない
- リモートからローカルへ接続させる方式は、ローカル SSH サーバーやファイアウォール設定が必要になりがち
- SSH ラッパーだけにすると、すでに開いている SSH セッションを救えない

`kclip` は、すでに開いている SSH セッションに後付けでクリップボード機能を足すことを中心にしています。

## 特徴

- **テキスト専用のクリップボード**: UTF-8 の `text/plain` を標準入力・標準出力で扱います。
- **既存 SSH セッションへのアタッチ**: リモートで `kclip pair`、ローカルで `kclip attach` を実行するだけです。
- **ローカルへの受信接続は不要**: リモートからローカルへ直接接続しません。転送はローカル側から作ります。
- **paste は明示許可制**: リモートからローカルクリップボードを読む `paste` は、ローカル側の `--paste=allow` が必要です。
- **グローバルデーモンなし**: システム全体の常駐デーモンは起動しません。アタッチメントごとのローカルエージェントだけが動きます。
- **OpenSSH ネイティブの転送**: SSH 認証、ホスト鍵検証、暗号化は OpenSSH に任せます。
- **Kotlin Multiplatform 構成**: domain / protocol / application / platform / agent / cli を分け、Unix native target に載せています。

## 対応環境

| 環境 | ターゲット | クリップボードバックエンド |
|---|---|---|
| macOS Apple Silicon | `macosArm64` | `/usr/bin/pbcopy`, `/usr/bin/pbpaste` |
| Linux x64 | `linuxX64` | Wayland: `wl-copy` / `wl-paste`, X11: `xclip`, 代替: `xsel` |
| Linux arm64 | `linuxArm64` | Wayland: `wl-copy` / `wl-paste`, X11: `xclip`, 代替: `xsel` |

必要なもの:

- ビルド用の JDK 21
- SSH アタッチ用の OpenSSH クライアント
- SSH アタッチ先にある OpenSSH サーバー
- SSH サーバー側で有効化された Unix ドメインソケット転送
- 上の表にあるいずれかのクリップボードバックエンド

## インストール

リポジトリを取得して、ネイティブ実行ファイルをビルドします。

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

`~/.local/bin` が `PATH` に入っていることを確認してください。

```sh
kclip version
kclip doctor
```

`kclip` はローカル環境とリモート環境の両方に必要です。

```sh
ssh dev@example.com 'mkdir -p ~/bin'
scp ~/.local/bin/kclip dev@example.com:~/bin/kclip
ssh dev@example.com 'chmod +x ~/bin/kclip && ~/bin/kclip version'
```

## クイックスタート

### ローカルクリップボード

標準入力の内容をローカルクリップボードへコピーします。

```sh
printf 'kclip からこんにちは' | kclip copy
```

ローカルクリップボードの内容を標準出力へ貼り付けます。

```sh
kclip paste
```

ローカルのクリップボードバックエンドが使えるか確認します。

```sh
kclip doctor
```

macOS での出力例:

```text
kclip
  status: local
  clipboard: available (macos-pbcopy)
```

### 既存 SSH セッションへアタッチする

通常どおり SSH セッションを開くか、すでに開いている SSH セッションを使います。

```sh
ローカル$ ssh dev@example.com
リモート$ kclip pair --paste
```

`kclip pair` は使い捨てのペアリングコードを表示し、ローカルからのアタッチを待ちます。

```text
kclip pairing
code: KC1-6X4P-9Q2K-H7MT-W3DN

ローカル環境で実行:
  kclip attach --pairing-code-stdin --paste=allow <ssh-destination>

ローカルアタッチメントを待機中...
```

別のローカルターミナルで、同じ SSH 接続先へアタッチします。

```sh
ローカル$ printf 'KC1-6X4P-9Q2K-H7MT-W3DN\n' |
  kclip attach --pairing-code-stdin --paste=allow dev@example.com
```

元のリモートシェルに戻ると、自然に `kclip` を使えます。

```sh
リモート$ printf 'リモートからコピー' | kclip copy
リモート$ kclip paste
```

リモートからローカルクリップボードへ書き込むだけでよい場合は、リモート側の `--paste` を省き、ローカル側も `--paste=deny` のままにします。

```sh
リモート$ kclip pair
ローカル$ printf '<ペアリングコード>\n' |
  kclip attach --pairing-code-stdin --paste=deny dev@example.com
```

## コマンドリファレンス

### `kclip copy`

標準入力から UTF-8 テキストを読み、クリップボードバックエンドへコピーします。

```sh
kclip copy [--backend=auto|attachment|system|osc52] [--attachment=<id>] [--max-bytes=<bytes>]
```

例:

```sh
printf 'hello' | kclip copy
printf 'hello' | kclip copy --backend=system
printf 'hello' | kclip copy --backend=attachment
```

`--attachment=<id>` は、完全なアタッチメント ID を知っているスクリプト向けの上級オプションです。対話的な SSH 利用では通常、現在の TTY binding が使われるため、このオプションは不要です。

### `kclip paste`

クリップボードバックエンドからテキストを読み、標準出力へ書き出します。

```sh
kclip paste [--backend=auto|attachment|system] [--attachment=<id>] [--max-bytes=<bytes>]
```

例:

```sh
kclip paste
kclip paste > note.txt
value="$(kclip paste)"
```

SSH 内で実行している場合、`paste --backend=auto` はアタッチメントがない状態でリモート側の GUI クリップボードへ黙ってフォールバックしません。代わりに、pair と attach を行うよう案内します。

### `kclip pair`

リモートシェルから使い捨てのペアリング要求を開始します。

```sh
kclip pair [--paste] [--replace]
```

- `--paste` は、ローカルクリップボードを読む権限を要求します。
- `--replace` は、同じリモート TTY に既存の binding がある場合に置き換えます。

ペアリング要求は現在の controlling TTY に紐づきます。つまり、tmux ペイン、screen ウィンドウ、通常の端末セッションごとに別のアタッチメントを持てます。

### `kclip attach`

ローカル環境で実行し、待機中のリモートペアリング要求をローカルアタッチメントエージェントへ接続します。

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

転送モード:

| モード | 挙動 |
|---|---|
| `auto` | 利用可能な OpenSSH ControlMaster があれば使い、なければ専用 master を起動します。 |
| `controlmaster` | 既存 ControlMaster を必須とし、その接続に転送を追加します。 |
| `dedicated` | アタッチメント専用の private SSH master を必ず起動します。 |

`--control-path` は、`ssh -G` から ControlMaster path を解決できない場合や、特定の master socket を検証したい場合に使います。

### `kclip attachments`

ローカル環境が把握しているアタッチメントを一覧表示します。

```sh
kclip attachments
```

出力例:

```text
KC-A7D2  dedicated  paste=allow  active  dev@example.com
```

### `kclip detach`

指定したアタッチメントのローカルエージェントを停止し、SSH 転送を解除します。

```sh
kclip detach KC-A7D2
```

ControlMaster アタッチメントの場合、`detach` は kclip が追加した転送だけを解除します。既存の SSH master connection は停止しません。

### `kclip reconnect`

既存のローカルアタッチメントに対して SSH 転送を作り直します。

```sh
kclip reconnect KC-A7D2
```

dedicated master が中断された場合や転送経路が degraded になった場合に使います。

### `kclip doctor`

現在の環境を簡潔に診断します。

```sh
kclip doctor
```

現時点の診断は、ローカルクリップボードバックエンドの利用可否を中心にしています。

## セキュリティモデル

`kclip` は、意図しないクリップボード露出を避ける設計です。

- リモートの `copy` は、ローカルクリップボードへ書き込むだけなので既定で許可します。
- リモートの `paste` は、ローカルクリップボードを読むため既定で拒否します。
- `paste` 権限は `kclip attach --paste=allow` によりアタッチメントごとに付与します。
- pairing code は 80 bit の entropy から生成される使い捨ての値です。
- pairing 後のローカルエージェントはアタッチメントごとの random nonce で認証します。
- SSH 認証、暗号化、ホスト鍵検証は OpenSSH に委譲します。
- ローカルエージェントはアタッチメントごとのプロセスで、システム全体の常駐デーモンではありません。
- dedicated transport は kclip が管理する private OpenSSH master を使います。
- ControlMaster transport は、利用前に control socket を検証します。
- stale な remote socket collision は安全側で失敗します。`kclip` は任意の remote path を自動 unlink しません。

重要な制限:

- 同じリモート UID で動くプロセスは、同じ TTY-bound attachment state を利用できる場合があります。
- リモート root は、リモートユーザーの runtime file から隔離されません。
- クリップボード payload は UTF-8 テキストのみです。
- 既定の payload 上限は 1 MiB です。
- 画像、HTML、RTF、複数 MIME type のクリップボード内容は v1 の対象外です。

## アーキテクチャ

このプロジェクトは、protocol、domain、platform の責務を分けるため、小さな KMP モジュールへ分割しています。

| モジュール | 役割 |
|---|---|
| `:core:domain` | 値オブジェクト、エラー、クリップボード抽象、アタッチメント状態 codec |
| `:core:protocol` | バージョン付きアタッチメントエージェント通信プロトコル |
| `:core:application` | copy、paste、pair、attach のユースケース |
| `:core:platform` | Unix IPC、ファイルシステム、プロセス、コマンド、クリップボード連携 |
| `:core:agent` | アタッチメントごとのローカルエージェントとリモートアタッチメントクライアント |
| `:core:diagnostics` | 診断レポートモデル |
| `:cli` | Clikt ベースのコマンドラインエントリーポイント |
| `integration/openssh` | OpenSSH 転送スパイクと自己テストスクリプト |

主要な SSH アタッチ経路は次の流れです。

1. リモートユーザーが `kclip pair` を実行します。
2. リモートの `pair` は使い捨ての code を作り、その code から導出したリモート Unix socket で待機します。
3. ローカルユーザーが code を渡して `kclip attach` を実行します。
4. ローカルの `attach` はローカル Unix socket 上にアタッチメントエージェントを起動します。
5. ローカルの `attach` は、リモートからローカルへ向かう OpenSSH reverse Unix-domain socket forward を追加します。
6. リモートの `pair` は forwarded socket 経由で接続し、protocol handshake を完了します。
7. リモートの `copy` / `paste` は、detach されるまで TTY-bound attachment lease を使います。

詳細な設計は [docs/design-v1.md](docs/design-v1.md) を参照してください。

## 開発

主要なテストを実行します。

```sh
./gradlew :core:domain:allTests \
  :core:application:allTests \
  :core:platform:allTests \
  :core:protocol:allTests \
  :core:agent:allTests \
  :cli:allTests
```

静的解析を実行します。

```sh
./gradlew detekt
```

ローカル macOS 向け実行ファイルをビルドします。

```sh
./gradlew :cli:linkDebugExecutableMacosArm64
```

OpenSSH 統合検証用スパイクを実行します。

```sh
integration/openssh/run-openssh-spike.sh --self-test
```

CI では現在、以下を検証しています。

- macOS のテスト、macOS 実行ファイルリンク、detekt
- Linux x64 のテストとコンパイル
- OpenSSH transport spike self-test

## ロードマップ

近い将来の候補:

- macOS / Linux 向け配布成果物
- インストーラーやパッケージマネージャー向け recipe
- アタッチメントや転送失敗を含む、より詳しい `doctor` 出力
- より使いやすい `kclip ssh` convenience flow
- リモート環境向けの `pbcopy` / `pbpaste` shim install
- tmux、ProxyJump、stale socket 向け troubleshooting document

v1 の非目標:

- クリップボード履歴や自動同期
- 長時間動くグローバルデーモン
- バイナリ / 画像クリップボード payload
- Windows、Android、iOS バイナリ
- OpenSSH server policy の迂回

## ライセンス

MIT License です。詳細は [LICENSE](LICENSE) を参照してください。
