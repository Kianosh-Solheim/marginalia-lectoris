; Marginalia Companion for Windows: installs for this user (no administrator), with a Start menu shortcut,
; an optional desktop shortcut and an uninstaller. The build passes the version and the app folder.
#define AppVersion GetEnv("COMPANION_VERSION")

[Setup]
AppId={{6F2C1E2A-6C1B-4B3E-9C1F-8A1D5B2E7C11}
AppName=Marginalia Companion
AppVersion={#AppVersion}
AppVerName=Marginalia Companion {#AppVersion}
AppPublisher=Marginalia Lectoris
AppPublisherURL=https://github.com/Kianosh-Solheim/marginalia-lectoris
DefaultDirName={localappdata}\Programs\Marginalia Companion
DisableDirPage=yes
DefaultGroupName=Marginalia Companion
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=.
OutputBaseFilename=Marginalia-Companion-windows-setup
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\Marginalia Companion.exe
UninstallDisplayName=Marginalia Companion
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
; The app holds this while running: the installer closes it first instead of replacing files under it.
AppMutex=MarginaliaCompanionRunning
RestartApplications=no
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Tasks]
Name: "desktopicon"; Description: "Put a shortcut on the desktop"; Flags: unchecked

[InstallDelete]
; The app's files from an earlier version go first, so nothing old is left behind.
Type: filesandordirs; Name: "{app}\_internal"

[Files]
Source: "dist\Marginalia Companion\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Marginalia Companion"; Filename: "{app}\Marginalia Companion.exe"
Name: "{autodesktop}\Marginalia Companion"; Filename: "{app}\Marginalia Companion.exe"; Tasks: desktopicon

[Registry]
; "Start with Windows" is set by the app itself; the uninstaller takes it away.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueName: "MarginaliaCompanion"; ValueType: none; Flags: uninsdeletevalue dontcreatekey

[Run]
Filename: "{app}\Marginalia Companion.exe"; Description: "Open Marginalia Companion"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM ""Marginalia Companion.exe"""; Flags: runhidden; RunOnceId: "CloseCompanion"
