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

    private static DateTime _loadedSettingsWriteTimeUtc = SettingsService.GetLastWriteTimeUtc();

    /// <summary>
    /// 疑似制限モード（動作確認用）。実際の時間・勉強時間に関わらず制限モードを強制する。
    /// あえて設定ファイルには保存しない（アプリ再起動で必ずOFFに戻る安全策）。
    /// </summary>
    public static bool PseudoRestrictedMode { get; set; }

    /// <summary>
    /// 勉強時間中の一時パスワード適用によるバイパス終了時刻。
    /// この時刻までは勉強時間帯であっても制限を一時解除する。
    /// </summary>
    public static DateTime? StudyTimeBypassedUntil { get; set; }

    public static event Action? Updated;

    public static void RaiseUpdated() => Updated?.Invoke();

    public static void SaveSettings()
    {
        SettingsService.Save(Settings);
        _loadedSettingsWriteTimeUtc = SettingsService.GetLastWriteTimeUtc();
    }

    /// <summary>
    /// 「全アカウントで保護」モードでは、保護者アカウントと子供のアカウントがそれぞれ別プロセスとして
    /// 同時に起動しうる。各プロセスは起動時に一度だけ設定をメモリに読み込むため、これが無いと、
    /// 一方のアカウントで設定（パスワード・勉強時間帯等）を変更しても、既に起動中の他方のアカウント側
    /// インスタンスには反映されない。DispatcherTimerから毎秒呼び出し、ファイルの更新日時を見て
    /// 他セッションによる保存を検知したら再読み込みする。
    /// </summary>
    public static void ReloadSettingsIfChangedOnDisk()
    {
        var writeTimeUtc = SettingsService.GetLastWriteTimeUtc();
        if (writeTimeUtc > _loadedSettingsWriteTimeUtc)
        {
            _loadedSettingsWriteTimeUtc = writeTimeUtc;
            Settings = SettingsService.Load();
            RaiseUpdated();
        }
    }

    public static void SaveUsage()
    {
        UsageService.Save(Usage);
    }

    public static DayUsage TodayUsage => UsageService.GetOrCreateToday(Usage);
}
