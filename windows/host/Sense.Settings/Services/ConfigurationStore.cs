using System.IO;
using System.Text;
using System.Text.Json;
using Sense.Settings.Models;

namespace Sense.Settings.Services;

public sealed class ConfigurationStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    public string DataDirectory { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Sense");

    public string ConfigurationPath => Path.Combine(DataDirectory, "settings.json");

    public SenseConfiguration Load()
    {
        try
        {
            if (!File.Exists(ConfigurationPath))
            {
                return new SenseConfiguration();
            }

            var json = File.ReadAllText(ConfigurationPath, Encoding.UTF8);
            return JsonSerializer.Deserialize<SenseConfiguration>(json, JsonOptions)
                ?? new SenseConfiguration();
        }
        catch (JsonException)
        {
            return new SenseConfiguration();
        }
        catch (IOException)
        {
            return new SenseConfiguration();
        }
    }

    public void Save(SenseConfiguration configuration)
    {
        Directory.CreateDirectory(DataDirectory);
        var temporary = ConfigurationPath + ".tmp";
        var json = JsonSerializer.Serialize(configuration, JsonOptions);
        File.WriteAllText(temporary, json, new UTF8Encoding(false));
        File.Move(temporary, ConfigurationPath, true);
        WriteNativeProjection(configuration);
    }

    private void WriteNativeProjection(SenseConfiguration configuration)
    {
        var path = Path.Combine(DataDirectory, "settings.ini");
        var text = $"""
            [input]
            default_chinese_mode={(configuration.DefaultChineseMode ? 1 : 0)}
            chinese_punctuation={(configuration.UseChinesePunctuation ? 1 : 0)}

            [appearance]
            candidate_theme={Sanitize(configuration.CandidateTheme)}

            [agent]
            enabled={(configuration.AgentEnabled ? 1 : 0)}
            hotkey={Sanitize(configuration.AgentHotkey)}
            """;
        File.WriteAllText(path, text + Environment.NewLine, new UTF8Encoding(false));
    }

    private static string Sanitize(string value) =>
        value.Replace("\r", string.Empty, StringComparison.Ordinal)
            .Replace("\n", string.Empty, StringComparison.Ordinal)
            .Replace("=", string.Empty, StringComparison.Ordinal);
}
