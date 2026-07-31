using System.Diagnostics;
using System.Runtime.InteropServices;
using MedicalSchoolApp.Windows.Views;

namespace MedicalSchoolApp.Windows.Services;

/// <summary>
/// 1秒間隔で呼ばれる監視ロジック。前面プロセスの取得のみP/Invokeを使い、
/// 可視ウィンドウの列挙・終了はSystem.Diagnostics.Processで完結させる。
/// </summary>
public static class ProcessMonitor
{
    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);

    /// <summary>
    /// 誤って閉じるとOSやシェル、ChatGPT画面(WebView2)、Chrome Remote Desktopが
    /// 壊れるおそれのあるプロセス。常に保護する。
    /// 開発用ツール（ターミナル・IDE等）はDebugビルドにのみ含め、Releaseビルド
    /// （実際に子供のPCへ配布する版）には含めない。taskmgr.exe/cmd.exe/powershell.exe等を
    /// 本番でも保護してしまうと、子供がそこから制限を回避できてしまうため。
    /// </summary>
    public static readonly HashSet<string> SafeList = BuildSafeList();

    private static HashSet<string> BuildSafeList()
    {
        var list = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            "medicalschoolapp.windows.exe",
            "medicalschoolapp.windows.watchdog.exe",
            "explorer.exe",
            "conhost.exe",
            "dwm.exe", "sihost.exe", "shellexperiencehost.exe",
            "searchhost.exe", "startmenuexperiencehost.exe", "textinputhost.exe",
            "lockapp.exe", "logonui.exe", "consent.exe", "systemsettings.exe",
            "applicationframehost.exe",
            // WebView2（このアプリ自身のChatGPT画面が使用）
            "msedgewebview2.exe",
            // Chrome Remote Desktop（保護者が制限モード中も画面を確認できるようにする）
            "remoting_host.exe", "remoting_desktop.exe", "remoting_native_messaging_host.exe",
            "remoting_start_host.exe", "remote_assistance_host.exe", "remote_assistance_host_uiaccess.exe",
            "remoting_crashpad_handler.exe", "remote_open_url.exe", "remote_security_key.exe",
            "remote_webauthn.exe",
        };

#if DEBUG
        // 開発中にAntigravity/VSCode/ターミナル等が誤って強制終了されないようにするための除外。
        // Releaseビルドには絶対に含めない（taskmgr/cmd/powershellが保護されると回避手段になるため）。
        list.UnionWith(new[]
        {
            "taskmgr.exe", "cmd.exe", "powershell.exe", "pwsh.exe", "windowsterminal.exe",
            "antigravity.exe", "agy.exe", "node.exe", "code.exe", "devenv.exe",
        });
#endif

        return list;
    }

    private static readonly Dictionary<int, int> CloseAttempts = new();

    public static void Tick()
    {
        // 「全アカウントで保護」時は複数プロセスがsettings.jsonを共有するため、
        // 他アカウントで行われた設定変更（勉強時間・上限分等）を反映するために毎回読み直す。
        // Settingsはこのプロセス自身が随時書き足すものではないため、常に読み直して問題ない。
        AppState.ReloadSettings();

        var settings = AppState.Settings;
        var today = AppState.TodayUsage;
        var now = DateTime.Now;

        // 疑似制限モード（動作確認用）が有効な間は、実際の時間・勉強時間に関わらず制限モード扱いにする
        var mode = AppState.PseudoRestrictedMode ? Mode.TimeExceeded : ModeService.ComputeMode(settings, today, now);
        var allowedSet = new HashSet<string>(settings.AllowedApps.Select(a => a.ToLowerInvariant()), StringComparer.OrdinalIgnoreCase);
        var ownPid = Environment.ProcessId;

        var stillFlagged = new HashSet<int>();

        foreach (var process in Process.GetProcesses())
        {
            try
            {
                if (process.Id == ownPid) continue;
                if (process.MainWindowHandle == IntPtr.Zero) continue;
                if (string.IsNullOrEmpty(process.MainWindowTitle)) continue;

                var exeName = process.ProcessName.ToLowerInvariant() + ".exe";
                if (SafeList.Contains(exeName)) continue;

                var shouldClose = mode != Mode.Normal && !allowedSet.Contains(exeName);
                if (!shouldClose) continue;

                stillFlagged.Add(process.Id);
                CloseAttempts.TryGetValue(process.Id, out var attempts);
                attempts++;
                CloseAttempts[process.Id] = attempts;

                if (attempts <= 2)
                {
                    process.CloseMainWindow();
                }
                else
                {
                    process.Kill();
                }
            }
            catch
            {
                // プロセスが既に終了している、アクセスが拒否された等は無視する
            }
            finally
            {
                process.Dispose();
            }
        }

        foreach (var pid in CloseAttempts.Keys.Where(pid => !stillFlagged.Contains(pid)).ToList())
        {
            CloseAttempts.Remove(pid);
        }

        if (mode == Mode.Normal)
        {
            var foregroundExe = GetForegroundExeName(ownPid);
            if (!string.IsNullOrEmpty(foregroundExe) && !allowedSet.Contains(foregroundExe))
            {
                today.UsedMinutes += 1.0 / 60.0;
            }
        }

        if (mode == Mode.Normal)
        {
            App.HideBlockWindow();
        }
        else
        {
            App.ShowBlockWindow();
        }

        if (now.Second % 5 == 0)
        {
            AppState.SaveUsage();
            // 自分の増分を書き出した直後に読み直すことで、他アカウントの更新
            // （延長・リセット等）も取り込む。書き出し前に読み直すと自分の増分を失うため順序が重要。
            AppState.ReloadUsage();
        }

        AppState.RaiseUpdated();
    }

    private static string? GetForegroundExeName(int ownPid)
    {
        try
        {
            var hwnd = GetForegroundWindow();
            if (hwnd == IntPtr.Zero) return null;

            GetWindowThreadProcessId(hwnd, out var pid);
            if (pid == 0 || pid == ownPid) return null;

            using var process = Process.GetProcessById((int)pid);
            return process.ProcessName.ToLowerInvariant() + ".exe";
        }
        catch
        {
            return null;
        }
    }
}
