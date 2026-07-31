using System.Runtime.InteropServices;
using System.Threading;
using System.Windows;
using System.Windows.Interop;
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
    private uint _taskbarCreatedMessage;

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern uint RegisterWindowMessage(string lpString);

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // 想定外の例外でクラッシュダイアログを子供に見せたり、監視が止まったままにならないようにする
        // （Watchdogがいずれ再起動するが、それまでの間だけでも静かに終了する）。
        // 併せて原因調査のためcrash.logに書き出す（%ProgramData%または%APPDATA%配下）。
        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
        {
            LogCrash(args.ExceptionObject as Exception, "AppDomain.UnhandledException");
        };
        DispatcherUnhandledException += (_, ex) =>
        {
            LogCrash(ex.Exception, "DispatcherUnhandledException");
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

        try
        {
            StartNormally();
        }
        catch (Exception ex)
        {
            // ここで捕まえられれば、OnStartup中の同期的な例外（Dispatcherのメッセージループが
            // 始まる前）でも記録できる。DispatcherUnhandledExceptionはこのケースを拾えないため。
            LogCrash(ex, "OnStartup");
            Shutdown();
        }
    }

    private void StartNormally()
    {
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

        // 自動起動直後はタスクバー（通知領域）の初期化がまだ終わっていないことがあり、
        // その場合トレイアイコンの登録が失敗して二度と表示されない（=見た目上アプリが消えたように見える）
        // ことがある。Explorerがタスクバーを(再)作成した時に送るTaskbarCreatedメッセージを
        // 監視し、届いたらトレイアイコンを作り直すことで確実に復旧させる。
        try
        {
            _taskbarCreatedMessage = RegisterWindowMessage("TaskbarCreated");
            var hwnd = new WindowInteropHelper(_dashboardWindow).Handle;
            HwndSource.FromHwnd(hwnd)?.AddHook(WndProc);
        }
        catch
        {
            // ignore
        }

        _monitorTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromSeconds(1),
        };
        _monitorTimer.Tick += (_, _) =>
        {
            try
            {
                ProcessMonitor.Tick();
                WatchdogService.EnsureRunning();
            }
            catch (Exception ex)
            {
                // 監視ループ内の例外でタイマーが完全に止まってしまわないようにする
                LogCrash(ex, "MonitorTimer.Tick");
            }
        };
        _monitorTimer.Start();
    }

    private static void LogCrash(Exception? ex, string source)
    {
        try
        {
            var path = System.IO.Path.Combine(SettingsService.AppDataDir, "crash.log");
            var text = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} [{Environment.UserName} / {source}]{Environment.NewLine}{ex}{Environment.NewLine}{Environment.NewLine}";
            System.IO.File.AppendAllText(path, text);
        }
        catch
        {
            // ログ自体が書けなくても致命的にしない
        }
    }

    public static void ShowChatGptWindow()
    {
        _chatGptWindow ??= new ChatGptWindow();
        _chatGptWindow.Topmost = true;
        _chatGptWindow.Show();
        _chatGptWindow.Activate();
        // ブロック画面(常時最前面)がChatGPT画面を覆ってしまわないよう、いったん隠す
        _blockWindow?.Hide();
    }

    /// <summary>
    /// ChatGPT画面が現在アクティブ（使用中）かどうか。制限モード監視ループが
    /// 毎秒ブロック画面を最前面に出し直す際、ChatGPT使用中はそれを抑制するために使う。
    /// </summary>
    public static bool IsChatGptWindowActive => _chatGptWindow?.IsActive == true;

    public static void ShowBlockWindow()
    {
        if (_blockWindow is null) return;
        if (IsChatGptWindowActive) return; // ChatGPT使用中はブロック画面で覆わない

        if (_chatGptWindow is not null)
        {
            _chatGptWindow.Topmost = false;
        }
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

    /// <summary>
    /// トレイアイコンを(再)作成する。TaskbarCreated受信時にも呼ばれるため、
    /// 既存のアイコンがあれば一度破棄してから作り直す（多重登録を防ぐ）。
    /// </summary>
    private void SetupTrayIcon()
    {
        if (_trayIcon is not null)
        {
            _trayIcon.Visible = false;
            _trayIcon.Dispose();
            _trayIcon = null;
        }

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

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (_taskbarCreatedMessage != 0 && msg == _taskbarCreatedMessage)
        {
            try
            {
                SetupTrayIcon();
            }
            catch (Exception ex)
            {
                LogCrash(ex, "TaskbarCreated/SetupTrayIcon");
            }
        }
        return IntPtr.Zero;
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

    private void RunElevatedTaskAndExit(Action task)
    {
        try
        {
            task();
        }
        finally
        {
            Shutdown();
        }
    }
}
