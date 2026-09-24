<#
.SYNOPSIS
  Install (or remove) the Export Watcher tray app for the current user.
.DESCRIPTION
  Run once on the acquisition PC (the Clarus PC, the Spectralis PC — one
  watcher per PC, each pointed at that PC's own export folder), as the user
  who exports. What it does:
    1. creates %ProgramData%\LibreClinica and seeds export-watcher.json if
       there is none (existing settings are never overwritten);
    2. puts a shortcut in the user's Startup folder that launches
       ExportWatcher.ps1 hidden at login;
    3. starts the watcher now.
  There is no secret to enter: the public upload front door takes none.
  Switch it on from the tray icon's Settings once the folder is right.
.PARAMETER BaseUrl
  The platform's base URL including the context path, e.g.
  https://ecrf.augen.meduniwien.ac.at/LibreClinica
.PARAMETER WatchFolder
  The folder the device exports into. Defaults to
  %USERPROFILE%\Documents\LibreClinica-Export.
.PARAMETER Uninstall
  Remove the Startup shortcut and stop the watcher. Settings and log are kept.
.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\Install-ExportWatcher.ps1 `
    -BaseUrl https://ecrf.augen.meduniwien.ac.at/LibreClinica -WatchFolder D:\Export
#>
[CmdletBinding()]
param(
    [string]$BaseUrl,
    [string]$WatchFolder,
    [switch]$Uninstall
)
$ErrorActionPreference = 'Stop'
$watcher   = Join-Path $PSScriptRoot 'ExportWatcher.ps1'
$stateDir  = Join-Path $env:ProgramData 'LibreClinica'
$cfgPath   = Join-Path $stateDir 'export-watcher.json'
$startup   = [Environment]::GetFolderPath('Startup')
$shortcut  = Join-Path $startup 'Export Watcher.lnk'
$psExe     = Join-Path $PSHOME 'powershell.exe'

function Stop-Watcher {
    # The watcher holds a named mutex; there is no service to stop, just the
    # powershell.exe hosting it. Match on the script path so nothing else dies.
    Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" |
        Where-Object { $_.CommandLine -like "*ExportWatcher.ps1*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

if ($Uninstall) {
    Stop-Watcher
    if (Test-Path $shortcut) { Remove-Item $shortcut -Force; Write-Host "removed $shortcut" }
    Write-Host "Export Watcher removed from startup. Settings and log kept under $stateDir."
    return
}

if (-not (Test-Path $watcher)) { throw "ExportWatcher.ps1 not found next to this installer: $watcher" }

# 1. state dir + seeded settings (never overwrite what is there)
if (-not (Test-Path $stateDir)) { New-Item -ItemType Directory -Force $stateDir | Out-Null }
if (-not (Test-Path $cfgPath)) {
    $cfg = [ordered]@{
        BaseUrl          = if ($BaseUrl) { $BaseUrl.TrimEnd('/') } else { 'https://ecrf.augen.meduniwien.ac.at/LibreClinica' }
        WatchFolder      = if ($WatchFolder) { $WatchFolder } else { Join-Path $env:USERPROFILE 'Documents\LibreClinica-Export' }
        SweepIntervalSec = 20
        DeviceDicom      = 'clarus'
        DeviceE2e        = 'spectralis'
        Enabled          = $false
    }
    $cfg | ConvertTo-Json | Set-Content -Path $cfgPath -Encoding UTF8
    Write-Host "seeded $cfgPath (disabled until switched on in Settings)"
} else {
    Write-Host "kept existing $cfgPath"
    if ($BaseUrl -or $WatchFolder) { Write-Warning 'settings file exists; -BaseUrl/-WatchFolder ignored. Change them in the tray Settings dialog.' }
}
$folder = (Get-Content $cfgPath -Raw | ConvertFrom-Json).WatchFolder
if (-not (Test-Path $folder)) { New-Item -ItemType Directory -Force $folder | Out-Null; Write-Host "created export folder $folder" }

# 2. startup shortcut — hidden window, bypass so a machine policy of
#    RemoteSigned does not stop an unsigned script from a network copy.
$wsh = New-Object -ComObject WScript.Shell
$lnk = $wsh.CreateShortcut($shortcut)
$lnk.TargetPath       = $psExe
$lnk.Arguments        = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$watcher`""
$lnk.WorkingDirectory = $PSScriptRoot
$lnk.Description      = 'LibreClinica Export Watcher'
$lnk.WindowStyle      = 7   # minimised
$lnk.Save()
Write-Host "startup shortcut: $shortcut"

# 3. start it now (single-instance: a running watcher simply exits the new one),
#    and say whether it stayed up — a hidden window that dies in its first
#    second is otherwise indistinguishable from one that is running.
Stop-Watcher
$p = Start-Process -FilePath $psExe -ArgumentList $lnk.Arguments -WindowStyle Hidden -WorkingDirectory $PSScriptRoot -PassThru
if ($p.WaitForExit(4000)) {
    Write-Warning "Export Watcher exited straight away (code $($p.ExitCode)). See $(Join-Path $stateDir 'export-watcher.log'), or run it in a console to see the error:"
    Write-Warning "  powershell -NoProfile -ExecutionPolicy Bypass -File `"$watcher`""
    exit 1
}
Write-Host "Export Watcher running (pid $($p.Id)) - look for the grey square in the tray."
Write-Host "Right-click it -> Settings... -> check the folder -> Enabled. The icon turns teal when enabled."
Write-Host "Point the device's export at $folder."
