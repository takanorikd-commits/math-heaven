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

    /// <summary>
    /// 全アカウントから実行できるよう、exe本体一式をコピーする先。
    /// 元の実行場所（例: 特定アカウントのDocuments配下）は他アカウントから
    /// アクセスできないため、ここに置き直したものをHKLM Runキーに登録する。
    /// </summary>
    public static string BinDir => Path.Combine(ProgramDataDir, "bin");

    /// <summary>
    /// 実際にHKLMのRunキーが登録されているかで判定する（ProgramDataフォルダの有無ではない）。
    /// フォルダは無効化後も設定・履歴を残すため消さない一方、レジストリキーは
    /// 無効化で確実に消えるため、ボタンの状態表示や再登録の判定はこちらを信頼できる。
    /// レジストリの読み取り自体は管理者権限不要。
    /// </summary>
    public static bool IsMachineWideEnabled
    {
        get
        {
            try
            {
                using var key = Registry.LocalMachine.OpenSubKey(RunKeyPath, writable: false);
                return key?.GetValue(MainValueName) != null;
            }
            catch
            {
                return false;
            }
        }
    }

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
    /// </summary>
    public static void RunElevatedRegistration()
    {
        try
        {
            Directory.CreateDirectory(ProgramDataDir);
            // Users権限を(OI)(CI)で継承付与しておくことで、この後コピーするbin配下のファイルにも
            // 自動的に同じ権限が引き継がれる（すべてのアカウントが実行できるようにするため）。
            GrantUsersFullControl(ProgramDataDir);

            // 元の実行場所（特定アカウントのDocuments配下等）は他アカウントからアクセスできないため、
            // 全アカウントが読み書きできるProgramData配下にexe一式をコピーし、そちらを登録する。
            CopyDirectory(AppContext.BaseDirectory, BinDir);
        }
        catch
        {
            return;
        }

        WriteRunKey(MainValueName, Path.Combine(BinDir, "MedicalSchoolApp.Windows.exe"));
        WriteRunKey(WatchdogValueName, Path.Combine(BinDir, "MedicalSchoolApp.Windows.Watchdog.exe"));
    }

    private static void CopyDirectory(string sourceDir, string destDir)
    {
        Directory.CreateDirectory(destDir);
        foreach (var file in Directory.GetFiles(sourceDir))
        {
            var destFile = Path.Combine(destDir, Path.GetFileName(file));
            try
            {
                File.Copy(file, destFile, overwrite: true);
            }
            catch
            {
                // 実行中の自分自身のexe/dll等、ロックされていてコピーできないものは無視する
            }
        }
        foreach (var dir in Directory.GetDirectories(sourceDir))
        {
            CopyDirectory(dir, Path.Combine(destDir, Path.GetFileName(dir)));
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
