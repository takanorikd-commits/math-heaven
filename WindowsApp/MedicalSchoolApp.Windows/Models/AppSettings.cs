namespace MedicalSchoolApp.Windows.Models;

public class AppSettings
{
    public static readonly string[] WeekdayKeys = { "mon", "tue", "wed", "thu", "fri", "sat", "sun" };

    public int DailyLimitMinutes { get; set; } = 90;

    public Dictionary<string, List<StudyTimeRange>> WeekdayStudyTimes { get; set; } = new();

    /// <summary>
    /// 許可アプリ（実行ファイル名、拡張子込み、小文字）。ここに無いアプリは
    /// 「遊び」とみなされ、DailyLimitMinutesのカウント対象・制限モードでの終了対象になる。
    /// </summary>
    public List<string> AllowedApps { get; set; } = new();

    public string ParentPasswordHash { get; set; } = "";
    public string ParentPasswordSalt { get; set; } = "";

    public TempPasswordInfo? TempPassword { get; set; }
    public List<TempPasswordInfo> TempPasswords { get; set; } = new();

    /// <summary>共通テスト日時（Android版のデフォルトに合わせる）</summary>
    public DateTime ExamDate { get; set; } = new DateTime(2028, 1, 15, 9, 30, 0);

    public bool AutostartEnabled { get; set; } = true;

    public static AppSettings CreateDefault()
    {
        var settings = new AppSettings();
        foreach (var key in WeekdayKeys)
        {
            settings.WeekdayStudyTimes[key] = new List<StudyTimeRange>();
        }
        var salt = Services.PasswordHasher.GenerateSalt();
        settings.ParentPasswordSalt = salt;
        settings.ParentPasswordHash = Services.PasswordHasher.Hash("0000", salt);
        return settings;
    }
}
