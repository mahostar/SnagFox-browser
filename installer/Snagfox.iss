[Setup]
AppId={{0A5AF11E-1972-4381-98E3-73A0B257F238}
AppName=Snagfox
AppVersion=0.1.3
AppPublisher=Snagfox
DefaultDirName={localappdata}\Programs\Snagfox
DefaultGroupName=Snagfox
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\delivery
OutputBaseFilename=Snagfox-Setup
SetupIconFile=..\ui\icon.ico
UninstallDisplayIcon={app}\Snagfox.exe
Compression=lzma2/fast
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
[Tasks]
Name: desktopicon; Description: "Create a desktop shortcut"; Flags: unchecked
[Files]
Source: "..\delivery\app\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
Name: "{group}\Snagfox"; Filename: "{app}\Snagfox.exe"
Name: "{autodesktop}\Snagfox"; Filename: "{app}\Snagfox.exe"; Tasks: desktopicon
[Run]
Filename: "{app}\Snagfox.exe"; Description: "Launch Snagfox"; Flags: nowait postinstall skipifsilent
