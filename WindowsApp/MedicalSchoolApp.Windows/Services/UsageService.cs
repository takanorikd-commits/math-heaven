using System.IO;
using System.Text.Json;
using MedicalSchoolApp.Windows.Models;

namespace MedicalSchoolApp.Windows.Services;

public static class UsageService
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    private static string UsagePath => Path.Combine(SettingsService.AppDataDir, "usage_history.json");

    public static string TodayKey() => DateTime.Now.ToString("yyyy-MM-dd");

    public static Dictionary<string, DayUsage> Load()
    {
        Directory.CreateDirectory(SettingsService.AppDataDir);
        if (!File.Exists(UsagePath))
        {
            return new Dictionary<string, DayUsage>();
        }

        try
        {
            var json = File.ReadAllText(UsagePath);
            var history = JsonSerializer.Deserialize<Dictionary<string, DayUsage>>(json);
            return history ?? new Dictionary<string, DayUsage>();
        }
        catch
        {
            return new Dictionary<string, DayUsage>();
        }
    }

    public static void Save(Dictionary<string, DayUsage> history)
    {
        Directory.CreateDirectory(SettingsService.AppDataDir);
        var json = JsonSerializer.Serialize(history, JsonOptions);
        File.WriteAllText(UsagePath, json);
    }

    public static DayUsage GetToday(Dictionary<string, DayUsage> history)
    {
        var key = TodayKey();
        if (history.TryGetValue(key, out var usage))
        {
            return usage;
        }
        return new DayUsage();
    }

    public static DayUsage GetOrCreateToday(Dictionary<string, DayUsage> history)
    {
        var key = TodayKey();
        if (!history.TryGetValue(key, out var usage))
        {
            usage = new DayUsage();
            history[key] = usage;
        }
        return usage;
    }
}
