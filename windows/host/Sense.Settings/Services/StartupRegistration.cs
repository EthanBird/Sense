using System.IO;
using Microsoft.Win32;

namespace Sense.Settings.Services;

public static class StartupRegistration
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "SenseAgentHost";

    public static void Apply(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
        if (!enabled)
        {
            key.DeleteValue(ValueName, throwOnMissingValue: false);
            return;
        }

        var executable = Path.Combine(AppContext.BaseDirectory, "Sense.AgentHost.exe");
        if (File.Exists(executable))
        {
            key.SetValue(ValueName, $"\"{executable}\"", RegistryValueKind.String);
        }
    }
}
