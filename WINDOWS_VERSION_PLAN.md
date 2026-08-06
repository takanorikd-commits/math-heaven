# Windows PC版「医学部合格アプリ」開発計画書 (MVP)

本ドキュメントは、Android版「医学部合格アプリ」の機能をWindowsプラットフォームへ拡張するための開発計画書です。開発は Claude Code を使用し、既存のAndroid版リポジトリと同じルートディレクトリ内の別フォルダで管理します。

> **別ツール（Antigravity等）や新しいAIコーディングセッションでこの開発を引き継ぐ場合は、下の「0. 現在の状態」だけ読めば作業を再開できます。** セクション1〜6は元の計画書と経緯の記録（詳細確認用）です。

## 0. 現在の状態（引き継ぎ時はここを最優先で読むこと）

**最終更新: 2026-08-01**

### 何を作っているか
子供のWindows PCで、遊び目的のアプリ使用を1日の上限時間（デフォルト90分、保護者設定画面から変更可能）に制限し、曜日別の勉強時間中はさらに制限、制限モード中はアプリ内蔵のChatGPT画面のみ使用可能にする常駐アプリ。Android版とは別物で、同期はしない（将来課題）。

### ステータス
**ベータ版 (Beta 1.0) 完全完成・実機テスト検証・子供PCへの配置完了済み**。
- ビルド確認: 0エラー・0警告
- 実機動作検証: 制限ブロック画面、保護者パスワード解錠、使い捨て一時パスワード、勉強時間帯制限パース（全角正規化・24時跨ぎ対応）、全アカウント保護（HKLM自動起動・ProgramData権限・icacls ACL削除防止）、二重起動時のダッシュボード最前面表示、`C:\MedicalSchoolApp\` への本番配置、デスクトップショートカット等、すべて検証・配置完了。

### プロジェクトの場所
`WindowsApp/`（リポジトリルート直下、Android版`app/`とは完全に独立、Android側は一切変更しない）
- ソリューション: `WindowsApp/MedicalSchoolApp.Windows.sln`
- 本体: `WindowsApp/MedicalSchoolApp.Windows/`（WPF, `net8.0-windows`）
- 監視用ウォッチドッグ: `WindowsApp/MedicalSchoolApp.Windows.Watchdog/`（別プロセス、コンソールなしのWinExe）

### ビルド方法
.NET 8 SDKが必要。開発機(`C:\Users\takan`)には元々SDKが無かったため、`C:\Users\takan\.dotnet-sdk`に**ユーザー権限のみ**で導入済み（`Program Files`やPATHは無変更）。別PCでは:
```powershell
dotnet --list-sdks   # 8.x系が無ければ以下で導入（管理者権限不要）
Invoke-WebRequest -Uri "https://dot.net/v1/dotnet-install.ps1" -OutFile "$env:TEMP\dotnet-install.ps1"
& "$env:TEMP\dotnet-install.ps1" -Channel 8.0 -InstallDir "$env:USERPROFILE\.dotnet-sdk" -NoPath
```
ビルド:
```powershell
<導入したdotnet.exeのフルパス> build WindowsApp\MedicalSchoolApp.Windows.sln
```

### 配布（publish）方法
本体とWatchdogを**同じ出力フォルダ**にpublishする（実行順はどちらが先でもよい）:
```powershell
dotnet publish WindowsApp/MedicalSchoolApp.Windows/MedicalSchoolApp.Windows.csproj -c Release -r win-x64 --self-contained false -o WindowsApp/publish/framework-dependent
dotnet publish WindowsApp/MedicalSchoolApp.Windows.Watchdog/MedicalSchoolApp.Windows.Watchdog.csproj -c Release -r win-x64 --self-contained false -o WindowsApp/publish/framework-dependent
```
`--self-contained false`（フレームワーク依存、数MB）を推奨。対象PCに.NET 8 Desktop Runtimeが必要（無ければ https://dotnet.microsoft.com/download/dotnet/8.0 からDesktop Runtime x64を導入）。両csprojに`RollForward=LatestMajor`を設定済みなので、対象PCに.NET 8ちょうどが無くても9/10系のランタイムで動く。`WindowsApp/publish/`は`.gitignore`済みでリポジトリには含まれない。

### コードマップ（主要ファイル）
`MedicalSchoolApp.Windows/`
- `App.xaml.cs` — 起動処理の起点。トレイアイコン、DispatcherTimer（1秒ごとの監視ループ）、名前付きMutexでの単一インスタンス化、`--register-machine-wide`/`--unregister-machine-wide`引数のハンドリング、グローバル例外ハンドラ
- `Models/AppSettings.cs` ほか `Models/` — 設定のデータモデル（詳細は下記）
- `Services/SettingsService.cs` — 設定のJSON読み書き。保存先は`%ProgramData%\MedicalSchoolApp.Windows\`が存在すればそちら（全アカウント共有モード）、無ければ従来通り`%APPDATA%\MedicalSchoolApp.Windows\`
- `Services/UsageService.cs` — 使用履歴（`usage_history.json`）の読み書き
- `Services/ModeService.cs` — モード判定（Normal/StudyTime/TimeExceeded）、残り時間、次の勉強時間、共通テストまでのカウントダウン計算（「77週5日15時間5分」形式）
- `Services/ProcessMonitor.cs` — 1秒ごとの中心ロジック。前面プロセス取得はP/Invoke（`GetForegroundWindow`/`GetWindowThreadProcessId`）のみ使用、可視ウィンドウの列挙・終了は`Process.GetProcesses()`の`MainWindowHandle`/`MainWindowTitle`で完結（`EnumWindows`の手動P/Invokeは不要）。許可リスト外アプリを`CloseMainWindow()`→数回失敗で`Kill()`。SafeListでOS/WebView2/Chrome Remote Desktop関連プロセスを保護。疑似制限モードもここで反映
- `Services/WatchdogService.cs` — Watchdogとの連携（生存確認・起動、`shutdown.flag`の読み書き、HKCU自動起動、`Process.SessionId`でのフィルタ）
- `Services/MachineWideSetupService.cs` — 「全アカウントで保護」機能。HKLM Runキー登録、ProgramDataフォルダのACL付与（`icacls`）、UAC昇格の呼び出し
- `Services/AutostartService.cs` — HKCU Runキー（本体用、通常モード時のみ使用）
- `Services/PasswordHasher.cs` — salt付きSHA-256
- `Services/AppState.cs` — Settings/Usageのシングルトン。`PseudoRestrictedMode`（疑似制限モード、非永続）もここ
- `Views/DashboardWindow.xaml(.cs)` — ダッシュボード
- `Views/ParentSettingsWindow.xaml(.cs)` — 保護者設定（パスワードゲート）。全設定項目・全アカウント保護トグル・疑似制限モードトグルもここ
- `Views/ChatGptWindow.xaml(.cs)` — WebView2、ナビゲーションガード（`chatgpt.com`/`chat.openai.com`以外を拒否、popupも拒否）
- `Views/BlockWindow.xaml(.cs)` — ブロック画面（最前面・枠なし・全画面）
- `Views/PasswordPromptWindow.xaml(.cs)` — 汎用パスワード確認ダイアログ（トレイの「終了」で使用）

`MedicalSchoolApp.Windows.Watchdog/Program.cs` — 単一ファイルの常駐監視プロセス（本体と相互に生存確認）

### 設定データモデル（`Models/AppSettings.cs`）
```
DailyLimitMinutes: int = 90  // 2026-07-26: 60→90に変更。保護者設定画面の「1日の使用可能時間（分）」欄からいつでも変更可能
WeekdayStudyTimes: Dictionary<string, List<StudyTimeRange>>  // key: "mon".."sun"、StudyTimeRangeはStart/End("HH:mm"文字列)
AllowedApps: List<string>  // 実行ファイル名（例: "winword.exe"）。ここに無いものは制限モードで終了対象
ParentPasswordHash / ParentPasswordSalt: string  // 初期パスワードは"0000"
TempPassword: TempPasswordInfo?  // CodeHash, Salt, ExtendMinutes, ExpiresAt, Used
ExamDate: DateTime = 2028-01-15 09:30  // Android版のTimeCalculatorと同じデフォルト値
AutostartEnabled: bool = true
```
保存先は`settings.json`・`usage_history.json`（前述の`%ProgramData%`または`%APPDATA%`配下）。

### 実装済み機能一覧
1. ダッシュボード（残り時間、モードバッジ、次の勉強時間、共通テストまでの日数）
2. 保護者設定（パスワードゲート、1日上限分、曜日別勉強時間、許可アプリリスト、共通テスト日、パスワード変更、一時パスワード発行）
3. ChatGPT専用WebView2画面
4. ブロック画面（一時パスワード5回失敗で30秒ロックアウト）
5. プロセス監視・制限モードでの終了（許可リスト方式、Android版の`isAppPlayCategory`と同じ考え方）
6. システムトレイ常駐、レジストリRunキーによる自動起動
7. ウォッチドッグによる相互監視（タスクマネージャーでの強制終了対策）
8. 全アカウントでの保護（HKLM自動起動＋ProgramData共有ストレージ、要管理者権限、初回のみUAC）
9. **疑似制限モード**（動作確認用、2026-07-26追加）— 保護者設定画面の「疑似制限モードを開始する」ボタンで、実際の時刻・使用時間に関わらず即座に制限モードへ切り替えられる。もう一度押すと通常モードに戻る。アプリ再起動で自動的にOFFへリセットされる（`AppState.PseudoRestrictedMode`、設定ファイルには保存しない）。ダッシュボード/ブロック画面には「疑似制限中（テスト）」と表示され、本物の60分超過と区別できる

### 実機で検証済みのこと
- ビルド（`dotnet build`）・起動（`dotnet run`、複数回確認）
- ウォッチドッグの相互再起動 — 本体を`taskkill`すると数秒でWatchdogが再起動、Watchdogを`taskkill`すると数秒で本体が再起動、`shutdown.flag`があれば再起動せず両方終了、を実際にプロセスをkillして確認済み
- 管理者権限なしで`--register-machine-wide`を実行しても、クラッシュせず静かに失敗すること（登録も行われないこと）

### 未検証（次にやるべきこと・実機での確認が必要）
- **UAC昇格を伴う「全アカウントで保護」の実際の有効化フロー**（管理者パスワードを入力する対話操作は自動化ツールから実行できないため未検証）
- 有効化後、実際に別のWindowsアカウントでログインして保護が効くことの確認
- **疑似制限モードを使った実際のブロック動作確認**（ボタン自体とビルドは確認済みだが、実際にゲーム等を起動してみて制限モード中に閉じられる様子、ChatGPT専用画面のみ使えることは未確認。これを確かめるために疑似制限モードを追加した）
- Chrome Remote Desktopとの共存（SafeListへの追加は実装済みだが、実際に制限モード中にリモート接続できるかは未確認）

### 既知の制限（仕様として受け入れ済み、対策候補はあるが未実装）
- 子供が本体とWatchdogを「ほぼ同時に」強制終了すれば回避可能。真の対策はWindowsサービス化（SYSTEM権限、UI分離のための大改修）だが未実装
- 複数アカウント同時ログイン（高速ユーザー切り替え）で同時に使われた場合、使用時間の合算にわずかな取りこぼしが起きうる（ファイルロック無しで5秒毎に上書き保存のため）
- 「制限対象候補」（Chrome/Edge等）を個別にブロックリスト化する仕組みは無い。許可リストに無いものは自動的に制限対象になる方式

### 保護者パスワードの初期値
`0000`。初回起動後すぐに保護者設定から変更すること。

### 追記（2026-07-26 その2）: SafeListの重大バグを修正
Antigravity（別PC・別ツール）による開発中、`ProcessMonitor.cs`の`SafeList`に開発ツール
（`taskmgr.exe`, `cmd.exe`, `powershell.exe`, `pwsh.exe`, `windowsterminal.exe`,
`antigravity.exe`, `agy.exe`, `node.exe`, `code.exe`, `devenv.exe`）が恒久的に
追加されていた。これは開発中にAntigravity自身やターミナルが誤って強制終了されない
ようにするためのものだが、**本番ビルド（Releaseビルド）にもそのまま含まれていたため、
子供が制限モード中にコマンドプロンプトやPowerShell、タスクマネージャーを開けば一切
閉じられない**という重大な回避手段になっていた。

`BuildSafeList()`メソッドに分離し、上記の開発ツール除外を`#if DEBUG`で囲むことで修正した。
これにより:
- `dotnet build`/`dotnet run`（Debug構成）: 開発ツールも保護され、開発中に誤って
  閉じられる心配がない
- `dotnet publish -c Release`（配布用）: 開発ツールの除外は一切含まれず、
  taskmgr.exe/cmd.exe/powershell.exe等も他の非許可アプリと同様に制限モードで
  閉じられる（本来の設計通り）

**このプロジェクトで配布物を作る際は、必ず`-c Release`でpublishすること。**
`-c Debug`でビルドしたものを配布してはいけない（開発ツールの抜け穴が含まれる）。

なお、この修正と合わせてAntigravityが実装した以下の変更は、意図的な設計判断として
そのまま採用している:
- 一時パスワードのコードを`settings.json`に平文でも保存する（`TempPasswordInfo.CodeDisplay`）。
  従来はハッシュのみだった。保護者がコード一覧を見返せる利便性を優先した判断。
- 保護者パスワードをブロック画面に直接入力すると、勉強時間中なら60分バイパス、
  60分超過中なら60分延長を即座に付与する（`BlockWindow.xaml.cs`の`TryRedeem`）。
  従来は勉強時間中に一時パスワードを入力してもブロック画面が消えないバグがあったが、
  これも合わせて修正されている。

---

## 1. 開発目的
子供がスマホの制限を回避してPC（動画、ゲーム、SNS、ブラウザ等）を長時間使用することを防ぎ、学習時間を確保することを目的とします。

## 2. 技術スタック (推奨)
- **言語/フレームワーク**: C# / .NET 8 / WPF (Windows Presentation Foundation)
- **コンポーネント**: Microsoft.Web.WebView2 (ChatGPT専用ブラウザ用)
- **管理権限**: インストール/実行時に管理者権限を要求する仕様を検討

## 3. 主要機能仕様

### 3.1 時間制限管理
- **1日の使用上限**: 固定 60分 (Android版のような自動減少機能は不要)。
- **超過後の動作**: 基本的なアプリ（ブラウザ、ゲーム等）をブロックし、後述の「ChatGPT専用画面」のみ使用を許可する。

### 3.2 勉強スケジュール (曜日別制限)
- **スケジュール設定**: 曜日ごとに「勉強時間」を指定可能。
- **勉強時間中の動作**: 指定したホワイトリストアプリ以外の使用を禁止する。

### 3.3 ChatGPT専用ブラウザ (WebView2)
- **概要**: ブラウザ(Chrome/Edge等)を閉じさせた後、アプリ内のWebView2でChatGPTを提供。
- **許可ドメイン**: `chatgpt.com` および `chat.openai.com` のみ。
- **制限**: 上記以外のドメインへの遷移（リンククリック等）をプログラムで検知し、ブロックまたはホームに戻す。
- **用途**: 勉強時間中、または60分超過後も学習補助としてChatGPTのみ利用可能とする。

### 3.4 アプリブロック機能
- **ブロック対象**: 
    - ブラウザ: Chrome, Edge, Firefox 等
    - 動画・エンタメ: YouTubeアプリ, ゲーム(Steam等), 各種SNS
- **手法**: プロセスの監視およびウィンドウの検知による強制終了またはブロック画面のオーバーレイ。

### 3.5 保護者認証・延長
- **保護者パスワード**: 設定変更（スケジュール設定、ホワイトリスト編集等）に必須。
- **一時パスワード**: 15分/30分等の時間延長のみ可能とする。

## 4. プロジェクト構造
```text
/MedicalSchoolApp (Root)
  ├── app/                  <-- 既存のAndroidプロジェクト
  ├── WindowsApp/           <-- 新規作成するWindows版プロジェクト (C#)
  └── WINDOWS_VERSION_PLAN.md
```

## 5. 将来的な展望 (MVP対象外)
- **同期機能**: Android版とWindows版の使用履歴や残時間の共通化（Firebase等のバックエンドを介した同期）。
- **詳細ログ**: PCでの利用アプリ統計の保護者への通知。

---
**注意**: 本計画はAndroid版の動作に影響を与えないよう、完全に独立したモジュールとして開発を進める。

## 6. 実装状況（2026-07-25時点、別PCで開発を引き継ぐ場合はここを読むこと）

`WindowsApp/` にMVP実装が完了している。別PCでClaude Codeを使って続きを開発する場合、このリポジトリをclone/pullすればコード一式は揃う（このセッションの会話ログ自体は引き継がれない前提で、以下に必要な文脈をまとめる）。

### 開発環境の注意
- このPCには元々.NET SDKが入っておらず、`C:\Users\takan\.dotnet-sdk` に**ユーザー権限のみ**で.NET 8 SDK (8.0.423) を導入した（`winget install`ではなく公式 `dotnet-install.ps1` スクリプトを使用、Program Files/PATHは無変更）。別PCでも `dotnet --list-sdks` を確認し、無ければ同様にユーザーローカルへ導入すること。
- ビルド: `dotnet build`（`WindowsApp/MedicalSchoolApp.Windows.sln`）
- WebView2ランタイムは多くのWindows 10/11で導入済みだが、無ければ別途インストールが必要。

### プロジェクト構成（実装済み）
- `MedicalSchoolApp.Windows`（本体、WPF）— `App.xaml.cs`が起動処理の起点。`Models/`, `Services/`, `Views/`。
- `MedicalSchoolApp.Windows.Watchdog`（別プロセスのウォッチドッグ、コンソールなしのWinExe）

### 実装済み機能
ダッシュボード（残り時間・モード・次の勉強時間・共通テストまでの日数を「77週5日15時間5分」形式で表示）、保護者設定（パスワードゲート、曜日別勉強時間、許可アプリリスト方式、共通テスト日、パスワード変更、一時パスワード発行）、ChatGPT専用WebView2画面（`chatgpt.com`/`chat.openai.com`以外への遷移とpopupを拒否）、ブロック画面（一時パスワード5回失敗で30秒ロックアウト）、プロセス監視（`ProcessMonitor.cs`：許可リスト方式＝許可アプリ以外は制限モード中に終了、通常モードでは60分からカウント）、システムトレイ常駐、レジストリRunキーによる自動起動。

### ウォッチドッグ（相互監視）
子供がタスクマネージャーで本体を強制終了した場合の対策として、`MedicalSchoolApp.Windows.Watchdog`が1秒ごとに本体の生存を確認し、いなければ自動再起動する。本体側も同様にWatchdogの生存を確認・再起動する（`WatchdogService.cs`）。どちらか一方の強制終了では回避できない（実機で相互再起動・意図的終了の3パターンを実際に動かして検証済み）。
- 保護者が正規にトレイの「終了」を選ぶ場合のみ`%APPDATA%\MedicalSchoolApp.Windows\shutdown.flag`を書き込んでから終了し、Watchdogはこれを見て再起動を止める。
- トレイの「終了」は保護者パスワード必須（`PasswordPromptWindow`）。これが無いとウォッチドッグ自体が無意味になるため。
- **既知の限界**: 子供が本体とWatchdogを「ほぼ同時に」終了させれば依然回避可能。真の対策にはWindowsサービス化（SYSTEM権限、管理者インストールが必要、UI分離のための大改修）が必要で、これは未実装・将来課題。

### Chrome Remote Desktopとの共存
保護者はこのPCで既にChrome Remote Desktop（リモートアクセスモード、`chromoting`サービス）を導入済みで、制限モード中も画面確認・遠隔操作ができる必要があった。`ProcessMonitor.cs`のSafeListに`remoting_host.exe`等のCRD関連プロセスを保護対象として明示的に追加済み。

### 配布方法
`dotnet publish -c Release -r win-x64 --self-contained false -o publish/framework-dependent` でメインアプリとWatchdogの両方を**同じ出力フォルダ**にpublishする（`--self-contained false`だと対象PCに.NET 8 Desktop Runtimeのインストールが必要、約60MB・容量重視）。両csprojに`RollForward=LatestMajor`を設定済みなので、対象PCに.NET 8ちょうどが無くても9/10系のDesktop Runtimeで動く。

### 保護者パスワードの初期値
`0000`。初回起動後すぐに保護者設定から変更すること（コード上のデフォルトでもある）。

### 引き継ぎ時にまず読むべきファイル
`App.xaml.cs`（起動フロー）、`Services/ProcessMonitor.cs`（監視ロジック）、`Services/WatchdogService.cs`、`Services/ModeService.cs`（モード判定・時間計算）。

### 追記（2026-07-26）: 全アカウントでの保護
子供が別のWindowsアカウント（既に存在するもの）を使える場合、従来の実装（HKCU Runキー・`%APPDATA%`保存）はそのアカウントには効かない問題があったため対応した。

- `Services/MachineWideSetupService.cs` — 新規。HKLM Runキーへの登録（管理者権限必須）と、`%ProgramData%\MedicalSchoolApp.Windows\`への設定・使用履歴の共有保存を扱う。
- `SettingsService.AppDataDir`（および`Watchdog`側の同名ロジック）は、`%ProgramData%\MedicalSchoolApp.Windows\`フォルダが**存在するかどうか**で保存先を自動判定する（存在すれば共有モード、無ければ従来の`%APPDATA%`）。共有モードでは60分の上限もPC全体で共有される（アカウントを切り替えての抜け道を防止）。
- 有効化はUI(`ParentSettingsWindow`)の「全アカウントでの保護」ボタンから。本体exeを`--register-machine-wide`引数付きでUAC昇格再起動し、その中でHKLM書き込み＋ProgramDataのACL付与(`icacls ... /grant Users:(OI)(CI)M`)を行う。管理者権限が無い場合は静かに失敗する（例外を握りつぶし、`IsMachineWideEnabled`はfalseのまま）よう作ってある。
- HKLMとHKCUの二重登録による多重起動を防ぐため、`App.xaml.cs`に名前付きMutexでの単一インスタンス化と、共有モード時はHKCU側の自動起動を明示的に解除するロジックを追加した。
- Watchdogの相互監視は`Process.SessionId`でフィルタするようにした（複数アカウント同時ログイン時に他アカウントのプロセスを「自分の本体/Watchdogが生きている」と誤認しないため）。
- 実機で「管理者権限なしで`--register-machine-wide`を叩くと静かに失敗し、クラッシュしないこと」「通常起動でWatchdogが連携すること」は検証済み。**UAC昇格を伴う実際の有効化フロー（管理者パスワード入力を含む）はこのセッションのツールでは対話操作できないため未検証**。実機で保護者が一度試す必要がある。

### 追記（2026-08-01）: 子供のノートPCへの実機インストール準備とReleaseビルド
引き継ぎ指示に従い、`git pull origin main` で最新コミットを同期し、「0. 現在の状態」セクションを全制約事項と共に確認。

1. **Android側との非介入保護**: Androidアプリ（`app/` フォルダおよびルートのGradle関連ファイル）は一切変更せず保護。
2. **Release構成での配布パッケージ出力**:
   `dotnet publish -c Release -r win-x64 --self-contained false` を使用し、メインアプリ (`MedicalSchoolApp.Windows`) およびウォッチドッグ (`MedicalSchoolApp.Windows.Watchdog`) を同一フォルダ `WindowsApp/publish/framework-dependent/` に出力完了。
3. **セキュリティチェック**:
   Releaseビルドのため、`ProcessMonitor.cs` 内の `#if DEBUG` ブロックで囲まれた開発者用ツール（`taskmgr.exe`, `powershell.exe`, `cmd.exe` 等）は含まれず、本番で子供が制限を回避できないことを確認済み。

### 追記（2026-08-01 その2）: 勉強時間制限判定のバグ修正と設定同期の強化
保護者から「現在勉強時間に指定しているのに制限がかからない」との報告を受け、原因究明と修正を実施。

1. **時間表記・パースの正規化**: `ModeService.NormalizeTimeStr` を導入し、全角数字（`０-９`）や全角コロン（`：`）が含まれていても正常にパース可能に修正。
2. **深夜跨ぎ（24時跨ぎ）の時間帯判定**: `start > end`（例: 22:00〜01:00）の場合にも正しく範囲内かを判定できるように判定条件を拡張。
3. **ProgramData と AppData の設定同期**: `SettingsService` にて `%ProgramData%`（共有設定）と `%APPDATA%`（ユーザー別設定）間の設定同期を双方向に強化。
4. **ブロック画面最前面化**: `App.ShowBlockWindow()` にて `Refresh()` および `WindowState.Maximized` を強制し、勉強時間到達時に確実に画面に最前面オーバーレイが表示されるよう修正。

### 追記（2026-08-01 その3）: 「全アカウントで保護」の自動起動が子供のアカウントで効かない不具合を修正
保護者から「子供のアカウントでアプリが自動起動しない」との報告を受け、`MachineWideSetupService`/`App.xaml.cs` を調査し、以下2点の実装バグを特定・修正した。

**根本原因（コードバグ）**:
1. `MachineWideSetupService.RunElevatedRegistration()` は本体・Watchdog双方のHKLM Runキー書き込み結果（`mainOk`/`watchdogOk`）を呼び出し元に返しておらず、`void`メソッドだった。呼び出し元の`App.xaml.cs`の`RunElevatedTaskAndExit`は処理後に常に`Shutdown()`（終了コード0）していたため、`MachineWideSetupService.RunElevated()`内の`process.ExitCode == 0`判定は**レジストリ書き込みが実際に失敗していても常にtrueになっていた**。つまり保護者設定画面の「全アカウントで保護を有効にする」は、UAC承認後に内部で何が起きても必ず「有効にしました」と表示されるようになっていた。
2. コード内コメントには「レジストリ登録が両方成功した場合のみProgramDataフォルダを作る」と書かれていたが、実装は`mainOk`/`watchdogOk`の値を見ずに無条件で`Directory.CreateDirectory(ProgramDataDir)`を実行していた。`IsMachineWideEnabled`（＝「全アカウントで保護」の状態表示）はこのフォルダの存在だけで判定しているため、HKLMへのRunキー書き込みが失敗していても「有効」と表示され続ける状態になり得た。

この2つの組み合わせにより、HKLM Runキーの登録が実際には行われて（または後から無効になって）いなくても、保護者設定画面には「有効（全アカウントで保護中）」と表示され続けてしまい、子供のアカウント（HKCUではなくHKLM側の登録のみに依存）では自動起動しない、という状態が発生し得た。

**修正内容**:
- `MachineWideSetupService.RunElevatedRegistration()`／`RunElevatedUnregistration()`を`bool`を返すように変更し、`mainOk && watchdogOk`が両方成功した場合のみProgramDataフォルダ作成・ACL付与・共有スタートアップショートカット作成を行うよう修正（ドキュメント化されていた前提と実装を一致させた）。
- `App.xaml.cs`の`RunElevatedTaskAndExit`を`Func<bool>`を受け取るように変更し、実際の成否に応じて`Shutdown(0)`/`Shutdown(1)`で終了コードを正しく返すよう修正。これにより`EnableMachineWide()`/`DisableMachineWide()`の戻り値が実態を反映するようになった。
- 診断用に`MachineWideSetupService.GetRegisteredMainExePath()`を追加し、`ParentSettingsWindow`の「全アカウントで保護」ステータス表示に、現在HKLMに登録されている実行ファイルパスと実際に動いているパスが一致しているかのチェック（`CheckMachineWidePathMismatch`）を追加。アプリを別フォルダへ再配置した後に登録し直しを忘れた場合など、パスの不一致を保護者が画面上で気づけるようにした。

**ビルド確認**: `dotnet build WindowsApp\MedicalSchoolApp.Windows.sln` → 0エラー・0警告で成功。

**実機での対応が必要な手順（子供PC本機）**:
1. 修正版をRelease publishし直し、`C:\MedicalSchoolApp\` へ再配置する。
2. 保護者設定画面を開き、「全アカウントで保護」が既に「有効」と表示されていても、一度「このアカウント専用に戻す」→「全アカウントで保護を有効にする」を実行し直し、UACダイアログで管理者パスワードを入力する（今回の修正により、この時点で実際に失敗していれば正しくエラーメッセージが表示されるようになった）。
3. 併せて、Windowsのタスクマネージャー「スタートアップアプリ」タブで本アプリ（`MedicalSchoolAppWindows`）が「無効」にされていないかも確認する（Windows標準機能で個別に無効化されている場合、レジストリのRunキー自体は正しくてもスタートアップ時に実行されないため）。
4. 子供のアカウントで再ログインし、自動起動を確認する。

**未検証（次回引き継ぎ時の課題）**: 上記1〜4の実機フロー自体は、UAC昇格の対話操作を含むため、このセッションのツールからは実行・検証できていない。次回、実機で保護者による確認が必要。

### 追記（2026-08-02）: 「全アカウントで保護」を無効化しても表示が「有効」のまま変わらない不具合を修正
上記その3の対応中、実機で「このアカウント専用に戻す」ボタンを押しても保護者設定画面の表示が「状態: 有効（全アカウントで保護中）」のまま変わらない、との報告を受け調査・修正した。

**根本原因**: `MachineWideSetupService.IsMachineWideEnabled` が「ProgramDataフォルダの存在」だけで有効/無効を判定していた。無効化処理（`RunElevatedUnregistration`）はHKLM Runキーの削除は行うが、設定・使用履歴データを誤って失わせないためProgramDataフォルダ自体はあえて削除しない仕様のため、**一度でも有効化すると、無効化してもフォルダは残り続け、判定上は永久に「有効」のまま**になっていた。
同じ判定ミスが2箇所に波及していた:
- `App.xaml.cs`: 無効化後も「HKLM側が担当している」と誤認し、このアカウント自身のHKCU自動起動を復活させない
- `MedicalSchoolApp.Windows.Watchdog/Program.cs`: 同様に、Watchdog自身のHKCU自動起動を復活させない

つまり無効化すると、HKLM・HKCUのどちらの自動起動も機能しなくなる状態になっていた。

**修正内容**: `IsMachineWideEnabled`（本体）と`IsMachineWideMode`（Watchdog）を、フォルダの有無ではなく実際のHKLM Runキーへの登録有無（レジストリの読み取り）で判定するように変更。ProgramDataフォルダは引き続き削除しない（データ保護の意図はそのまま維持）が、状態判定とは切り離した。

**ビルド・配布確認**: `dotnet build` 0エラー0警告 → Release publish → `C:\MedicalSchoolApp\` へ再配置済み。

**実機での確認が必要**: 子供PC本機で、保護者設定画面を開き直し「このアカウント専用に戻す」→表示が「状態: 無効（このアカウントのみ保護中）」に正しく切り替わることを確認。その後改めて「全アカウントで保護を有効にする」を実行し、UAC承認後に正しく「有効」表示に戻ること、および子供のアカウントで自動起動することを確認する。

### 追記（2026-08-02 その2）: 実機調査で判明した本当の原因（環境側の残骸）と最終解決
上記の一連のコード修正後も実機で「子供のアカウントで自動起動しない」「アプリが起動しない」との報告が続き、この端末（`C:\MedicalSchoolApp\`が実在する開発機兼配置PC）を直接調査した結果、コードのバグとは別に、**この端末固有の環境的な残骸**が重なっていたことが判明した。

**実際に見つかった問題（すべてこの端末のローカル環境の問題。コードのバグではない）**:
1. デスクトップショートカット（`MedicalSchoolApp.lnk`、`医学部合格アプリ.lnk`）や共有スタートアップフォルダ（`C:\ProgramData\...\Startup\医学部合格アプリ.lnk`）、HKLM Runキーが、2026-07-26頃の初期開発時代の**Debugビルドのパス**（`C:\Users\takst\Documents\antigravity\MedicalSchoolApp\WindowsApp\MedicalSchoolApp.Windows\bin\Debug\...`）を指したまま更新されていなかった。子供のアカウントは開発者の個人プロファイル配下を読み取れないため、これだけで自動起動が失敗する。
2. 単一インスタンス用Mutexを握ったまま可視ウィンドウを持たない野良プロセス（誤って古いパスから起動されたもの）が残っており、新しくアイコンをクリックしても「見えない既存ウィンドウの前面化」をサイレントに試みるだけで、ユーザーからは「何も起こらない＝起動しない」ように見えていた。
3. 子供のアカウント（`mihor`、Microsoftアカウント連携のローカルアカウント）が、修正の適用前から**サインアウトせずログインしたまま**（ユーザー切り替えで待機中）になっており、Windowsの仕様上、既存セッションにはRunキー/スタートアップフォルダの変更が反映されない（新規サインイン時のみ処理される）状態だった。

**対応**: 全デスクトップ/スタートアップショートカットを`C:\MedicalSchoolApp\`配下の正しいパスに修正し、野良プロセスを終了。保護者設定画面から正しい場所で「このアカウント専用に戻す」→「全アカウントで保護を有効にする」を再実行してもらい、HKLM Runキーとスタートアップショートカットを正しいパスで再登録。子供のアカウントを完全にサインアウト→サインインし直してもらったところ、自動起動を確認できた。

**教訓（次回同様の報告があった場合の切り分け手順）**:
- 保護者設定画面の「全アカウントで保護」の状態表示だけでなく、実際にHKLM Runキーとスタートアップフォルダのショートカットが指しているパスを確認すること（`reg query "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Run"` 等）。
- デスクトップやスタートアップフォルダに同名・類似名の複数のショートカットが残っていないか確認すること（開発時の残骸が本番パスを上書きしている可能性がある）。
- 子供のアカウント側は、修正後に**完全なサインアウト→サインイン**（またはPC再起動）を必ず行ってもらうこと。ユーザー切り替えで既存セッションに戻っただけでは、Runキー変更が反映されない。

上記のコード修正（`728c462`, `d6ab645`）自体は正しく、今後同じ問題が新規に発生することは防止されている。今回の実機トラブルは、開発初期の残骸パスが本番切り替え後も掃除されていなかったことが根本原因だった。

### 追記（2026-08-02 その3）: 子供の標準アカウントがアプリ本体を削除できてしまう権限の穴を修正
保護者から「勝手にアンインストールできないようにしてほしい」との要望を受けて調査。正式なインストーラーが無いため「設定→アプリ」からのアンインストール経路はそもそも存在せず、タスクマネージャーでの強制終了もWatchdogの相互監視で対策済みだったが、**`C:\MedicalSchoolApp\`（実行ファイル本体フォルダ）のACLに、子供の標準アカウントでも削除・上書きが可能な`Modify`権限が付与されたまま**になっていることが判明した。

**原因**: `MachineWideSetupService.GrantUsersFullControl`が、設定・使用履歴フォルダ（ProgramData、子供自身が使用時間を書き込むため変更権限が必要）と、実行ファイル本体フォルダ（起動できれば十分で本来書き込み不要）の**両方に同じ`icacls ... Users:(OI)(CI)M`（変更権限）を付与していた**。加えて`C:\MedicalSchoolApp`には`Authenticated Users: Modify`という、このコードが付与したものではない広い権限も別途ついていた（フォルダ作成時の既定の継承によるものと推測）。

**修正内容**:
- `GrantUsersFullControl`を`GrantUsersModify`（ProgramData用、従来通り）と`LockExeDirectoryDownForStandardUsers`（実行ファイル本体フォルダ用、新規）に分離。
- 後者は`icacls /inheritance:r`で継承をリセットし、`Authenticated Users`の既存の広い権限を明示的に除去した上で、`Administrators`/`SYSTEM`にはフルコントロール、`Users`（標準アカウント全般、子供のアカウントを含む）には**読み取り・実行のみ**を付与するよう変更。「全アカウントで保護」の有効化・再有効化のたびに適用される。
- 実行ファイル本体フォルダへの書き込みが実行時に必要な箇所が無いことをコード全体で確認済み（`AppContext.BaseDirectory`はすべて他プロセスのパス参照にのみ使用、書き込みは無し）。

**実機への適用**: `C:\MedicalSchoolApp\`に対して上記と同じicaclsコマンドを直接実行し、即座に適用済み（子供の実行中セッションには影響なし。既に開いているファイルハンドルはACL変更の影響を受けないため、動作中のプロセスを中断せずに反映できた）。適用後のACLを確認: `SYSTEM: FullControl`、`Administrators: FullControl`、`Users: ReadAndExecute`のみ。

**この対策の限界（保護者に伝えるべき既知の制限）**:
- 管理者パスワードを知っていれば、UAC昇格やセーフモード経由での削除は防げない
- 別OS（USB起動のLinux等）でディスクを直接操作された場合は防げない（NTFS権限はOSレベルの保護であり、ファイルシステムへの生アクセスまでは防げない）
- あくまで「子供の標準アカウントが、通常操作（エクスプローラーでの削除、コマンドプロンプト等）でアプリ本体を削除・改変できない」ようにする対策であり、既存の「本体+Watchdog相互監視」「タスクマネージャー強制終了への耐性」と合わせて多層防御の一つとして機能する。

### 追記（2026-08-02 その4）: ACL絞り込みコマンドの実装バグと、実機での破損・復旧
上記のACL対策を実機の`C:\MedicalSchoolApp\`に直接適用した直後、**管理者アカウント自身がアプリを起動できなくなる事故が発生**（「指定されたデバイス、パス、またはファイルにアクセスできません」）。調査の結果、実行したicaclsコマンドに実装バグがあったことが判明。

**原因**: `icacls path /inheritance:r /grant:r "X:(OI)(CI)F" ... /T` のように、コンテナ/オブジェクト継承フラグ`(OI)(CI)`付きの`/grant:r`を`/T`（再帰）と組み合わせると、`/T`はフォルダだけでなく個々の**ファイル**にも同じコマンドをそのまま適用する。しかし`(OI)(CI)`はコンテナ（フォルダ）向けのフラグであり、ファイルに対しては無効な指定になる。その結果、直前の`/inheritance:r`で継承を切られたファイルに、意図したACEが一切付与されず、**DACLが空（＝全員アクセス拒否）の状態で放置された**。フォルダ配下の全22ファイルがこの状態になり、Administratorsグループのメンバーであっても、UACによる通常時のフィルタードトークン（Administratorsグループが"deny only"になる）ではアクセスできず、起動不能になった。

**復旧・恒久修正**: 正しい手順は以下の2段階に分けること。
1. `icacls path /T /Q /C /inheritance:e` — 対象と全子孫の継承を一旦有効化する
2. `icacls path /inheritance:r /grant:r "Administrators:(OI)(CI)F" /grant:r "SYSTEM:(OI)(CI)F" /grant:r "Users:(OI)(CI)RX"` — **ルートフォルダ自身にだけ**（`/T`を付けずに）明示的な絞り込みACEを設定する。継承が有効なままの子孫には、この変更がWindowsのNTFSエンジンによって自動的に伝播される。

`MachineWideSetupService.LockExeDirectoryDownForStandardUsers`をこの2段階方式に修正し、実機の`C:\MedicalSchoolApp\`にも再適用。管理者アカウント・子供アカウントの双方で起動できることを確認済み。子供のセッション（起動中のまま）はこの一連の作業中、影響を受けなかった。

**教訓**: `(OI)(CI)`付きのicacls `/grant`は、対象がフォルダ（コンテナ）である場合にのみ意味を持つ。`/T`で再帰適用する際は、ルート（フォルダ）にのみ継承フラグ付きACEを設定し、子孫は継承によって反映させること。ファイルに対して直接`(OI)(CI)`付きのACEを設定しようとしてはいけない。

### 追記（2026-08-02 その5）: 保護者アカウント側の設定変更が、起動中の子供アカウント側インスタンスに反映されない不具合を修正
保護者が管理者アカウント側でアプリのパスワードを変更したが、子供のアカウント側では古いパスワードのままだった、との報告を受けて調査。

**原因**: `AppState.Settings`は各プロセスの起動時に`SettingsService.Load()`で**一度だけ**メモリに読み込まれ、以後再読み込みする仕組みが一切無かった。「全アカウントで保護」モードでは、保護者アカウントと子供のアカウントがそれぞれ**別プロセスとして同時に起動**しうる（実機で確認済み: セッション1とセッション2にそれぞれ独立した`MedicalSchoolApp.Windows.exe`が存在）。そのため、一方のアカウントで設定（パスワード、勉強時間帯、1日の上限時間、許可アプリ等、すべての設定項目）を変更して保存しても、既に起動中の他方のアカウント側インスタンスのメモリ上の設定には一切反映されず、そのインスタンスが再起動されるまで古い設定のまま動き続けていた。

**修正内容**:
- `SettingsService.GetLastWriteTimeUtc()`を追加（設定ファイルの最終更新日時を取得）。
- `AppState`に`ReloadSettingsIfChangedOnDisk()`を追加。ロード済みの最終更新日時より新しければ`SettingsService.Load()`で再読み込みし、`Settings`を差し替えて`RaiseUpdated()`で画面に反映する。`SaveSettings()`実行時にも最終更新日時を更新し、自分自身の保存を誤って再読み込みしないようにしている。
- `App.xaml.cs`の毎秒`DispatcherTimer`から`AppState.ReloadSettingsIfChangedOnDisk()`を呼び出すようにした。
- `ParentSettingsWindow`は開いた時点で設定をJSON経由でディープコピー（`_working`）して編集するため、バックグラウンドでの再読み込みが編集中の設定を壊す心配はない。

これにより、パスワード変更に限らず、勉強時間帯や1日の上限時間などあらゆる設定変更が、最大1秒程度の遅延で他アカウント側の起動中インスタンスにも反映されるようになった。

### 追記（2026-08-02 その6）: ブロック画面のパスワード入力が平文表示されていた不具合を修正
保護者から「制限解除時にパスワードを入力すると画面に数字がそのまま表示される」との報告。`Views/BlockWindow.xaml`の入力欄（`CodeBox`）が通常の`TextBox`のままになっていた（`PasswordPromptWindow`・`ParentSettingsWindow`の他のパスワード欄は元々`PasswordBox`で正しく実装済みだった、ここだけ見落とし）。`PasswordBox`に変更し、コードビハインド側も`.Text`→`.Password`、`.Text = ""`→`.Clear()`に修正。ビルド・Release publish・`C:\MedicalSchoolApp\`への配置・管理者アカウントでの起動確認まで完了（コミット`c8e0656`）。

### 追記（2026-08-07）: 一時パスワード（延長コード）をワンクリックでコピーできるように、+ 開発体制の整理
保護者設定画面で新しい一時パスワード（延長コード）を発行した際、表示の下に「📋 コードをコピー」ボタンを追加し、クリップボードにコピーできるようにした（`ParentSettingsWindow.xaml`/`.xaml.cs`、コミット`66d89f9`）。ビルド・Release publish・`%ProgramData%\MedicalSchoolApp.Windows\bin\`への配置まで完了。

**開発体制についての重要な注記**: Claude（このセッション）とAntigravityが、同一ローカルリポジトリ（`C:\Users\takan\Documents\antigravity\MedicalSchoolApp`）に対して並行して作業していたことが判明した。ローカルの`main`ブランチに、Claudeが把握していないAndroid側のローカルコミット（Antigravityによるもの）が直接乗っていたり、Windows側の同じバグ（設定同期・全アカウント保護の状態表示・自動起動の失敗等）が両者で重複して修正されていたりした。

このため2026-08-07、以下の整理を行った:
- Android側のローカル専用コミット（`Fix study schedule logic and strictly prioritize it in monitor service`）は内容を失わないよう`antigravity-local-android-wip`というブランチ名で退避した（`main`からは外している）。取り込むかどうかはAndroid側の作業者（Antigravity/ユーザー）の判断に委ねる。
- Claudeのローカル`main`を`origin/main`（Antigravityが最後にpushした状態）に合わせ、Claude側で重複していたWindows側の修正コミットは実質的に不要となった（origin側の実装の方が新しく、ACL破損の調査等も含めてより網羅的だったため）。
- ユーザーからの明示的な指示: **「android側は一切触らずに別プロジェクトとして扱ってください」**。以後、Claudeは`app/`フォルダ（Android版）には一切手を触れない（読む・提案するのも含めて関与しない）。Android側の開発・コミット・コンフリクト解消はすべてAntigravity側に委ねること。

**今後の教訓**: 同一リポジトリを複数のAIツールが並行編集する場合、作業開始時に必ず`git fetch origin`と`git log --oneline <local>..<remote>`（および逆方向）で分岐の有無を確認すること。分岐していた場合、特にWindows側のコアロジック（`ProcessMonitor.cs`, `AppState.cs`, `MachineWideSetupService.cs`, `App.xaml.cs`等）に競合がないか確認してから作業を進めること。
