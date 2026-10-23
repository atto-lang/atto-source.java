#define AppVersion GetEnv("ORESLANG_INSTALLER_VERSION")
#if AppVersion == ""
  #error "ORESLANG_INSTALLER_VERSION must be supplied by the verified release build"
#endif

[Setup]
AppId=OreslangCompiler
AppName=Oreslang Compiler
AppVersion={#AppVersion}
AppPublisher=Oreslang
DefaultDirName={localappdata}\Programs\Oreslang
DefaultGroupName=Oreslang
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
DisableProgramGroupPage=yes
UninstallDisplayIcon={app}\oreslang.exe
OutputDir=artifacts\installer
OutputBaseFilename=oreslang-windows-x86_64-setup
Compression=lzma
SolidCompression=yes
ChangesEnvironment=yes
WizardStyle=modern

[Files]
Source: "stage\oreslang.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "stage\oreslang-compiler.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "stage\provenance.json"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\Oreslang"; Filename: "{app}\oreslang.exe"; Parameters: "doctor"

[Code]
function PathContains(const PathValue, InstallPath: String): Boolean;
begin
  Result := Pos(';' + Uppercase(InstallPath) + ';',
    ';' + Uppercase(PathValue) + ';') > 0;
end;

procedure AddUserPath;
var
  OldPath, NewPath, InstallPath: String;
begin
  InstallPath := ExpandConstant('{app}');
  if not RegQueryStringValue(HKEY_CURRENT_USER, 'Environment', 'Path', OldPath) then
    OldPath := '';
  if PathContains(OldPath, InstallPath) then Exit;
  if OldPath = '' then
    NewPath := InstallPath
  else
    NewPath := OldPath + ';' + InstallPath;
  if not RegWriteExpandStringValue(HKEY_CURRENT_USER, 'Environment', 'Path', NewPath) then
    RaiseException('Could not update the per-user PATH');
end;

procedure RemoveUserPath;
var
  OldPath, Wrapped, InstallPath, Needle, NewPath: String;
  Position: Integer;
begin
  if not RegQueryStringValue(HKEY_CURRENT_USER, 'Environment', 'Path', OldPath) then Exit;
  InstallPath := ExpandConstant('{app}');
  Wrapped := ';' + OldPath + ';';
  Needle := ';' + InstallPath + ';';
  Position := Pos(Uppercase(Needle), Uppercase(Wrapped));
  if Position = 0 then Exit;
  Delete(Wrapped, Position, Length(Needle) - 1);
  NewPath := Copy(Wrapped, 2, Length(Wrapped) - 2);
  if not RegWriteExpandStringValue(HKEY_CURRENT_USER, 'Environment', 'Path', NewPath) then
    RaiseException('Could not remove Oreslang from the per-user PATH');
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    AddUserPath;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
    RemoveUserPath;
end;
