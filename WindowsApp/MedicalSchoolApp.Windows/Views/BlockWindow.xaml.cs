using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using MedicalSchoolApp.Windows.Services;

namespace MedicalSchoolApp.Windows.Views;

public partial class BlockWindow : Window
{
    private const int MaxAttempts = 5;
    private static readonly TimeSpan LockoutDuration = TimeSpan.FromSeconds(30);

    private int _failedAttempts;
    private DateTime? _lockedUntil;
    private DispatcherTimer? _lockoutTimer;

    public BlockWindow()
    {
        InitializeComponent();
        Closing += (_, e) =>
        {
            e.Cancel = true;
            Hide();
        };
        AppState.Updated += Refresh;
        IsVisibleChanged += (_, _) =>
        {
            if (IsVisible) Refresh();
        };
    }

    public void Refresh()
    {
        if (AppState.PseudoRestrictedMode)
        {
            ReasonText.Text = "疑似制限モード（テスト中）";
            return;
        }

        var mode = ModeService.ComputeMode(AppState.Settings, AppState.TodayUsage, DateTime.Now);
        ReasonText.Text = mode == Mode.StudyTime
            ? "現在は勉強時間中です"
            : "本日のPC使用時間は終了しました";
    }

    private void OpenChatGptButton_Click(object sender, RoutedEventArgs e)
    {
        App.ShowChatGptWindow();
    }

    private void CodeBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) RedeemButton_Click(sender, e);
    }

    private void RedeemButton_Click(object sender, RoutedEventArgs e)
    {
        if (_lockedUntil is { } lockedUntil && DateTime.Now < lockedUntil)
        {
            return;
        }

        var code = CodeBox.Text.Trim();
        if (string.IsNullOrEmpty(code))
        {
            return;
        }

        var result = TryRedeem(code);
        if (result)
        {
            _failedAttempts = 0;
            CodeBox.Text = "";
            RedeemMessageText.Text = "延長しました！";
            RedeemMessageText.Foreground = new SolidColorBrush(Color.FromRgb(0x10, 0xB9, 0x81));
            AppState.RaiseUpdated();
        }
        else
        {
            _failedAttempts++;
            if (_failedAttempts >= MaxAttempts)
            {
                _lockedUntil = DateTime.Now + LockoutDuration;
                _failedAttempts = 0;
                StartLockoutCountdown();
            }
            else
            {
                RedeemMessageText.Text = "コードが無効か、使用済みです";
                RedeemMessageText.Foreground = new SolidColorBrush(Color.FromRgb(0xEF, 0x44, 0x44));
            }
        }
    }

    private void StartLockoutCountdown()
    {
        CodeBox.IsEnabled = false;
        RedeemButton.IsEnabled = false;

        _lockoutTimer?.Stop();
        _lockoutTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _lockoutTimer.Tick += (_, _) =>
        {
            var remaining = _lockedUntil.HasValue ? (_lockedUntil.Value - DateTime.Now) : TimeSpan.Zero;
            if (remaining <= TimeSpan.Zero)
            {
                _lockoutTimer!.Stop();
                _lockedUntil = null;
                CodeBox.IsEnabled = true;
                RedeemButton.IsEnabled = true;
                RedeemMessageText.Text = "";
            }
            else
            {
                RedeemMessageText.Text = $"試行回数が上限に達しました。{(int)Math.Ceiling(remaining.TotalSeconds)}秒後に再試行してください。";
                RedeemMessageText.Foreground = new SolidColorBrush(Color.FromRgb(0xEF, 0x44, 0x44));
            }
        };
        _lockoutTimer.Start();
    }

    private static bool TryRedeem(string code)
    {
        var temp = AppState.Settings.TempPassword;
        if (temp is null || temp.Used) return false;
        if (DateTime.Now > temp.ExpiresAt) return false;
        if (!PasswordHasher.Verify(code, temp.Salt, temp.CodeHash)) return false;

        temp.Used = true;
        var today = AppState.TodayUsage;
        today.ExtraMinutes += temp.ExtendMinutes;
        AppState.SaveSettings();
        AppState.SaveUsage();
        return true;
    }
}
