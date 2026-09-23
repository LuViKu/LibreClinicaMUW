<#
.SYNOPSIS
  Install (or remove) the Optomed Bridge tray app for the current user.

.DESCRIPTION
  Run once on the clinic PC, as the user who runs the Optomed Client — the
  bridge reads that user's Optomed Client folder and protects its token to
  that user with DPAPI, so it must run as them.

  What it does:
    1. creates %ProgramData%\LibreClinica and seeds optomed-bridge.json if
       there is none (existing settings are never overwritten);
    2. puts a shortcut in the user's Startup folder that launches
       OptomedBridge.ps1 hidden at login;
    3. starts the bridge now.

  The token is NOT taken here: paste it into the tray icon's Settings dialog
  so it is DPAPI-protected on save and never sits in a shell history.

.PARAMETER BaseUrl
  The platform's base URL including the context path, e.g.
  https://ecrf.augen.meduniwien.ac.at/LibreClinica

.PARAMETER ClientRoot
  The Optomed Client's device folder. Defaults to the Client's own default,
  %USERPROFILE%\Documents\OptomedClient\Optomed Lumo.

.PARAMETER Uninstall
  Remove the Startup shortcut and stop the bridge. Settings and log are kept.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\Install-OptomedBridge.ps1 `
    -BaseUrl https://ecrf.augen.meduniwien.ac.at/LibreClinica
#>
[CmdletBinding()]
param(
    [string]$BaseUrl,
    [string]$ClientRoot,
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'

$bridge    = Join-Path $PSScriptRoot 'OptomedBridge.ps1'
$stateDir  = Join-Path $env:ProgramData 'LibreClinica'
$cfgPath   = Join-Path $stateDir 'optomed-bridge.json'
$startup   = [Environment]::GetFolderPath('Startup')
$shortcut  = Join-Path $startup 'Optomed Bridge.lnk'
$psExe     = Join-Path $PSHOME 'powershell.exe'

function Stop-Bridge {
    # The bridge holds a named mutex; there is no service to stop, just the
    # powershell.exe hosting it. Match on the script path so nothing else dies.
    Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" |
        Where-Object { $_.CommandLine -like "*OptomedBridge.ps1*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

if ($Uninstall) {
    Stop-Bridge
    if (Test-Path $shortcut) { Remove-Item $shortcut -Force; Write-Host "removed $shortcut" }
    Write-Host "Optomed Bridge removed from startup. Settings and log kept under $stateDir."
    return
}

if (-not (Test-Path $bridge)) { throw "OptomedBridge.ps1 not found next to this installer: $bridge" }

# 1. state dir + seeded settings (never overwrite what is there)
if (-not (Test-Path $stateDir)) { New-Item -ItemType Directory -Force $stateDir | Out-Null }
if (-not (Test-Path $cfgPath)) {
    $cfg = [ordered]@{
        BaseUrl             = if ($BaseUrl) { $BaseUrl.TrimEnd('/') } else { 'https://ecrf.augen.meduniwien.ac.at/LibreClinica' }
        TokenProtected      = ''
        ClientRoot          = if ($ClientRoot) { $ClientRoot } else { Join-Path $env:USERPROFILE 'Documents\OptomedClient\Optomed Lumo' }
        WorklistIntervalSec = 30
        UploadIntervalMin   = 5
        Device              = 'optomed-lumo'
        Enabled             = $false
        HideClientWindow    = $true
        ClientExe           = (Join-Path $env:LOCALAPPDATA 'Optomed\OptomedClient\OptomedClient.exe')
    }
    $cfg | ConvertTo-Json | Set-Content -Path $cfgPath -Encoding UTF8
    Write-Host "seeded $cfgPath (disabled until a token is set in Settings)"
} else {
    Write-Host "kept existing $cfgPath"
    if ($BaseUrl -or $ClientRoot) { Write-Warning 'settings file exists; -BaseUrl/-ClientRoot ignored. Change them in the tray Settings dialog.' }
}

# 2. startup shortcut — hidden window, bypass so a machine policy of
#    RemoteSigned does not stop an unsigned script from a network copy.
$wsh = New-Object -ComObject WScript.Shell
$lnk = $wsh.CreateShortcut($shortcut)
$lnk.TargetPath       = $psExe
$lnk.Arguments        = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$bridge`""
$lnk.WorkingDirectory = $PSScriptRoot
$lnk.Description      = 'LibreClinica Optomed Bridge'
$lnk.WindowStyle      = 7   # minimised
$lnk.Save()
Write-Host "startup shortcut: $shortcut"

# 3. start it now (single-instance: a running bridge simply exits the new one),
#    and say whether it stayed up — a hidden window that dies in its first
#    second is otherwise indistinguishable from one that is running.
Stop-Bridge
$p = Start-Process -FilePath $psExe -ArgumentList $lnk.Arguments -WindowStyle Hidden -WorkingDirectory $PSScriptRoot -PassThru
if ($p.WaitForExit(4000)) {
    Write-Warning "Optomed Bridge exited straight away (code $($p.ExitCode)). See $(Join-Path $stateDir 'optomed-bridge.log'), or run it in a console to see the error:"
    Write-Warning "  powershell -NoProfile -ExecutionPolicy Bypass -File `"$bridge`""
    exit 1
}
Write-Host "Optomed Bridge running (pid $($p.Id)) - look for the grey circle in the tray."
Write-Host 'Right-click it -> Settings... -> paste the worklist token -> Enabled. The icon turns teal when enabled.'
