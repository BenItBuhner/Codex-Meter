; Inno Setup script for the Codex Meter Windows installer.
; Built by .github/workflows/windows-ci.yml:
;   ISCC /DAppVersion=2.9.0-alpha.14 /DArch=x64 /DSourceDir=..\out\CodexMeter-win-x64 /DOutputDir=..\out CodexMeter.iss
; Installs per user (no admin prompt) into %LOCALAPPDATA%\Programs\Codex Meter.

#ifndef AppVersion
  #define AppVersion "0.0.0-dev"
#endif
#ifndef Arch
  #define Arch "x64"
#endif
#ifndef SourceDir
  #define SourceDir "..\out\CodexMeter-win-" + Arch
#endif
#ifndef IconFile
  #define IconFile "..\src\CodexMeter.App\Assets\AppIcon.ico"
#endif
#ifndef OutputDir
  #define OutputDir "..\out"
#endif

[Setup]
; Keep AppId fixed forever: it is how upgrades and the uninstaller find an existing install.
AppId={{6C1E3E2A-5B7D-4E61-9C0B-2F7A1D9C4B30}
AppName=Codex Meter
AppVersion={#AppVersion}
AppVerName=Codex Meter {#AppVersion}
AppPublisher=Codex Meter
AppPublisherURL=https://github.com/BenItBuhner/Codex-Meter
AppSupportURL=https://github.com/BenItBuhner/Codex-Meter/issues
AppUpdatesURL=https://github.com/BenItBuhner/Codex-Meter/releases
DefaultDirName={autopf}\Codex Meter
DefaultGroupName=Codex Meter
DisableProgramGroupPage=yes
DisableDirPage=auto
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
#if Arch == "arm64"
ArchitecturesAllowed=arm64
ArchitecturesInstallIn64BitMode=arm64
#else
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
#endif
MinVersion=10.0.19041
OutputDir={#OutputDir}
OutputBaseFilename=CodexMeter-Setup-{#Arch}
SetupIconFile={#IconFile}
UninstallDisplayIcon={app}\CodexMeter.exe
UninstallDisplayName=Codex Meter
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[InstallDelete]
; Clear the previous version's files so nothing stale is left behind by an upgrade.
Type: filesandordirs; Name: "{app}\*"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Codex Meter"; Filename: "{app}\CodexMeter.exe"
Name: "{autodesktop}\Codex Meter"; Filename: "{app}\CodexMeter.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\CodexMeter.exe"; Description: "{cm:LaunchProgram,Codex Meter}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/f /im CodexMeter.exe"; Flags: runhidden; RunOnceId: "StopCodexMeter"

[Registry]
; The app's "Start with Windows" setting writes this value; remove it with the app.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "CodexMeter"; Flags: uninsdeletevalue dontcreatekey
