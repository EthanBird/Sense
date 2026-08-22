#ifndef AppVersion
  #define AppVersion "0.4.13"
#endif
#ifndef StageDir
  #error StageDir is required
#endif
#ifndef OutputDir
  #define OutputDir "."
#endif
#ifndef SourceCommit
  #define SourceCommit "unknown"
#endif

[Setup]
AppId={{A2EF1E20-83D7-49F5-9DC5-9538F55B1D8E}
AppName=Sense Mic
AppVersion={#AppVersion}
AppPublisher=Sense Project
AppPublisherURL=https://github.com/EthanBird/Sense
AppSupportURL=https://github.com/EthanBird/Sense/issues
AppUpdatesURL=https://github.com/EthanBird/Sense/releases
#ifdef DriverStage
DefaultDirName={autopf}\Sense Mic
PrivilegesRequired=admin
#else
DefaultDirName={localappdata}\Programs\Sense Mic
PrivilegesRequired=lowest
#endif
DefaultGroupName=Sense Mic
DisableProgramGroupPage=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutputDir}
OutputBaseFilename=SenseMicSetup-v{#AppVersion}-windows-x64
SetupIconFile=..\..\windows\native\tsf\resources\sense.ico
UninstallDisplayIcon={app}\sense-mic-gui.exe
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
VersionInfoVersion={#AppVersion}.0
VersionInfoCompany=Sense Project
VersionInfoDescription=Sense Mic Windows Setup
VersionInfoProductName=Sense Mic
VersionInfoProductVersion={#AppVersion}
VersionInfoCopyright=GPL-3.0-only

[Languages]
Name: "chinesesimp"; MessagesFile: "ChineseSimplified.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "快捷方式："; Flags: unchecked
Name: "startup"; Description: "登录 Windows 后启动 Sense Mic"; GroupDescription: "自动启动："; Flags: unchecked

[Files]
Source: "{#StageDir}\sense-mic-gui.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#StageDir}\sense-mic.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#StageDir}\README.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#StageDir}\LICENSE"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#StageDir}\NOTICE"; DestDir: "{app}"; Flags: ignoreversion
#ifndef DriverStage
Source: "{#StageDir}\CLIENT-ONLY-NOTICE.txt"; DestDir: "{app}"; Flags: ignoreversion
#endif
Source: "{#StageDir}\BUILD-INFO.txt"; DestDir: "{app}"; Flags: ignoreversion
#ifdef DriverStage
Source: "{#DriverStage}\*"; DestDir: "{app}\driver\windows\x64"; Flags: ignoreversion recursesubdirs createallsubdirs
#endif

[Icons]
Name: "{group}\Sense Mic"; Filename: "{app}\sense-mic-gui.exe"; WorkingDir: "{app}"
Name: "{group}\Sense Mic 命令行"; Filename: "{app}\sense-mic.exe"; WorkingDir: "{app}"
Name: "{group}\卸载 Sense Mic"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Sense Mic"; Filename: "{app}\sense-mic-gui.exe"; WorkingDir: "{app}"; Tasks: desktopicon
#ifdef DriverStage
Name: "{commonstartup}\Sense Mic"; Filename: "{app}\sense-mic-gui.exe"; WorkingDir: "{app}"; Tasks: startup
#else
Name: "{userstartup}\Sense Mic"; Filename: "{app}\sense-mic-gui.exe"; WorkingDir: "{app}"; Tasks: startup
#endif

[Run]
#ifdef DriverStage
Filename: "{app}\sense-mic.exe"; Parameters: "driver install --package ""{app}\driver\windows\x64\SenseMicVAD.inf"""; StatusMsg: "正在安装 Sense Mic 虚拟麦克风驱动…"; Flags: runhidden waituntilterminated
#endif
Filename: "{app}\sense-mic-gui.exe"; Description: "启动 Sense Mic"; Flags: nowait postinstall skipifsilent

[UninstallRun]
#ifdef DriverStage
Filename: "{app}\sense-mic.exe"; Parameters: "driver uninstall"; Flags: runhidden waituntilterminated; RunOnceId: "RemoveSenseMicDriver"
#endif

[Code]
function InitializeSetup(): Boolean;
begin
  Result := True;
end;
