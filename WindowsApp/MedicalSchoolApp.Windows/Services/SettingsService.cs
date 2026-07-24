using System.IO;
using System.Text.Json;
using MedicalSchoolApp.Windows.Models;

namespace MedicalSchoolApp.Windows.Services;

public static class SettingsService
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    public static string AppDataDir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "MedicalSchoolApp.Windows");

    private static string SettingsPath => Path.Combine(AppDataDir, "settings.json");

    public static AppSettings Load()
    {
        Directory.CreateDirectory(AppDataDir);
        if (!File.Exists(SettingsPath))
        {
            var defaults = AppSettings.CreateDefault();
            Save(defaults);
            return defaults;
        }

        try
        {
            var json = File.ReadAllText(SettingsPath);
            var settings = JsonSerializer.Deserialize<AppSettings>(json);
            return settings ?? AppSettings.CreateDefault();
        }
        catch
        {
            return AppSettings.CreateDefault();
        }
    }

    public static void Save(AppSettings settings)
    {
        Directory.CreateDirectory(AppDataDir);
        var json = JsonSerializer.Serialize(settings, JsonOptions);
        File.WriteAllText(SettingsPath, json);
    }
}
