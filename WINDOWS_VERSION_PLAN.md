# Windows PC版「医学部合格アプリ」開発計画書 (MVP)

本ドキュメントは、Android版「医学部合格アプリ」の機能をWindowsプラットフォームへ拡張するための開発計画書です。開発は Claude Code を使用し、既存のAndroid版リポジトリと同じルートディレクトリ内の別フォルダで管理します。

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
