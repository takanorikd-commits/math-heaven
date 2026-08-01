using System.Threading;
using System.Windows;
using System.Windows.Threading;
using MedicalSchoolApp.Windows.Services;
using MedicalSchoolApp.Windows.Views;

namespace MedicalSchoolApp.Windows;

public partial class App : System.Windows.Application
{
    private static DashboardWindow? _dashboardWindow;
    private static ChatGptWindow? _chatGptWindow;
    private static BlockWindow? _blockWindow;
    private DispatcherTimer? _monitorTimer;
    private System.Windows.Forms.NotifyIcon? _trayIcon;
    private Mutex? _singleInstanceMutex;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // 想定外の例外でクラッシュダイアログを子供に見せたり、監視が止まったままにならないようにする
        // （Watchdogがいずれ再起動するが、それまでの間だけでも静かに終了する）
        DispatcherUnhandledException += (_, ex) =>
        {
            ex.Handled = true;
            Shutdown();
        };

        // UAC昇格された「全アカウントで保護」の登録/解除専用モード。UIは出さず即終了する。
        if (e.Args.Contains(MachineWideSetupService.RegisterArg))
        {
            RunElevatedTaskAndExit(MachineWideSetupService.RunElevatedRegistration);
            return;
        }
        if (e.Args.Contains(MachineWideSetupService.UnregisterArg))
        {
            RunElevatedTaskAndExit(MachineWideSetupService.RunElevatedUnregistration);
            return;
        }

        // 同一セッション内での二重起動を防ぐ（すでに起動している場合は常駐中の本体のダッシュボードを前面表示する）
        _singleInstanceMutex = new Mutex(true, "Local\\MedicalSchoolAppWindows_SingleInstance", out var createdNew);
        if (!createdNew)
        {
            try
            {
                using var existingEvent = EventWaitHandle.OpenExisting("Local\\MedicalSchoolAppWindows_ShowDashboard");
                existingEvent.Set();
            }
            catch
            {
                // ignore
            }
            Shutdown();
            return;
        }

        try
        {
            var showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, "Local\\MedicalSchoolAppWindows_ShowDashboard");
            ThreadPool.RegisterWaitForSingleObject(showEvent, (_, _) =>
            {
                Dispatcher.Invoke(ShowDashboardWindow);
            }, null, -1, false);
        }
        catch
        {
            // ignore
        }

        // 前回セッションの意図的終了フラグが残っていたら消す（通常起動として扱う）
        WatchdogService.ClearStaleShutdownFlag();

        if (MachineWideSetupService.IsMachineWideEnabled)
        {
            // HKLM側が全アカウント分の自動起動を担当するので、このアカウント専用のHKCU登録は消しておく（二重起動防止）
            AutostartService.Apply(false);
            WatchdogService.ApplyAutostart(false);
        }
        else
        {
            AutostartService.Apply(AppState.Settings.AutostartEnabled);
            WatchdogService.ApplyAutostart(AppState.Settings.AutostartEnabled);
        }
        WatchdogService.EnsureRunning();

        _dashboardWindow = new DashboardWindow();
        _dashboardWindow.Show();

        _blockWindow = new BlockWindow();
        _blockWindow.Hide();

        SetupTrayIcon();

        _monitorTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromSeconds(1),
        };
        _monitorTimer.Tick += (_, _) =>
        {
            AppState.ReloadSettingsIfChangedOnDisk();
            ProcessMonitor.Tick();
            WatchdogService.EnsureRunning();
        };
        _monitorTimer.Start();
    }

    public static void ShowChatGptWindow()
    {
        _chatGptWindow ??= new ChatGptWindow();
        _chatGptWindow.Show();
        _chatGptWindow.Activate();
    }

    public static void ShowBlockWindow()
    {
        if (_blockWindow is null) return;
        _blockWindow.Refresh();
        _blockWindow.WindowState = WindowState.Maximized;
        _blockWindow.Show();
        _blockWindow.Activate();
        _blockWindow.Topmost = true;
    }

    public static void HideBlockWindow()
    {
        _blockWindow?.Hide();
    }

    public static void ShowDashboardWindow()
    {
        if (_dashboardWindow is null) return;
        _dashboardWindow.Show();
        _dashboardWindow.WindowState = WindowState.Normal;
        _dashboardWindow.Activate();
    }

    private void SetupTrayIcon()
    {
        _trayIcon = new System.Windows.Forms.NotifyIcon
        {
            Icon = System.Drawing.SystemIcons.Shield,
            Visible = true,
            Text = "医学部合格アプリ",
        };

        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("ダッシュボードを開く", null, (_, _) => ShowDashboardWindow());
        menu.Items.Add("終了", null, (_, _) => RequestQuit());
        _trayIcon.ContextMenuStrip = menu;
        _trayIcon.DoubleClick += (_, _) => ShowDashboardWindow();
    }

    private void RequestQuit()
    {
        if (!PasswordPromptWindow.ConfirmParentPassword("終了するには保護者パスワードを入力してください"))
        {
            return;
        }

        // Watchdogが再起動しないよう、意図的終了であることを先に知らせる
        WatchdogService.WriteShutdownFlag();
        Shutdown();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _monitorTimer?.Stop();
        if (_trayIcon is not null)
        {
            _trayIcon.Visible = false;
            _trayIcon.Dispose();
        }
        _singleInstanceMutex?.ReleaseMutex();
        base.OnExit(e);
    }

    private void RunElevatedTaskAndExit(Func<bool> task)
    {
        var success = false;
        try
        {
            success = task();
        }
        finally
        {
            // 呼び出し元（MachineWideSetupService.RunElevated）はこの終了コードで
            // 実際の登録成否を判定するため、falseの場合は非0で終了する必要がある。
            Shutdown(success ? 0 : 1);
        }
    }
}
