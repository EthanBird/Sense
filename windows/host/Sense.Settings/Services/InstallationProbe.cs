using System.IO;
using Microsoft.Win32;

namespace Sense.Settings.Services;

public sealed record InstallationState(
    bool Registered,
    bool DictionaryPresent,
    string Summary);

public static class InstallationProbe
{
    private const string ClassId = "{D24D7D5D-A4B3-4C28-AD93-C04E0C6B3501}";

    public static InstallationState Probe()
    {
        var registered = false;
        try
        {
            using var key = Registry.ClassesRoot.OpenSubKey(
                $@"CLSID\{ClassId}\InprocServer32");
            registered = key?.GetValue(null) is string path && File.Exists(path);
        }
        catch
        {
            registered = false;
        }

        var dictionaryPresent = new[]
        {
            Path.Combine(
                AppContext.BaseDirectory,
                "native",
                "x64",
                "data",
                "pinyin_lexicon.bin"),
            Path.Combine(
                AppContext.BaseDirectory,
                "native",
                "x86",
                "data",
                "pinyin_lexicon.bin"),
            Path.Combine(
                AppContext.BaseDirectory,
                "data",
                "pinyin_lexicon.bin"),
        }.Any(File.Exists);

        var summary = registered
            ? "TSF 已注册，系统输入切换器中可用"
            : "TSF 尚未注册，运行安装脚本后启用";
        return new InstallationState(registered, dictionaryPresent, summary);
    }
}
