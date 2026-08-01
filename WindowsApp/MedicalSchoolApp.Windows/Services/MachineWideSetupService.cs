using System.Diagnostics;
using System.IO;
using Microsoft.Win32;

namespace MedicalSchoolApp.Windows.Services;

/// <summary>
/// 「全アカウントで保護」機能。HKEY_LOCAL_MACHINEのRunキーに登録することで、
/// このPCにログインするどのWindowsアカウントでも自動的にアプリが起動するようにする。
/// HKLMへの書き込み・ProgramDataフォルダのACL設定には管理者権限が必要なため、
/// 実際の登録処理は本体exeを --register-machine-wide 引数付きでUAC昇格再起動して行う。
/// </summary>
public static class MachineWideSetupService
{
    private const string RunKeyPath = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Run";
    private const string MainValueName = "MedicalSchoolAppWindows";
    private const string WatchdogValueName = "MedicalSchoolAppWindowsWatchdog";

    public const string RegisterArg = "--register-machine-wide";
    public const string UnregisterArg = "--unregister-machine-wide";

    public static string ProgramDataDir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
        "MedicalSchoolApp.Windows");

    public static bool IsMachineWideEnabled => Directory.Exists(ProgramDataDir);

    /// <summary>
    /// UAC昇格を要求してこのexeを--register-machine-wide付きで再起動し、完了を待つ。
    /// 戻り値: 昇格・登録が成功したか（ユーザーがUACをキャンセルした場合はfalse）。
    /// </summary>
    public static bool EnableMachineWide() => RunElevated(RegisterArg);

    public static bool DisableMachineWide() => RunElevated(UnregisterArg);

    private static bool RunElevated(string arg)
    {
        try
        {
            var exePath = Environment.ProcessPath ?? Process.GetCurrentProcess().MainModule!.FileName;
            var psi = new ProcessStartInfo(exePath, arg)
            {
                UseShellExecute = true,
                Verb = "runas",
            };
            using var process = Process.Start(psi);
            process?.WaitForExit();
            return process is { ExitCode: 0 };
        }
        catch (System.ComponentModel.Win32Exception)
        {
            // ユーザーがUACのダイアログでキャンセルした場合など
            return false;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>
    /// 管理者権限で実行された時だけ呼ばれる本体。UIは出さずに処理して即終了する。
    /// 万一(想定外に)管理者権限が無い状態で呼ばれても、例外でアプリごとクラッシュしないよう
    /// 内部で握りつぶす（呼び出し元はプロセスの正常終了=成功とみなす簡易的な作りのため）。
    /// レジストリ登録が両方成功した場合のみProgramDataフォルダを作る
    /// （IsMachineWideEnabledの判定=フォルダ存在、を登録成功と一致させるため）。
    /// </summary>
    public static void RunElevatedRegistration()
    {
        var exeDir = AppContext.BaseDirectory;
        var mainExe = Path.Combine(exeDir, "MedicalSchoolApp.Windows.exe");
        var watchdogExe = Path.Combine(exeDir, "MedicalSchoolApp.Windows.Watchdog.exe");

        var mainOk = WriteRunKey(MainValueName, mainExe);
        var watchdogOk = WriteRunKey(WatchdogValueName, watchdogExe);

        try
        {
            Directory.CreateDirectory(ProgramDataDir);
            GrantUsersFullControl(ProgramDataDir);
            GrantUsersFullControl(exeDir);
            CreateCommonStartupShortcut(mainExe);
        }
        catch
        {
            // ignore
        }
    }

    private static void CreateCommonStartupShortcut(string targetExe)
    {
        try
        {
            var commonStartup = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
                @"Microsoft\Windows\Start Menu\Programs\StartUp");
            if (Directory.Exists(commonStartup))
            {
                var lnkPath = Path.Combine(commonStartup, "医学部合格アプリ.lnk");
                Type? shellType = Type.GetTypeFromProgID("WScript.Shell");
                if (shellType != null)
                {
                    dynamic shell = Activator.CreateInstance(shellType)!;
                    dynamic shortcut = shell.CreateShortcut(lnkPath);
                    shortcut.TargetPath = targetExe;
                    shortcut.WorkingDirectory = Path.GetDirectoryName(targetExe);
                    shortcut.Description = "医学部合格アプリ";
                    shortcut.Save();
                }
            }
        }
        catch
        {
            // ignore
        }
    }

    public static void RunElevatedUnregistration()
    {
        try
        {
            RemoveRunKey(MainValueName);
            RemoveRunKey(WatchdogValueName);
            // ProgramData配下の設定・履歴ファイルは誤操作でのデータ消失を避けるため削除しない
        }
        catch
        {
            // ignore
        }
    }

    private static bool WriteRunKey(string valueName, string exePath)
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(RunKeyPath, writable: true);
            if (key is null) return false;
            key.SetValue(valueName, $"\"{exePath}\"");
            return true;
        }
        catch
        {
            // ignore（管理者権限が無い場合など）
            return false;
        }
    }

    private static void RemoveRunKey(string valueName)
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(RunKeyPath, writable: true);
            if (key?.GetValue(valueName) != null)
            {
                key.DeleteValue(valueName, throwOnMissingValue: false);
            }
        }
        catch
        {
            // ignore
        }
    }

    private static void GrantUsersFullControl(string path)
    {
        try
        {
            var psi = new ProcessStartInfo("icacls.exe", $"\"{path}\" /grant Users:(OI)(CI)M /T")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            using var process = Process.Start(psi);
            process?.WaitForExit(10000);
        }
        catch
        {
            // 失敗してもフォルダ自体は作成済みなので致命的ではない
        }
    }
}
