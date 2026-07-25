using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using MedicalSchoolApp.Windows.Models;
using MedicalSchoolApp.Windows.Services;

namespace MedicalSchoolApp.Windows.Views;

public partial class ParentSettingsWindow : Window
{
    private static readonly Dictionary<string, string> WeekdayLabelsJa = new()
    {
        ["mon"] = "月", ["tue"] = "火", ["wed"] = "水", ["thu"] = "木",
        ["fri"] = "金", ["sat"] = "土", ["sun"] = "日",
    };

    private string _unlockedPassword = "";
    private AppSettings _working = null!;

    public ParentSettingsWindow()
    {
        InitializeComponent();
    }

    private void PasswordInputBox_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key == Key.Enter) UnlockButton_Click(sender, e);
    }

    private void UnlockButton_Click(object sender, RoutedEventArgs e)
    {
        var password = PasswordInputBox.Password;
        if (!PasswordHasher.Verify(password, AppState.Settings.ParentPasswordSalt, AppState.Settings.ParentPasswordHash))
        {
            UnlockErrorText.Text = "パスワードが違います";
            UnlockErrorText.Visibility = Visibility.Visible;
            return;
        }

        _unlockedPassword = password;
        LoadWorkingCopy();
        LockPanel.Visibility = Visibility.Collapsed;
        SettingsPanel.Visibility = Visibility.Visible;
    }

    private void LoadWorkingCopy()
    {
        var json = JsonSerializer.Serialize(AppState.Settings);
        _working = JsonSerializer.Deserialize<AppSettings>(json)!;

        DailyLimitBox.Text = _working.DailyLimitMinutes.ToString();
        ExamDatePicker.SelectedDate = _working.ExamDate;
        AutostartCheckBox.IsChecked = _working.AutostartEnabled;

        RefreshAllowedAppsList();
        RebuildWeekdayPanel();
        RefreshMachineWideStatus();
        RefreshPseudoRestrictedButton();
    }

    private void RefreshPseudoRestrictedButton()
    {
        if (AppState.PseudoRestrictedMode)
        {
            PseudoRestrictedToggleButton.Content = "疑似制限モードを解除する（通常に戻す）";
            PseudoRestrictedToggleButton.Background = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(0xF5, 0x9E, 0x0B));
        }
        else
        {
            PseudoRestrictedToggleButton.Content = "疑似制限モードを開始する";
            PseudoRestrictedToggleButton.Background = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(0x33, 0x41, 0x55));
        }
    }

    private void PseudoRestrictedToggleButton_Click(object sender, RoutedEventArgs e)
    {
        AppState.PseudoRestrictedMode = !AppState.PseudoRestrictedMode;
        RefreshPseudoRestrictedButton();
        AppState.RaiseUpdated();
    }

    private void RefreshMachineWideStatus()
    {
        if (MachineWideSetupService.IsMachineWideEnabled)
        {
            MachineWideStatusText.Text = "状態: 有効（全アカウントで保護中）";
            MachineWideStatusText.Foreground = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(0x10, 0xB9, 0x81));
            MachineWideToggleButton.Content = "このアカウント専用に戻す";
            MachineWideToggleButton.Background = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(0x33, 0x41, 0x55));
        }
        else
        {
            MachineWideStatusText.Text = "状態: 無効（このアカウントのみ保護中）";
            MachineWideStatusText.Foreground = System.Windows.Media.Brushes.Gray;
            MachineWideToggleButton.Content = "全アカウントで保護を有効にする";
            MachineWideToggleButton.Background = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(0x3B, 0x82, 0xF6));
        }
    }

    private void MachineWideToggleButton_Click(object sender, RoutedEventArgs e)
    {
        MachineWideMessageText.Text = "";
        var enabling = !MachineWideSetupService.IsMachineWideEnabled;

        var confirmMessage = enabling
            ? "全アカウントで保護を有効にします。次のダイアログで管理者の許可（UAC）が必要です。続けますか？"
            : "このアカウント専用に戻します。他のアカウントは保護されなくなります。次のダイアログで管理者の許可（UAC）が必要です。続けますか？";
        if (MessageBox.Show(confirmMessage, "全アカウントでの保護", MessageBoxButton.OKCancel) != MessageBoxResult.OK)
        {
            return;
        }

        var success = enabling
            ? MachineWideSetupService.EnableMachineWide()
            : MachineWideSetupService.DisableMachineWide();

        if (!success)
        {
            MachineWideMessageText.Text = "管理者の許可が得られなかったため、変更されませんでした。";
            MachineWideMessageText.Foreground = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(0xEF, 0x44, 0x44));
            return;
        }

        if (enabling)
        {
            // 現在の設定・使用履歴を新しい共有フォルダへ引き継ぐ
            AppState.SaveSettings();
            AppState.SaveUsage();
        }

        RefreshMachineWideStatus();
        MachineWideMessageText.Text = enabling
            ? "有効にしました。他のアカウントでは次回ログイン時から自動的に保護されます。"
            : "無効にしました。このアカウントのみ、次回起動時から通常の自動起動に戻ります。";
        MachineWideMessageText.Foreground = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(0x10, 0xB9, 0x81));
    }

    private void RefreshAllowedAppsList()
    {
        AllowedAppsListBox.ItemsSource = null;
        AllowedAppsListBox.ItemsSource = _working.AllowedApps;
    }

    private void NewAllowedAppBox_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key == Key.Enter) AddAllowedAppButton_Click(sender, e);
    }

    private void AddAllowedAppButton_Click(object sender, RoutedEventArgs e)
    {
        var value = NewAllowedAppBox.Text.Trim();
        if (string.IsNullOrEmpty(value)) return;
        _working.AllowedApps.Add(value);
        NewAllowedAppBox.Text = "";
        RefreshAllowedAppsList();
    }

    private void RemoveAllowedAppButton_Click(object sender, RoutedEventArgs e)
    {
        if (AllowedAppsListBox.SelectedItem is string selected)
        {
            _working.AllowedApps.Remove(selected);
            RefreshAllowedAppsList();
        }
    }

    private void RebuildWeekdayPanel()
    {
        WeekdayPanel.Children.Clear();
        foreach (var day in AppSettings.WeekdayKeys)
        {
            if (!_working.WeekdayStudyTimes.ContainsKey(day))
            {
                _working.WeekdayStudyTimes[day] = new List<StudyTimeRange>();
            }
            WeekdayPanel.Children.Add(BuildWeekdayCard(day));
        }
    }

    private Border BuildWeekdayCard(string day)
    {
        var border = new Border
        {
            Background = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(0x1E, 0x29, 0x3B)),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(12),
            Margin = new Thickness(0, 0, 0, 8),
        };

        var stack = new StackPanel();
        var header = new Grid();
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var label = new TextBlock
        {
            Text = $"{WeekdayLabelsJa[day]}曜日",
            FontWeight = FontWeights.Bold,
            VerticalAlignment = VerticalAlignment.Center,
        };
        Grid.SetColumn(label, 0);

        var addButton = new Button { Content = "+ 時間帯を追加", Padding = new Thickness(8, 4, 8, 4), Background = System.Windows.Media.Brushes.Transparent, Foreground = System.Windows.Media.Brushes.White };
        addButton.Click += (_, _) =>
        {
            _working.WeekdayStudyTimes[day].Add(new StudyTimeRange { Start = "18:00", End = "20:00" });
            RebuildWeekdayPanel();
        };
        Grid.SetColumn(addButton, 1);

        header.Children.Add(label);
        header.Children.Add(addButton);
        stack.Children.Add(header);

        var ranges = _working.WeekdayStudyTimes[day];
        if (ranges.Count == 0)
        {
            stack.Children.Add(new TextBlock { Text = "勉強時間の指定なし", Foreground = System.Windows.Media.Brushes.Gray, FontSize = 12, Margin = new Thickness(0, 4, 0, 0) });
        }

        for (var i = 0; i < ranges.Count; i++)
        {
            var index = i;
            var row = new Grid { Margin = new Thickness(0, 6, 0, 0) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var startBox = new TextBox { Text = ranges[index].Start, Padding = new Thickness(4) };
            startBox.LostFocus += (_, _) => ranges[index].Start = startBox.Text;
            Grid.SetColumn(startBox, 0);

            var tilde = new TextBlock { Text = "〜", Margin = new Thickness(6, 0, 6, 0), VerticalAlignment = VerticalAlignment.Center };
            Grid.SetColumn(tilde, 1);

            var endBox = new TextBox { Text = ranges[index].End, Padding = new Thickness(4) };
            endBox.LostFocus += (_, _) => ranges[index].End = endBox.Text;
            Grid.SetColumn(endBox, 2);

            var deleteButton = new Button { Content = "削除", Margin = new Thickness(6, 0, 0, 0), Background = System.Windows.Media.Brushes.Transparent, Foreground = System.Windows.Media.Brushes.OrangeRed };
            deleteButton.Click += (_, _) =>
            {
                ranges.RemoveAt(index);
                RebuildWeekdayPanel();
            };
            Grid.SetColumn(deleteButton, 3);

            row.Children.Add(startBox);
            row.Children.Add(tilde);
            row.Children.Add(endBox);
            row.Children.Add(deleteButton);
            stack.Children.Add(row);
        }

        border.Child = stack;
        return border;
    }

    private void SaveButton_Click(object sender, RoutedEventArgs e)
    {
        if (!int.TryParse(DailyLimitBox.Text, out var dailyLimit) || dailyLimit < 0)
        {
            MessageBox.Show("1日の使用可能時間は0以上の数値を入力してください。", "入力エラー");
            return;
        }

        AppState.Settings.DailyLimitMinutes = dailyLimit;
        AppState.Settings.WeekdayStudyTimes = _working.WeekdayStudyTimes;
        AppState.Settings.AllowedApps = _working.AllowedApps;
        AppState.Settings.ExamDate = ExamDatePicker.SelectedDate ?? AppState.Settings.ExamDate;
        AppState.Settings.AutostartEnabled = AutostartCheckBox.IsChecked ?? true;

        AppState.SaveSettings();
        if (!MachineWideSetupService.IsMachineWideEnabled)
        {
            // 全アカウント保護が有効な場合はHKLM側が自動起動を担当するため、HKCU側は触らない
            AutostartService.Apply(AppState.Settings.AutostartEnabled);
            WatchdogService.ApplyAutostart(AppState.Settings.AutostartEnabled);
        }
        AppState.RaiseUpdated();

        MessageBox.Show("保存しました。", "保護者設定");
    }

    private void ChangePasswordButton_Click(object sender, RoutedEventArgs e)
    {
        var oldPassword = OldPasswordBox.Password;
        var newPassword = NewPasswordBox.Password;

        if (!PasswordHasher.Verify(oldPassword, AppState.Settings.ParentPasswordSalt, AppState.Settings.ParentPasswordHash))
        {
            PasswordChangeMessage.Text = "現在のパスワードが違います";
            return;
        }
        if (string.IsNullOrWhiteSpace(newPassword))
        {
            PasswordChangeMessage.Text = "新しいパスワードを入力してください";
            return;
        }

        var salt = PasswordHasher.GenerateSalt();
        AppState.Settings.ParentPasswordSalt = salt;
        AppState.Settings.ParentPasswordHash = PasswordHasher.Hash(newPassword, salt);
        AppState.SaveSettings();

        _unlockedPassword = newPassword;
        OldPasswordBox.Password = "";
        NewPasswordBox.Password = "";
        PasswordChangeMessage.Text = "パスワードを変更しました。";
    }

    private void IssueTempPasswordButton_Click(object sender, RoutedEventArgs e)
    {
        if (!int.TryParse(ExtendMinutesBox.Text, out var extendMinutes) || extendMinutes <= 0)
        {
            MessageBox.Show("延長する分数は1以上の数値を入力してください。", "入力エラー");
            return;
        }
        if (!int.TryParse(ValidMinutesBox.Text, out var validMinutes) || validMinutes <= 0)
        {
            MessageBox.Show("有効期限は1以上の数値を入力してください。", "入力エラー");
            return;
        }

        var code = PasswordHasher.GenerateTempCode();
        var salt = PasswordHasher.GenerateSalt();
        AppState.Settings.TempPassword = new TempPasswordInfo
        {
            CodeHash = PasswordHasher.Hash(code, salt),
            Salt = salt,
            ExtendMinutes = extendMinutes,
            ExpiresAt = DateTime.Now.AddMinutes(validMinutes),
            Used = false,
        };
        AppState.SaveSettings();

        IssuedCodeText.Text = code;
    }
}
