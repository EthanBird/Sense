using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.CompilerServices;
using System.Windows;
using Sense.Settings.Models;
using Sense.Settings.Services;

namespace Sense.Settings.ViewModels;

public sealed class MainViewModel : INotifyPropertyChanged
{
    private readonly ConfigurationStore _store = new();
    private readonly SenseConfiguration _configuration;
    private string _saveStatus = "配置仅在显式保存后生效";

    public MainViewModel()
    {
        _configuration = _store.Load();
        Installation = InstallationProbe.Probe();
        SaveCommand = new RelayCommand(Save);
        OpenDataCommand = new RelayCommand(OpenDataDirectory);
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public InstallationState Installation { get; }
    public RelayCommand SaveCommand { get; }
    public RelayCommand OpenDataCommand { get; }

    public string RegistrationBadge => Installation.Registered ? "TSF ONLINE" : "TSF SETUP";
    public string DictionaryBadge => Installation.DictionaryPresent ? "SPLX LOCAL" : "SPLX MISSING";
    public string InstallationSummary => Installation.Summary;

    public bool DefaultChineseMode
    {
        get => _configuration.DefaultChineseMode;
        set
        {
            if (_configuration.DefaultChineseMode == value) return;
            _configuration.DefaultChineseMode = value;
            MarkDirty();
        }
    }

    public bool UseChinesePunctuation
    {
        get => _configuration.UseChinesePunctuation;
        set
        {
            if (_configuration.UseChinesePunctuation == value) return;
            _configuration.UseChinesePunctuation = value;
            MarkDirty();
        }
    }

    public bool AgentEnabled
    {
        get => _configuration.AgentEnabled;
        set
        {
            if (_configuration.AgentEnabled == value) return;
            _configuration.AgentEnabled = value;
            MarkDirty();
        }
    }

    public bool StartAgentWithWindows
    {
        get => _configuration.StartAgentWithWindows;
        set
        {
            if (_configuration.StartAgentWithWindows == value) return;
            _configuration.StartAgentWithWindows = value;
            MarkDirty();
        }
    }

    public string ProviderEndpoint
    {
        get => _configuration.ProviderEndpoint;
        set
        {
            if (_configuration.ProviderEndpoint == value) return;
            _configuration.ProviderEndpoint = value;
            MarkDirty();
        }
    }

    public string ProviderModel
    {
        get => _configuration.ProviderModel;
        set
        {
            if (_configuration.ProviderModel == value) return;
            _configuration.ProviderModel = value;
            MarkDirty();
        }
    }

    public string AgentHotkey
    {
        get => _configuration.AgentHotkey;
        set
        {
            if (_configuration.AgentHotkey == value) return;
            _configuration.AgentHotkey = value;
            MarkDirty();
        }
    }

    public string SaveStatus
    {
        get => _saveStatus;
        private set
        {
            _saveStatus = value;
            OnPropertyChanged();
        }
    }

    private void Save()
    {
        try
        {
            _store.Save(_configuration);
            StartupRegistration.Apply(
                _configuration.AgentEnabled && _configuration.StartAgentWithWindows);
            SaveStatus = $"已保存 · {DateTime.Now:HH:mm:ss}";
        }
        catch (IOException exception)
        {
            SaveStatus = $"保存失败 · {exception.Message}";
        }
        catch (UnauthorizedAccessException exception)
        {
            SaveStatus = $"保存失败 · {exception.Message}";
        }
    }

    private void OpenDataDirectory()
    {
        Directory.CreateDirectory(_store.DataDirectory);
        Process.Start(new ProcessStartInfo
        {
            FileName = _store.DataDirectory,
            UseShellExecute = true,
        });
    }

    private void MarkDirty()
    {
        SaveStatus = "有尚未保存的更改";
    }

    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
