using MedicalSchoolApp.Windows.Models;

namespace MedicalSchoolApp.Windows.Services;

/// <summary>
/// アプリ全体で共有する設定・使用履歴。DispatcherTimer(UIスレッド)からのみ更新されるため
/// ロックは不要。DashboardWindow等はUpdatedイベントを購読して再描画する。
/// </summary>
public static class AppState
{
    public static AppSettings Settings { get; private set; } = SettingsService.Load();
    public static Dictionary<string, DayUsage> Usage { get; private set; } = UsageService.Load();

    public static event Action? Updated;

    public static void RaiseUpdated() => Updated?.Invoke();

    public static void SaveSettings()
    {
        SettingsService.Save(Settings);
    }

    public static void SaveUsage()
    {
        UsageService.Save(Usage);
    }

    public static DayUsage TodayUsage => UsageService.GetOrCreateToday(Usage);
}
