using System.IO;
using System.Text.Json;
using MedicalSchoolApp.Windows.Models;

namespace MedicalSchoolApp.Windows.Services;

public static class SettingsService
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    /// <summary>
    /// 「全アカウントで保護」が有効な場合は%ProgramData%配下（全ユーザー共有）、
    /// そうでなければ従来通り%APPDATA%配下（アカウント専用）を使う。
    /// ProgramData側のフォルダは MachineWideSetupService が管理者権限で作成・ACL付与する。
    /// </summary>
    public static string AppDataDir
    {
        get
        {
            var sharedDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
                "MedicalSchoolApp.Windows");
            if (Directory.Exists(sharedDir))
            {
                return sharedDir;
            }

            return Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "MedicalSchoolApp.Windows");
        }
    }

    private static string SettingsPath => Path.Combine(AppDataDir, "settings.json");

    /// <summary>
    /// 設定ファイルの最終更新日時（UTC）。別セッション（別Windowsアカウント）で動いている
    /// 他のインスタンスが設定を保存したかどうかを検知するために使う。ファイルが無ければ最小値。
    /// </summary>
    public static DateTime GetLastWriteTimeUtc()
    {
        try
        {
            return File.Exists(SettingsPath) ? File.GetLastWriteTimeUtc(SettingsPath) : DateTime.MinValue;
        }
        catch
        {
            return DateTime.MinValue;
        }
    }

    public static AppSettings Load()
    {
        Directory.CreateDirectory(AppDataDir);
        var mainPath = SettingsPath;
        var userAppDataPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "MedicalSchoolApp.Windows", "settings.json");

        if (!File.Exists(mainPath) && File.Exists(userAppDataPath))
        {
            try
            {
                var userJson = File.ReadAllText(userAppDataPath);
                File.WriteAllText(mainPath, userJson);
            }
            catch { }
        }

        if (!File.Exists(mainPath))
        {
            var defaults = AppSettings.CreateDefault();
            Save(defaults);
            return defaults;
        }

        try
        {
            var json = File.ReadAllText(mainPath);
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
        try
        {
            File.WriteAllText(SettingsPath, json);
        }
        catch { }

        // AppData側にもバックアップ同期
        var userAppDataDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "MedicalSchoolApp.Windows");
        if (Directory.Exists(userAppDataDir))
        {
            try
            {
                File.WriteAllText(Path.Combine(userAppDataDir, "settings.json"), json);
            }
            catch { }
        }
    }
}
