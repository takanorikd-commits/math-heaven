# 申し送りドキュメント: Windows版「医学部合格アプリ」開発引き継ぎ

**最終更新日**: 2026年8月1日  
**対象AIエージェント / 開発者**: Claude Code または別のAIエージェント  
**リポジトリ**: `https://github.com/takanorikd-commits/math-heaven.git` (ブランチ: `main`)

---

## 1. プロジェクトの概要と配置

本アプリは、子供の Windows PC での遊びアプリの使用時間を制限し、勉強時間帯の保護、および ChatGPT 専用ブラウザ（WebView2）による学習支援を提供する常駐型 WPF アプリケーションです。

* **プロジェクトフォルダ**: `WindowsApp/` （ルート直下）
  * メインアプリ: `WindowsApp/MedicalSchoolApp.Windows/`
  * ウォッチドッグ（相互監視）: `WindowsApp/MedicalSchoolApp.Windows.Watchdog/`
* **本番実機設置パス（子供のPC上）**: `C:\MedicalSchoolApp\`
* **重要制約**: 既存の Android版（ルートの `app/` フォルダおよび Gradle 関連ファイル）には**絶対に触れない**こと。Windows版は `WindowsApp/` に完全独立しています。

---

## 2. 現在のステータス（2026-08-01 時点）

**Beta 1.0 完成・バグ修正＆子供用PC実機配置完了済み**

### 2026-08-01 に完了・修正した重要事項
1. **勉強時間制限パースの強化 (`ModeService.cs`)**:
   * 全角数字（`０-９`）や全角コロン（`：`）が含まれていても正常にパースできるよう正規化処理 (`NormalizeTimeStr`) を導入。
2. **深夜0時跨ぎ（24時跨ぎ）の時間帯判定 (`ModeService.cs`)**:
   * `22:00 〜 01:00` のような日を跨ぐ勉強時間設定でも、`start > end` の条件分岐により正しく時間内であるかを判定できるように拡張。
3. **設定ファイル同期 (`SettingsService.cs`)**:
   * 全アカウント保護モードで使われる `%ProgramData%\MedicalSchoolApp.Windows\settings.json` と、ユーザー個別設定 `%APPDATA%\MedicalSchoolApp.Windows\settings.json` 間の設定同期を双方向に自動化。
4. **ブロック画面の描画・最前面化強化 (`App.xaml.cs` / `BlockWindow.xaml.cs`)**:
   * `App.ShowBlockWindow()` 実行時に `WindowState.Maximized` および `Topmost = true` を適用し、ぬる安全ガード (`if (!IsLoaded || ReasonText is null) return;`) を追加。
5. **子供用PC実機への配置**:
   * Release（本番用）構成で publish し、`C:\MedicalSchoolApp\` へバイナリ一式を配置。
   * デスクトップ上に `医学部合格アプリ.lnk` ショートカットを作成。

---

## 3. ビルドおよび配布（publish）コマンド

開発中の動作確認は Debug ビルドで構いませんが、**子供の PC へ配布・配置する際は必ず `-c Release` で publish してください**（Debug ビルドには開発用プロセスの保護解除ロジックが含まれるため）。

```powershell
# メインアプリとWatchdogの両方を同一次元フォルダへRelease publish
dotnet publish WindowsApp/MedicalSchoolApp.Windows/MedicalSchoolApp.Windows.csproj -c Release -r win-x64 --self-contained false -o WindowsApp/publish/framework-dependent
dotnet publish WindowsApp/MedicalSchoolApp.Windows.Watchdog/MedicalSchoolApp.Windows.Watchdog.csproj -c Release -r win-x64 --self-contained false -o WindowsApp/publish/framework-dependent
```

---

## 4. 主なアーキテクチャと機能一覧

* **二重起動の防止と前面化 (`App.xaml.cs`)**:
  * 名前付き Mutex (`Local\MedicalSchoolAppWindows_SingleInstance`) および `EventWaitHandle` を使用。二重起動を試みた場合は常駐中のダッシュボードを前面表示。
* **プロセス監視・制限ブロック (`ProcessMonitor.cs`)**:
  * 1秒周期のタイマーで動作。`SafeList`（OS標準プロセス、WebView2、Chrome Remote Desktop）以外のアプリを、制限モード中に自動終了 (`CloseMainWindow` → `Kill`)。
  * `#if DEBUG` ブロックで開発ツール (`taskmgr.exe`, `cmd.exe`, `powershell.exe`, `code.exe`, `antigravity.exe` 等) の保護を本番環境から安全に隔離。
* **相互監視ウォッチドッグ (`WatchdogService.cs` / `Watchdog/Program.cs`)**:
  * タスクマネージャー等による強硬なプロセス終了を防ぐため、本体と Watchdog が相互に生存確認し、相手が消えていれば数秒で再起動。
  * 正規終了（保護者パスワード認証済み）時のみ `shutdown.flag` を作成して相互停止。
* **全アカウントでの保護 (`MachineWideSetupService.cs`)**:
  * UAC 昇格 (`--register-machine-wide`) 経由で HKLM Run キーの自動起動を登録し、`%ProgramData%\MedicalSchoolApp.Windows\` 共有フォルダのアクセス権を `icacls` で削除防止制御。

---

## 5. 引き継ぎ後の推奨タスク（次のステップ）

1. **実機での挙動確認**:
   * デスクトップの `医学部合格アプリ` アイコンからアプリを起動し、保護者設定（パスワード: `0000`）から「全アカウントで保護を有効にする」を必要に応じて実行・確認する。
   * 勉強時間帯（例: 土曜 19:00〜23:00 等）にゲームや Chrome 等を起動してみて、ブロック画面が表示され正しく終了されるかテストする。
2. **今後の機能拡張案 (V2.0 Roadmap)**:
   * **学習利用統計のグラフィカル表示**: 1日の利用時間推移や週ごとの勉強時間達成度の可視化。
   * **保護者用Web/モバイル通知連携**: 一時コードの発行や利用超過通知を遠隔で受信・操作できる仕組み。
   * **MSI/InnoSetup インストーラー化**: 初回セットアップをより簡便にするインストーラーパッケージの作成。

---

以上の情報を引き継ぎ資料としてご利用ください。
