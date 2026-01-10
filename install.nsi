Unicode true

; The name of the installer
Name "茶坊村本地打印助手"

; The file to write
OutFile "茶坊村本地打印助手.exe"

; The default installation directory
InstallDir "$LOCALAPPDATA\WebApp Hardware Bridge"

; Request application privileges for Windows Vista
RequestExecutionLevel user

;--------------------------------

; Pages

;Page directory
Page components
Page instfiles

;--------------------------------

; The stuff to install
Section "!Main Application" ;No components page, name is not important
  SectionIn RO

  ; Set output path to the installation directory.
  SetOutPath $INSTDIR
  
  ; Remove old version
  RMDir /r "$INSTDIR\jre"
  Delete "$INSTDIR\*.jar"
  Delete "$INSTDIR\setting.default.json"
  Delete "$DESKTOP\WebApp Hardware Bridge (GUI).lnk"
  Delete "$DESKTOP\WebApp Hardware Bridge (Configurator).lnk"
  Delete "$SMPROGRAMS\WebApp Hardware Bridge (GUI).lnk"
  Delete "$SMPROGRAMS\WebApp Hardware Bridge (Configurator).lnk"
  
  ; Put file there
  File /r out\artifacts\webapp_hardware_bridge_jar\*
  File /r jre
  
  File "install.nsi"
  File "icon.ico"
  
  ; Delete shortcuts  
  Delete "$DESKTOP\WebApp Hardware Bridge.lnk"
  Delete "$SMPROGRAMS\WebApp Hardware Bridge.lnk"
  
  ; Create shortcuts
  CreateShortcut "$DESKTOP\茶坊村本地打印助手.lnk" "$INSTDIR\jre\bin\javaw.exe" '-cp ".;webapp-hardware-bridge.jar;*" tigerworkshop.webapphardwarebridge.GUI' "$INSTDIR\icon.ico" 0
  CreateShortcut "$SMPROGRAMS\茶坊村本地打印助手.lnk" "$INSTDIR\jre\bin\javaw.exe" '-cp ".;webapp-hardware-bridge.jar;*" tigerworkshop.webapphardwarebridge.GUI' "$INSTDIR\icon.ico" 0

  ; Write the installation path into the registry
  WriteRegStr HKCU "SOFTWARE\WebApp Hardware Bridge" "Install_Dir" "$INSTDIR"
  
  ; Write the uninstall keys for Windows
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\WebApp Hardware Bridge" "DisplayName" "茶坊村本地打印助手"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\WebApp Hardware Bridge" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\WebApp Hardware Bridge" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\WebApp Hardware Bridge" "NoRepair" 1
  WriteUninstaller "uninstall.exe"

  ; Auto close when finished
  SetAutoClose true
SectionEnd ; end the section

Section "Auto-start" autostart
  CreateShortcut "$SMSTARTUP\茶坊村本地打印助手.lnk" "$INSTDIR\jre\bin\javaw.exe" '-cp ".;webapp-hardware-bridge.jar;*" tigerworkshop.webapphardwarebridge.GUI'
SectionEnd

Section "Uninstall"
  ; Remove registry keys
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\WebApp Hardware Bridge"
  DeleteRegKey HKCU "SOFTWARE\WebApp Hardware Bridge"
  
  ; Delete shortcuts
  Delete "$DESKTOP\茶坊村本地打印助手.lnk"
  Delete "$SMPROGRAMS\茶坊村本地打印助手.lnk"
  
  ; Remove files and uninstaller
  RMDir /r $INSTDIR
SectionEnd

Function .onInstSuccess
  ExecShell "" "$DESKTOP\茶坊村本地打印助手.lnk"
FunctionEnd