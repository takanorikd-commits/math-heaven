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
    }

    public static void SaveUsage()
    {
        UsageService.Save(Usage);
    }

    /// <summary>
    /// 設定をディスクから読み直す。「全アカウントで保護」時は複数プロセスが同じ
    /// settings.jsonを共有するため、他アカウントで行われた変更（勉強時間・上限分等）を
    /// 反映するために定期的に呼ぶ必要がある。Settingsはこのプロセス自身が書き込む以外に
    /// 途中経過を持たないため、いつ読み直しても安全。
    /// </summary>
    public static void ReloadSettings()
    {
        Settings = SettingsService.Load();
    }

    /// <summary>
    /// 使用履歴をディスクから読み直す。Usageは1秒ごとの加算をメモリ上に溜めて
    /// 5秒毎に書き出す方式のため、SaveUsage()の直後（書き出しが完了した後）に
    /// 呼ぶこと。そうしないと、まだ書き出していない自分自身の増分を失う。
    /// </summary>
    public static void ReloadUsage()
    {
        Usage = UsageService.Load();
    }

    public static DayUsage TodayUsage => UsageService.GetOrCreateToday(Usage);
}
