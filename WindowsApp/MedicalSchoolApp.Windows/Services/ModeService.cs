using System.Globalization;
using MedicalSchoolApp.Windows.Models;

namespace MedicalSchoolApp.Windows.Services;

public enum Mode
{
    Normal,
    StudyTime,
    TimeExceeded,
}

public static class ModeService
{
    private static readonly string[] WeekdayLabelsJa = { "月", "火", "水", "木", "金", "土", "日" };

    private static string WeekdayKey(DateTime dt)
    {
        // DayOfWeek: Sunday=0 ... Saturday=6 → mon..sun のインデックスに変換
        var index = ((int)dt.DayOfWeek + 6) % 7; // Monday=0 ... Sunday=6
        return AppSettings.WeekdayKeys[index];
    }

    private static string NormalizeTimeStr(string s)
    {
        if (string.IsNullOrWhiteSpace(s)) return "";
        var sb = new System.Text.StringBuilder();
        foreach (var c in s.Trim())
        {
            if (c >= '０' && c <= '９') sb.Append((char)('0' + (c - '０')));
            else if (c == '：') sb.Append(':');
            else if (!char.IsWhiteSpace(c)) sb.Append(c);
        }
        return sb.ToString();
    }

    private static bool TryParseTime(string s, out TimeSpan time)
    {
        var normalized = NormalizeTimeStr(s);
        if (string.IsNullOrEmpty(normalized))
        {
            time = TimeSpan.Zero;
            return false;
        }

        if (normalized == "24:00" || normalized == "24:00:00")
        {
            time = TimeSpan.FromDays(1); // 24時
            return true;
        }

        return TimeSpan.TryParse(normalized, CultureInfo.InvariantCulture, out time);
    }

    public static bool IsStudyTime(AppSettings settings, DateTime now)
    {
        if (AppState.StudyTimeBypassedUntil is { } bypassedUntil && now < bypassedUntil)
        {
            return false;
        }
        var key = WeekdayKey(now);
        if (!settings.WeekdayStudyTimes.TryGetValue(key, out var ranges))
        {
            return false;
        }

        var cur = now.TimeOfDay;
        foreach (var range in ranges)
        {
            if (TryParseTime(range.Start, out var start) && TryParseTime(range.End, out var end))
            {
                if (start < end)
                {
                    if (cur >= start && cur < end) return true;
                }
                else if (start > end)
                {
                    // 日を跨ぐ時間帯 (例: 22:00 〜 01:00)
                    if (cur >= start || cur < end) return true;
                }
            }
        }
        return false;
    }

    public static double AllowanceMinutes(AppSettings settings, DayUsage todayUsage)
    {
        return settings.DailyLimitMinutes + todayUsage.ExtraMinutes;
    }

    public static Mode ComputeMode(AppSettings settings, DayUsage todayUsage, DateTime now)
    {
        if (IsStudyTime(settings, now))
        {
            return Mode.StudyTime;
        }
        if (todayUsage.UsedMinutes >= AllowanceMinutes(settings, todayUsage))
        {
            return Mode.TimeExceeded;
        }
        return Mode.Normal;
    }

    public static double RemainingMinutes(AppSettings settings, DayUsage todayUsage)
    {
        return Math.Max(0, AllowanceMinutes(settings, todayUsage) - todayUsage.UsedMinutes);
    }

    /// <summary>次の勉強時間帯を人間向けの文字列で返す（今日〜7日先まで走査）。</summary>
    public static string? NextStudySlot(AppSettings settings, DateTime now)
    {
        for (var dayOffset = 0; dayOffset < 7; dayOffset++)
        {
            var date = now.Date.AddDays(dayOffset);
            var weekdayIndex = ((int)date.DayOfWeek + 6) % 7;
            var key = AppSettings.WeekdayKeys[weekdayIndex];

            if (!settings.WeekdayStudyTimes.TryGetValue(key, out var ranges) || ranges.Count == 0)
            {
                continue;
            }

            foreach (var range in ranges.OrderBy(r => r.Start))
            {
                if (!TryParseTime(range.Start, out var startTime))
                {
                    continue;
                }
                if (dayOffset == 0 && startTime <= now.TimeOfDay)
                {
                    continue; // 今日の分はもう始まっている/終わっている
                }

                if (dayOffset == 0)
                {
                    return $"今日 {range.Start}〜";
                }
                if (dayOffset == 1)
                {
                    return $"明日 {range.Start}〜";
                }
                return $"{WeekdayLabelsJa[weekdayIndex]}曜 {range.Start}〜";
            }
        }
        return null;
    }

    public static TimeSpan? TimeUntilExam(AppSettings settings, DateTime now)
    {
        var diff = settings.ExamDate - now;
        return diff;
    }

    /// <summary>「77週5日15時間5分」形式で残り時間を表す（Android版のTimeCalculatorと同じ内訳）。</summary>
    public static string FormatExamCountdown(TimeSpan diff)
    {
        var totalMinutes = (long)diff.TotalMinutes;

        var weeks = totalMinutes / (7 * 24 * 60);
        var remainder = totalMinutes % (7 * 24 * 60);

        var days = remainder / (24 * 60);
        remainder %= 24 * 60;

        var hours = remainder / 60;
        var minutes = remainder % 60;

        return $"{weeks}週{days}日{hours}時間{minutes}分";
    }
}
