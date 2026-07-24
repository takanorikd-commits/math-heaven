using System.Diagnostics;
using Microsoft.Win32;

namespace MedicalSchoolApp.Windows.Watchdog;

/// <summary>
/// メインアプリ(MedicalSchoolApp.Windows.exe)が子供によってタスクマネージャーで
/// 強制終了された場合に、数秒以内に自動で再起動する常駐監視プロセス。
/// 保護者が正規に「終了」した場合は shutdown.flag が置かれるため再起動しない。
/// </summary>
internal static class Program
{
    private const string MainProcessName = "MedicalSchoolApp.Windows";
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunValueName = "MedicalSchoolAppWindowsWatchdog";
    private static readonly TimeSpan RelaunchCooldown = TimeSpan.FromSeconds(3);

    private static string AppDataDir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "MedicalSchoolApp.Windows");

    private static string ShutdownFlagPath => Path.Combine(AppDataDir, "shutdown.flag");
    private static string LogPath => Path.Combine(AppDataDir, "watchdog.log");

    private static void Main()
    {
        Directory.CreateDirectory(AppDataDir);

        // 起動時に前回セッションの古いフラグが残っていたら消しておく
        TryDeleteStaleFlag();

        RegisterAutostart();
        Log("Watchdog started.");

        var lastRelaunchAttempt = DateTime.MinValue;

        while (true)
        {
            try
            {
                if (File.Exists(ShutdownFlagPath))
                {
                    Log("Shutdown flag detected. Watchdog exiting without relaunch.");
                    break;
                }

                var mainRunning = Process.GetProcessesByName(MainProcessName).Any(p => !SafeHasExited(p));
                if (!mainRunning && DateTime.Now - lastRelaunchAttempt > RelaunchCooldown)
                {
                    lastRelaunchAttempt = DateTime.Now;
                    RelaunchMainApp();
                }
            }
            catch (Exception ex)
            {
                Log($"Watchdog loop error: {ex.Message}");
            }

            Thread.Sleep(1000);
        }
    }

    private static bool SafeHasExited(Process p)
    {
        try { return p.HasExited; }
        catch { return true; }
    }

    private static void RelaunchMainApp()
    {
        var exeDir = AppContext.BaseDirectory;
        var mainExePath = Path.Combine(exeDir, MainProcessName + ".exe");
        if (!File.Exists(mainExePath))
        {
            Log($"Main app exe not found at {mainExePath}");
            return;
        }

        try
        {
            Process.Start(new ProcessStartInfo(mainExePath) { UseShellExecute = true });
            Log("Main app relaunched.");
        }
        catch (Exception ex)
        {
            Log($"Failed to relaunch main app: {ex.Message}");
        }
    }

    private static void TryDeleteStaleFlag()
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

    private static void RegisterAutostart()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, writable: true);
            if (key is null) return;

            var exePath = Path.Combine(AppContext.BaseDirectory, "MedicalSchoolApp.Windows.Watchdog.exe");
            key.SetValue(RunValueName, $"\"{exePath}\"");
        }
        catch
        {
            // ignore
        }
    }

    private static void Log(string message)
    {
        try
        {
            var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {message}{Environment.NewLine}";
            File.AppendAllText(LogPath, line);

            // ログが肥大化しすぎないよう簡易ローテーション
            var info = new FileInfo(LogPath);
            if (info.Exists && info.Length > 512 * 1024)
            {
                File.Delete(LogPath);
            }
        }
        catch
        {
            // ignore
        }
    }
}
