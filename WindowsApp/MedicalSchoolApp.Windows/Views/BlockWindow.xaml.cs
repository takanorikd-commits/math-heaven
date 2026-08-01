using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using MedicalSchoolApp.Windows.Models;
using MedicalSchoolApp.Windows.Services;

namespace MedicalSchoolApp.Windows.Views;

public partial class BlockWindow : Window
{
    private const int MaxAttempts = 3;
    private static readonly TimeSpan LockoutDuration = TimeSpan.FromMinutes(1);

    private int _failedAttempts;
    private DateTime? _lockedUntil;
    private DispatcherTimer? _lockoutTimer;

    public BlockWindow()
    {
        InitializeComponent();
        Loaded += (_, _) => CodeBox.Focus();
    }

    public void Refresh()
    {
        if (!IsLoaded || ReasonText is null) return;
        var settings = AppState.Settings;
        var today = AppState.TodayUsage;
        var now = DateTime.Now;

        var mode = ModeService.ComputeMode(settings, today, now);

        if (AppState.PseudoRestrictedMode)
        {
            ReasonText.Text = "🧪 疑似制限モード実行中（動作テスト）";
            CancelPseudoRestrictedButton.Visibility = Visibility.Visible;
        }
        else
        {
            CancelPseudoRestrictedButton.Visibility = Visibility.Collapsed;
            if (mode == Mode.StudyTime)
            {
                ReasonText.Text = "現在は勉強時間中です";
            }
            else if (mode == Mode.TimeExceeded)
            {
                ReasonText.Text = "本日の利用可能時間（遊び枠）の上限に達しました";
            }
            else
            {
                ReasonText.Text = "制限モード中";
            }
        }
    }

    private void CancelPseudoRestrictedButton_Click(object sender, RoutedEventArgs e)
    {
        AppState.PseudoRestrictedMode = false;
        App.HideBlockWindow();
        AppState.RaiseUpdated();
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

        var result = TryRedeem(code, out var message);
        if (result)
        {
            _failedAttempts = 0;
            CodeBox.Text = "";
            AppState.PseudoRestrictedMode = false;
            RedeemMessageText.Text = message;
            RedeemMessageText.Foreground = new SolidColorBrush(Color.FromRgb(0x10, 0xB9, 0x81));
            App.HideBlockWindow();
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

    private static bool TryRedeem(string code, out string redeemMsg)
    {
        redeemMsg = "";
        var now = DateTime.Now;
        var mode = ModeService.ComputeMode(AppState.Settings, AppState.TodayUsage, now);

        if (PasswordHasher.Verify(code, AppState.Settings.ParentPasswordSalt, AppState.Settings.ParentPasswordHash))
        {
            if (mode == Mode.StudyTime)
            {
                var currentBypass = AppState.StudyTimeBypassedUntil ?? now;
                if (currentBypass < now) currentBypass = now;
                AppState.StudyTimeBypassedUntil = currentBypass.AddMinutes(60);
                redeemMsg = "保護者認証成功: 勉強時間の制限を60分間解除しました！";
            }
            else
            {
                var today = AppState.TodayUsage;
                today.ExtraMinutes += 60;
                AppState.SaveUsage();
                redeemMsg = "保護者認証成功: 1日の使用時間を60分延長しました！";
            }
            AppState.SaveSettings();
            return true;
        }

        var list = AppState.Settings.TempPasswords;
        TempPasswordInfo? matched = null;

        if (list is not null)
        {
            matched = list.FirstOrDefault(t => !t.Used && (PasswordHasher.Verify(code, t.Salt, t.CodeHash) || code == t.CodeDisplay));
        }

        if (matched is null)
        {
            var legacy = AppState.Settings.TempPassword;
            if (legacy is not null && !legacy.Used && PasswordHasher.Verify(code, legacy.Salt, legacy.CodeHash))
            {
                matched = legacy;
            }
        }

        if (matched is null)
        {
            return false;
        }

        matched.Used = true;

        if (mode == Mode.StudyTime)
        {
            var currentBypass = AppState.StudyTimeBypassedUntil ?? now;
            if (currentBypass < now) currentBypass = now;
            AppState.StudyTimeBypassedUntil = currentBypass.AddMinutes(matched.ExtendMinutes);
            redeemMsg = $"勉強時間の制限を {matched.ExtendMinutes} 分間一時解除しました！";
        }
        else
        {
            var today = AppState.TodayUsage;
            today.ExtraMinutes += matched.ExtendMinutes;
            AppState.SaveUsage();
            redeemMsg = $"1日の制限時間を {matched.ExtendMinutes} 分延長しました！";
        }

        AppState.Settings.TempPasswords?.RemoveAll(t => t.Used);
        AppState.SaveSettings();
        return true;
    }
}
