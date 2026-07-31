namespace Sense.Settings.Models;

public sealed class SenseConfiguration
{
    public int SchemaVersion { get; set; } = 1;
    public bool DefaultChineseMode { get; set; } = true;
    public bool UseChinesePunctuation { get; set; } = true;
    public string CandidateTheme { get; set; } = "arctic";
    public bool AgentEnabled { get; set; }
    public bool StartAgentWithWindows { get; set; }
    public string ProviderEndpoint { get; set; } = string.Empty;
    public string ProviderModel { get; set; } = string.Empty;
    public string AgentHotkey { get; set; } = "Ctrl+Win+Space";
}
