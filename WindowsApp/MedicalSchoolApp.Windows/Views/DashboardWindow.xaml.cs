using System.Windows;
using System.Windows.Media;
using MedicalSchoolApp.Windows.Services;

namespace MedicalSchoolApp.Windows.Views;

public partial class DashboardWindow : Window
{
    public DashboardWindow()
    {
        InitializeComponent();
        AppState.Updated += Refresh;
        Loaded += (_, _) => Refresh();
        Closing += (_, e) =>
        {
            e.Cancel = true;
            Hide();
        };
    }

    public void Refresh()
    {
        var settings = AppState.Settings;
        var today = AppState.TodayUsage;
        var now = DateTime.Now;

        var mode = ModeService.ComputeMode(settings, today, now);
        var remaining = ModeService.RemainingMinutes(settings, today);
        var nextSlot = ModeService.NextStudySlot(settings, now);
        var examDiff = ModeService.TimeUntilExam(settings, now);

        RemainingMinutesText.Text = $"{Math.Max(0, Math.Round(remaining))} 分";
        NextStudySlotText.Text = nextSlot ?? "予定なし";

        if (examDiff is { } diff)
        {
            ExamCountdownText.Text = diff.TotalSeconds < 0
                ? "終了しました"
                : ModeService.FormatExamCountdown(diff);
        }
        else
        {
            ExamCountdownText.Text = "未設定";
        }

        (ModeBadgeText.Text, ModeBadgeBorder.Background) = mode switch
        {
            Mode.StudyTime => ("勉強時間中", new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x8B, 0x5C, 0xF6))),
            Mode.TimeExceeded => ("60分超過", new SolidColorBrush(System.Windows.Media.Color.FromRgb(0xEF, 0x44, 0x44))),
            _ => ("通常", new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x10, 0xB9, 0x81))),
        };
    }

    private void OpenChatGptButton_Click(object sender, RoutedEventArgs e)
    {
        App.ShowChatGptWindow();
    }

    private void OpenSettingsButton_Click(object sender, RoutedEventArgs e)
    {
        var settingsWindow = new ParentSettingsWindow { Owner = this };
        settingsWindow.ShowDialog();
    }
}
