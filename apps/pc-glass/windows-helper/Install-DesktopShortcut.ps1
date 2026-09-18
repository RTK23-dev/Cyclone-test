#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
$vbs = Join-Path $PSScriptRoot 'Launch Cyclone PC Glass Helper.vbs'
$desktop = [Environment]::GetFolderPath('Desktop')
$lnkPath = Join-Path $desktop 'Cyclone PC Glass.lnk'
$w = New-Object -ComObject WScript.Shell
$lnk = $w.CreateShortcut($lnkPath)
$lnk.TargetPath = $vbs
$lnk.WorkingDirectory = $PSScriptRoot
$lnk.Description = 'Start/Stop Cyclone PC Glass (no command prompts)'
$lnk.Save()
Write-Host "Desktop shortcut created: $lnkPath"
