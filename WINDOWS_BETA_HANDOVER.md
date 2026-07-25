# 📋 医学部合格アプリ（Windows版） ベータ版 完成＆次回開発申し送り書 (Handover Document)

> **作成日時**: 2026年7月26日  
> **開発ステータス**: **ベータ版 (Beta 1.0) 完成・実機動作検証済み**  
> **リポジトリ**: `https://github.com/takanorikd-commits/math-heaven.git`  

---

## 🚨 次のAIエージェント / 開発者への【最重要鉄則】

1. **`app/` フォルダ（Androidアプリ）は絶対に編集・変更しないでください！**
   - 本リポジトリにはAndroid版（`app/`）とWindows版（`WindowsApp/`）が同居しています。
   - すべてのWindows開発・改修は **`WindowsApp/MedicalSchoolApp.Windows/`** 配下でのみ行ってください。
2. **コードを変更したら必ず `dotnet build` で 0 エラー・0 警告を確認してください。**
   - ビルドコマンド: `dotnet build WindowsApp\MedicalSchoolApp.Windows.sln`
3. **AIエージェントや開発ツールのプロセス保護（`SafeList`）を維持してください。**
   - `WindowsApp/MedicalSchoolApp.Windows/Services/ProcessMonitor.cs` 内の `SafeList` に、開発に使用するツール（例: `antigravity.exe`, `code.exe`, `powershell.exe`, `cmd.exe` 等）が登録されていることを確認してください。登録されていないと、制限モードテスト時に開発ツール自体が強制終了されます。

---

## 🛠️ ベータ版で完成・検証された主要機能一覧

| 機能エリア | 実装内容・仕様 | 主な関連ファイル |
| :--- | :--- | :--- |
| **0エラービルド** | .NET 8 (WPF) / C# にて警告・エラー0で完全ビルド・動作 | `MedicalSchoolApp.Windows.sln` |
| **1秒周期監視** | 1秒ごとに許可アプリ外の非SafeListプロセスを検知・終了 | `Services/ProcessMonitor.cs` |
| **相互監視ウォッチドッグ** | 本体プロセス終了時に自動で再起動して制限回避を防止 | `MedicalSchoolApp.Windows.Watchdog/` |
| **全アカウント保護** | HKLM Runキー自動起動登録 + ProgramData共通設定フォルダ + ACL `icacls` 削除禁止制御 | `Services/MachineWideSetupService.cs` |
| **最前面ブロック画面** | `Topmost=True`, `WindowState=Maximized`, `WindowStyle=None` で全画面を覆うブロック画面 | `Views/BlockWindow.xaml(.cs)` |
| **保護者パスワード解錠** | ブロック画面で保護者パスワード（初期 `0000`）入力により即座に解除（勉強時間中:60分解除 / 1日超過:60分延長） | `Views/BlockWindow.xaml.cs` (`TryRedeem`) |
| **無期限使い捨て一時コード** | 事前に複数発行可能・有効期限なし・1回入力で自動使い捨て＆一覧から自動完全消去 | `Views/BlockWindow.xaml.cs`, `Views/ParentSettingsWindow.xaml.cs` |
| **ダッシュボード** | 残り時間内訳表示（例: `46 分 (基本90分 + 延長90分)`）＆ `🧪 疑似制限モード` ワンタップテストボタン | `Views/DashboardWindow.xaml(.cs)` |
| **二重起動時の前面化** | 名前付きイベント (`EventWaitHandle`) により、常駐中にアイコンをダブルクリックすると自動でダッシュボードが最前面に表示 | `App.xaml.cs` |
| **デスクトップアイコン** | `MedicalSchoolApp.lnk` / `医学部合格アプリ.lnk` の正確な生成 | `scratch/clean_shortcuts.vbs` |

---

## 📂 アーキテクチャとディレクトリ構造

```text
C:\Users\takst\Documents\antigravity\MedicalSchoolApp\
├── WindowsApp/
│   ├── MedicalSchoolApp.Windows.sln              # メインソリューション
│   ├── MedicalSchoolApp.Windows/                 # Windows WPF メインアプリ
│   │   ├── App.xaml / App.xaml.cs               # アプリライフサイクル・Mutex・EventWaitHandle
│   │   ├── Models/                              # AppSettings.cs, DayUsage.cs, TempPasswordInfo.cs
│   │   ├── Services/                            # ProcessMonitor.cs, ModeService.cs, MachineWideSetupService.cs
│   │   └── Views/                               # DashboardWindow.xaml, BlockWindow.xaml, ParentSettingsWindow.xaml
│   └── MedicalSchoolApp.Windows.Watchdog/       # ウオッチドッグ常駐プロセス
├── WINDOWS_VERSION_PLAN.md                      # 全体開発計画書
└── WINDOWS_BETA_HANDOVER.md                     # 本申し送り書
```

---

## 🚀 別PC・別AIエージェントでの開発開始（Quick Start）

### 1. リポジトリのクローン
```bash
git clone https://github.com/takanorikd-commits/math-heaven.git MedicalSchoolApp
cd MedicalSchoolApp
```

### 2. ビルド確認
```bash
dotnet build WindowsApp\MedicalSchoolApp.Windows.sln
```

### 3. アプリの起動・動作テスト
```bash
dotnet run --project WindowsApp\MedicalSchoolApp.Windows\MedicalSchoolApp.Windows.csproj
```

---

## 🔑 テストアカウント・デフォルト設定情報

- **保護者パスワード初期値**: `0000`
- **制限時間の初期値**: 1日 `90` 分
- **共通設定ファイルの保存場所**:
  - 全アカウント保護有効時: `C:\ProgramData\MedicalSchoolApp.Windows\` (`settings.json`, `usage_history.json`)
  - 通常時: `%APPDATA%\MedicalSchoolApp.Windows\`

---

## 💡 今後の拡張アイデア（V2.0に向けて）

1. **学習統計グラフの更なるビジュアル化** (日別・週別の勉強時間グラフ表示)
2. **保護者スマホへの通知連携** (LINE / メール等での一時コード送出や制限到達通知)
3. **正式インストーラーの作成** (Inno Setup または WiX による MSI 化)
