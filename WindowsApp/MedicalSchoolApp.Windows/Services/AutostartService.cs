using Microsoft.Win32;

namespace MedicalSchoolApp.Windows.Services;

/// <summary>
/// HKCUのRunキーでログイン時自動起動を制御する（管理者権限不要）。
/// </summary>
public static class AutostartService
{
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "MedicalSchoolAppWindows";

    public static void Apply(bool enabled)
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, writable: true);
        if (key is null)
        {
            return;
        }

        if (enabled)
        {
            var exePath = Environment.ProcessPath ?? Environment.GetCommandLineArgs()[0];
            key.SetValue(ValueName, $"\"{exePath}\"");
        }
        else
        {
            if (key.GetValue(ValueName) != null)
            {
                key.DeleteValue(ValueName, throwOnMissingValue: false);
            }
        }
    }
}
