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
OutputDir=..\..\artifacts\installer
OutputBaseFilename=oreslang-windows-x86_64-setup
Compression=lzma
SolidCompression=yes
ChangesEnvironment=yes
WizardStyle=modern

[Files]
Source: "..\..\stage\oreslang.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\..\stage\oreslang-compiler.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\..\stage\provenance.json"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\Oreslang"; Filename: "{app}\oreslang.exe"; Parameters: "doctor"

[Code]
const
  PathOwnershipKey = 'Software\Oreslang\Installer';

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
  // Only remove a PATH value on uninstall if this installation added it.
  // Preexisting user configuration must never be claimed by the installer.
  if not RegWriteStringValue(HKEY_CURRENT_USER, PathOwnershipKey,
          'InstallerAddedPath', InstallPath) then
    RaiseException('Could not record Oreslang PATH ownership');
  if not RegWriteExpandStringValue(HKEY_CURRENT_USER, 'Environment', 'Path', NewPath) then
  begin
    RegDeleteValue(HKEY_CURRENT_USER, PathOwnershipKey, 'InstallerAddedPath');
    RaiseException('Could not update the per-user PATH');
  end;
end;

procedure RemoveUserPath;
var
  OldPath, Wrapped, InstallPath, OwnedPath, Needle, NewPath: String;
  Position: Integer;
begin
  // A path which was already present before setup is not our property.
  if not RegQueryStringValue(HKEY_CURRENT_USER, PathOwnershipKey,
          'InstallerAddedPath', OwnedPath) then Exit;
  InstallPath := ExpandConstant('{app}');
  if Uppercase(OwnedPath) <> Uppercase(InstallPath) then Exit;
  if not RegQueryStringValue(HKEY_CURRENT_USER, 'Environment', 'Path', OldPath) then
  begin
    RegDeleteValue(HKEY_CURRENT_USER, PathOwnershipKey, 'InstallerAddedPath');
    Exit;
  end;
  Wrapped := ';' + OldPath + ';';
  Needle := ';' + InstallPath + ';';
  Position := Pos(Uppercase(Needle), Uppercase(Wrapped));
  if Position = 0 then
  begin
    RegDeleteValue(HKEY_CURRENT_USER, PathOwnershipKey, 'InstallerAddedPath');
    Exit;
  end;
  Delete(Wrapped, Position, Length(Needle) - 1);
  NewPath := Copy(Wrapped, 2, Length(Wrapped) - 2);
  if not RegWriteExpandStringValue(HKEY_CURRENT_USER, 'Environment', 'Path', NewPath) then
    RaiseException('Could not remove Oreslang from the per-user PATH');
  RegDeleteValue(HKEY_CURRENT_USER, PathOwnershipKey, 'InstallerAddedPath');
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
