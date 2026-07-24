using System.Diagnostics;
using System.IO;
using Microsoft.Win32;

namespace MedicalSchoolApp.Windows.Services;

/// <summary>
/// Watchdog(MedicalSchoolApp.Windows.Watchdog.exe)との連携。
/// 子供がタスクマネージャーでこのアプリを強制終了した場合、Watchdogが数秒以内に再起動する。
/// 保護者が正規に終了する場合はshutdown.flagを先に書き込み、Watchdog側の再起動を止める。
/// </summary>
public static class WatchdogService
{
    private const string WatchdogProcessName = "MedicalSchoolApp.Windows.Watchdog";
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunValueName = "MedicalSchoolAppWindowsWatchdog";

    public static string ShutdownFlagPath => Path.Combine(SettingsService.AppDataDir, "shutdown.flag");

    public static void ClearStaleShutdownFlag()
    {
        try
        {
            if (File.Exists(ShutdownFlagPath))
            {
                File.Delete(ShutdownFlagPath);
            }
        }
        catch
        {
            // ignore
        }
    }

    public static void WriteShutdownFlag()
    {
        try
        {
            Directory.CreateDirectory(SettingsService.AppDataDir);
            File.WriteAllText(ShutdownFlagPath, DateTime.Now.ToString("O"));
        }
        catch
        {
            // ignore
        }
    }

    public static void ApplyAutostart(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, writable: true);
            if (key is null) return;

            if (enabled)
            {
                var exePath = Path.Combine(AppContext.BaseDirectory, "MedicalSchoolApp.Windows.Watchdog.exe");
                key.SetValue(RunValueName, $"\"{exePath}\"");
            }
            else if (key.GetValue(RunValueName) != null)
            {
                key.DeleteValue(RunValueName, throwOnMissingValue: false);
            }
        }
        catch
        {
            // ignore
        }
    }

    public static bool IsRunning()
    {
        return Process.GetProcessesByName(WatchdogProcessName).Any(p =>
        {
            try { return !p.HasExited; }
            catch { return false; }
        });
    }

    /// <summary>Watchdogが動いていなければ起動する（意図的終了中は何もしない）。</summary>
    public static void EnsureRunning()
    {
        if (File.Exists(ShutdownFlagPath)) return;
        if (IsRunning()) return;

        var exePath = Path.Combine(AppContext.BaseDirectory, "MedicalSchoolApp.Windows.Watchdog.exe");
        if (!File.Exists(exePath)) return;

        try
        {
            Process.Start(new ProcessStartInfo(exePath) { UseShellExecute = true });
        }
        catch
        {
            // ignore
        }
    }
}
