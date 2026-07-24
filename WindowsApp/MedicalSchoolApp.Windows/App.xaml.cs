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

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // 前回セッションの意図的終了フラグが残っていたら消す（通常起動として扱う）
        WatchdogService.ClearStaleShutdownFlag();

        AutostartService.Apply(AppState.Settings.AutostartEnabled);
        WatchdogService.ApplyAutostart(AppState.Settings.AutostartEnabled);
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
        base.OnExit(e);
    }
}
