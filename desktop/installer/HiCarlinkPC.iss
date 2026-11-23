; Hi Carlink PC 安装包（Inno Setup）
; 用 javaw 启动：只有一个应用窗口，没有控制台；日志在窗口里按 F12 看，也会写到 %USERPROFILE%\.hi-carlink\last-run.log

[Setup]
AppId={{8E2C71B4-5B7A-4F63-9C1E-2D4A6B8F0C31}
AppName=Hi Carlink
AppVersion=0.2.0
AppVerName=Hi Carlink 0.2.0
AppPublisher=CHAIOR
AppPublisherURL=https://github.com/CHAIOR/Hi-Carlink
DefaultDirName={autopf}\HiCarlinkPC
DefaultGroupName=Hi Carlink
DisableProgramGroupPage=yes
; 每次安装都显示「选择安装位置」，不然升级时 Inno 会自动跳过这一页
DisableDirPage=no
UsePreviousAppDir=yes
PrivilegesRequired=lowest
; 允许在向导里选择「为所有用户安装」或「仅为我安装」，两种模式都能改安装位置
PrivilegesRequiredOverridesAllowed=dialog
; 应用在运行时，先请用户关掉再装
CloseApplications=yes
RestartApplications=no
AppMutex=HiCarlinkPC
OutputDir=out
OutputBaseFilename=HiCarlinkPC-Setup-0.2.0
SetupIconFile=HiCarlinkPC\app.ico
UninstallDisplayIcon={app}\app.ico
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0

[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务："; Flags: checkedonce

[Files]
Source: "HiCarlinkPC\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{autoprograms}\Hi Carlink"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "-Xmx1g -Dfile.encoding=UTF-8 -cp ""{app}\app\*"" io.github.gcbond.openhc.pc.PcMainKt --identity ""{app}\identity\openhc-identity.json"" --trust-dir ""{userappdata}\.hi-carlink\trust"""; WorkingDir: "{app}"; IconFilename: "{app}\app.ico"
Name: "{autodesktop}\Hi Carlink"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "-Xmx1g -Dfile.encoding=UTF-8 -cp ""{app}\app\*"" io.github.gcbond.openhc.pc.PcMainKt --identity ""{app}\identity\openhc-identity.json"" --trust-dir ""{userappdata}\.hi-carlink\trust"""; WorkingDir: "{app}"; IconFilename: "{app}\app.ico"; Tasks: desktopicon

[Run]
Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "-Xmx1g -Dfile.encoding=UTF-8 -cp ""{app}\app\*"" io.github.gcbond.openhc.pc.PcMainKt --identity ""{app}\identity\openhc-identity.json"" --trust-dir ""{userappdata}\.hi-carlink\trust"""; WorkingDir: "{app}"; Description: "立即运行 Hi Carlink"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{app}"