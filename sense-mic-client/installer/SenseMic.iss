#ifndef AppVersion
  #define AppVersion "0.4.15"
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
#if defined(DriverStage) || defined(VbCableStage)
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
RestartIfNeededByRun=yes
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
#if !defined(DriverStage) && !defined(VbCableStage)
Source: "{#StageDir}\CLIENT-ONLY-NOTICE.txt"; DestDir: "{app}"; Flags: ignoreversion
#endif
Source: "{#StageDir}\VB-CABLE-NOTICE.txt"; DestDir: "{app}\licenses"; Flags: ignoreversion
Source: "{#StageDir}\BUILD-INFO.txt"; DestDir: "{app}"; Flags: ignoreversion
#ifdef DriverStage
Source: "{#DriverStage}\*"; DestDir: "{app}\driver\windows\x64"; Flags: ignoreversion recursesubdirs createallsubdirs
#endif
#ifdef VbCableStage
Source: "{#VbCableStage}\*"; DestDir: "{app}\driver\vb-cable"; Flags: ignoreversion recursesubdirs createallsubdirs
#endif

[Icons]
Name: "{group}\Sense Mic"; Filename: "{app}\sense-mic-gui.exe"; WorkingDir: "{app}"
Name: "{group}\Sense Mic 命令行"; Filename: "{app}\sense-mic.exe"; WorkingDir: "{app}"
Name: "{group}\卸载 Sense Mic"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Sense Mic"; Filename: "{app}\sense-mic-gui.exe"; WorkingDir: "{app}"; Tasks: desktopicon
#if defined(DriverStage) || defined(VbCableStage)
Name: "{commonstartup}\Sense Mic"; Filename: "{app}\sense-mic-gui.exe"; WorkingDir: "{app}"; Tasks: startup
#else
Name: "{userstartup}\Sense Mic"; Filename: "{app}\sense-mic-gui.exe"; WorkingDir: "{app}"; Tasks: startup
#endif

[Run]
#if defined(DriverStage) || defined(VbCableStage)
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Sense Mic Audio (Private LAN)"""; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Sense Mic Audio (Private LAN)"" dir=in action=allow program=""{app}\sense-mic.exe"" enable=yes profile=private protocol=UDP edge=no"; StatusMsg: "正在允许 Sense Mic 接收手机的局域网音频…"; Flags: runhidden waituntilterminated
#endif
#ifdef VbCableStage
Filename: "{app}\sense-mic.exe"; Parameters: "driver install"; WorkingDir: "{app}"; StatusMsg: "正在安装 Microsoft WHQL 签名的 VB-CABLE 虚拟麦克风…"; Flags: runhidden waituntilterminated
#endif
#ifdef DriverStage
Filename: "{app}\sense-mic.exe"; Parameters: "driver install --package ""{app}\driver\windows\x64\SenseMicVAD.inf"""; StatusMsg: "正在安装 Sense Mic 虚拟麦克风驱动…"; Flags: runhidden waituntilterminated
#endif
Filename: "{app}\sense-mic-gui.exe"; Description: "启动 Sense Mic"; Flags: nowait postinstall skipifsilent

[UninstallRun]
#if defined(DriverStage) || defined(VbCableStage)
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Sense Mic Audio (Private LAN)"""; Flags: runhidden waituntilterminated; RunOnceId: "RemoveSenseMicFirewallRule"
#endif
#ifdef DriverStage
Filename: "{app}\sense-mic.exe"; Parameters: "driver uninstall"; Flags: runhidden waituntilterminated; RunOnceId: "RemoveSenseMicDriver"
#endif

[Code]
function InitializeSetup(): Boolean;
begin
  Result := True;
end;
