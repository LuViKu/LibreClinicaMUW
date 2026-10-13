<#
.SYNOPSIS
  Export Watcher - tray app that uploads device exports from a watched folder
  to the LibreClinica upload front door: Zeiss Clarus DICOM (.dcm) and
  Heidelberg Spectralis (.e2e).

.DESCRIPTION
  Neither device speaks to the platform. The photographer exports to a folder
  on the acquisition PC - Clarus writes three .dcm per capture, HEYEX one .e2e per
  export - and until now re-uploaded every file through the browser page.
  This script watches that folder instead and carries each new file through
  the same public upload API the page uses (DR-029), so nothing changes
  server-side and the file arrives exactly as if uploaded by hand:

    sweep      every N seconds (default 20): list the watched folder for
               *.dcm and *.e2e. A file counts as finished when its size has
               not changed since the previous sweep AND it can be opened
               without sharing - an export in progress fails one of the two.
               Sub-folders named _uploaded / _failed / _skipped are skipped.

    identify   .dcm: PatientID, StudyDate and (Image)Laterality out of the
               header - the same reader the Optomed bridge uses. A Clarus
               export is three objects per capture: the photograph, a Raw
               Data object (the vendor's sensor data, 8 MB) and a small OT
               Raw Data object stamped with the export time. Only the
               photograph is an image; the other two are set aside in
               _skipped\ (never deleted) unless UploadNonImage is on.
               .e2e (DR-041): the header is NOT read. The subject is the
               folder: <WatchFolder>\...\<subject>\x.e2e - the folder that
               directly contains the file. An .e2e directly in the watch
               folder has no subject: it is not uploaded, it is counted
               ("without subject folder" in the sweep summary, the coded
               problem no-subject-folder in the heartbeat). One file holds a
               whole multimodal session (both eyes, OCT, IR, autofluorescence,
               angiography ...); the platform splits it into one item per
               series, and parks them all when the header's patient id
               differs from the folder name.

    resolve    .dcm only: ONE POST /resolve for every scan found in the sweep,
               not one per file: the public front door budgets lookups at 30
               per hour per client, and a Clarus session is easily 40 files.
               Exactly one subject with exactly one visit on that day ->
               bound on arrival; anything else -> the reconciliation inbox,
               with the label and date as hints.

    commit     .dcm: POST /commit per scan. 201 and 409 (already there, by
               content hash) both move the file into _uploaded\. Any other
               answer leaves it for the next sweep; after 5 attempts it goes
               to _failed\ so a bad file cannot burn the budget forever.
               .e2e: SHA-256 of the file (streamed), GET /preflight?sha256=..
               &split=true - already there -> _uploaded\ without a byte sent -
               else ONE streamed POST /commit (split=true, patientId=<folder>,
               device) for the whole file, up to 2 GiB, never read into
               memory; the deadline is sized from the file. 201 and 409 move
               it to _uploaded\, the rest is retried and ends in _failed\ as
               above. A file over 2 GiB goes to _failed\ unsent.

    token      (DR-040) When an uploader token is set in Settings, EVERY
               request - resolve, commit, heartbeat - carries it in the header
               X-MUW-Uploader-Token, and the platform takes this PC's location
               from it. No token set = no header = the anonymous behaviour of
               before, which the platform serves only while its front door is
               open. The platform answers 401 "uploader token required" or
               "uploader token rejected". A 401 is NOT a failed lookup and is
               never treated like a 429: the sweep stops, every file stays
               where it is (nothing goes to _uploaded\, _failed\ or
               _skipped\, nothing is filed without a visit, no attempt is
               counted), the tray says "Token fehlt" or "Token abgelehnt -
               bitte in den Einstellungen pruefen", the coded problem
               token-required / token-rejected goes into the heartbeat, and the
               next sweep (the normal interval, no backoff) tries again. Files
               the platform accepted earlier in that sweep are still moved to
               _uploaded\.

  The convention this relies on is the one every device ingress here uses:
  the patient id the photographer types into the device IS the study subject
  label. Clarus: the DICOM PatientID. Spectralis: the name of the patient's
  export folder (the header's patient id is empty or different often enough
  that it cannot be relied on - the platform only compares it).

  Runs at login as a tray icon (see Install-ExportWatcher.ps1). Right-click:
  enable/disable, sweep now, settings, open log, exit. One instance per PC;
  on the Clarus PC it runs beside the Optomed bridge, on the Spectralis PC
  alone - one watcher per export folder, never one watcher for both PCs.

  Settings: %ProgramData%\LibreClinica\export-watcher.json. The only secret in
  it is the optional uploader token (UploaderTokenProtected), DPAPI-protected
  to the user who saved it, as the Optomed bridge protects its token. It is
  typed into the Settings dialog ("Uploader-Token"), never passed on a command
  line, and shown there only as "gesetzt" / "nicht gesetzt". Log alongside,
  rotated at 1 MB. Only counts and pseudonymous labels are ever logged - never
  names, dates of birth, filenames (an export is often named after the
  patient) or the token.

  What stays on the PC: the exports themselves, moved into _uploaded\ and
  kept there until the retention setting removes them. That is off by
  default: nothing is deleted unless someone switches it on in Settings and
  says after how many days. A Clarus export carries the patient's name in
  its header until the platform pseudonymises its copy on ingest, which is
  the reason to switch it on; the device keeps the original capture and the
  platform the pseudonymised copy. _skipped\ and _failed\ are never touched.

.PARAMETER Once
  Headless: run one sweep against the configured folder and exit with a
  summary. For scheduled-task deployments, for the installer's smoke run,
  and for testing the whole chain without a desktop session.

.PARAMETER SelfTest
  Headless: read -File and print what this script would send for it. A .dcm:
  label, date, laterality. An .e2e: the subject taken from its folder and its
  SHA-256 (the header is not read). Nothing is uploaded. Lets the rules be
  checked on a real export before the watcher is switched on.

.PARAMETER Heartbeat
  Headless: send one heartbeat to the platform and print the answer - the
  quickest check that this PC reaches the platform and shows up on the
  System Status page. Nothing is uploaded. It prints whether an uploader token
  is set - never the token - and, on a 401, which of the two refusals it was.

  The tray app sends one every HeartbeatIntervalSec (default 120), switched
  on or not (DR-033): whether it runs, whether uploading is on, how many
  export files wait and for how long, how many went to _failed\, how full
  the export drive is, and coded problems from the last sweep. Counts, ages
  and codes only - no label, no filename, nothing about a patient. It says
  "stopped" when closed from its menu or when Windows ends the session, so
  the page can tell a PC switched off in the evening from a crash.

.NOTES
  Windows PowerShell 5.1 - no 6+ features (no ternary, no ??, no -Form), and
    ASCII only: 5.1 reads a BOM-less file as cp1252, so an em-dash inside a
    string arrives as a smart quote and terminates it (2026-09-24: the script
    did not parse on Windows at all; it had only ever run on PowerShell 7).
  The headless modes (-Once, -SelfTest) also run on PowerShell 7 on Linux,
  which is how the chain is tested from a Mac against the dev stack. DPAPI is
  Windows only: there a saved uploader token cannot be read and the run is
  anonymous.
#>
[CmdletBinding()]
param(
    [switch]$Once,
    [switch]$SelfTest,
    [switch]$Heartbeat,
    [string]$File,
    [string]$ConfigPath = (Join-Path $(if ($env:ProgramData) { $env:ProgramData } else { $HOME }) 'LibreClinica\export-watcher.json')
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }

$script:AppName     = 'Export Watcher'
# Shown on the System Status page beside this PC's name. Bump with every
# change to this script, so a PC still running an old copy stands out.
$script:Version     = '2026-10-13.1'
$script:Kind        = 'export-watcher'
$script:StateDir    = Split-Path -Parent $ConfigPath
$script:LogPath     = Join-Path $script:StateDir 'export-watcher.log'
$script:UploadedDir = '_uploaded'
$script:FailedDir   = '_failed'
$script:SkippedDir  = '_skipped'
# SOP classes that are not images: Raw Data (the Clarus writes one per
# capture plus one at export time), structured reports, presentation
# states, encapsulated documents. Set aside unless UploadNonImage is on.
$script:NonImageSopPrefixes = @('1.2.840.10008.5.1.4.1.1.66', '1.2.840.10008.5.1.4.1.1.88.',
                                '1.2.840.10008.5.1.4.1.1.11.', '1.2.840.10008.5.1.4.1.1.104.')
$script:MaxAttempts = 5
$script:LastBalloon = [datetime]::MinValue
# retention of uploaded exports: runs at most once an hour (see Invoke-Retention)
$script:LastRetentionUtc = [datetime]::MinValue
$script:RetentionEveryMinutes = 60
# path -> size seen at the previous sweep; a file is settled when it matches.
$script:SizeSeen    = @{}
# path -> failed attempts this session.
$script:Attempts    = @{}

# ----------------------------------------------------------------------------
# logging - counts and labels only
# ----------------------------------------------------------------------------
function Write-Log {
    param([string]$Message, [ValidateSet('INFO','WARN','ERROR')][string]$Level = 'INFO')
    $line = '{0} [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Message
    try {
        if (-not (Test-Path $script:StateDir)) { New-Item -ItemType Directory -Force $script:StateDir | Out-Null }
        if ((Test-Path $script:LogPath) -and (Get-Item $script:LogPath).Length -gt 1MB) {
            Move-Item -Force $script:LogPath ($script:LogPath + '.1')
        }
        Add-Content -Path $script:LogPath -Value $line -Encoding UTF8
    } catch { }
    if ($Once -or $SelfTest) { Write-Host $line }
}

# ----------------------------------------------------------------------------
# settings - a JSON file; the uploader token DPAPI-protected to the saving user
# ----------------------------------------------------------------------------
function Get-DefaultConfig {
    [pscustomobject]@{
        BaseUrl        = 'https://ecrf.augen.meduniwien.ac.at/LibreClinica'
        # DR-040 - this PC's uploader token, DPAPI-protected (see Protect-Token).
        # Empty = none: the requests go out without it, as they always did.
        UploaderTokenProtected = ''
        WatchFolder    = (Join-Path $(if ($env:USERPROFILE) { $env:USERPROFILE } else { $HOME }) 'Documents\LibreClinica-Export')
        SweepIntervalSec = 20
        DeviceDicom    = 'clarus'      # the device key on a .dcm upload (imaging catalogue: FUNDUS_CLARUS)
        DeviceE2e      = 'spectralis'  # on an .e2e upload (OCT_SPECTRALIS and the Spectralis image rows)
        UploadNonImage = $false       # also upload Raw Data / report objects (see 'identify' above)
        Enabled        = $false
        # Retention of uploaded exports (2026-09-28). Off by default: nothing
        # is deleted unless a person switches it on, here or in Settings, and
        # says after how many days. Only _uploaded\ is ever touched.
        DeleteUploaded = $false
        DeleteUploadedAfterDays = 30
        # DR-033 - the System Status page. InstanceId is generated on first
        # start and identifies this installation's row: keep it with the
        # settings file, never copy it to another PC. DisplayName blank =
        # the computer name.
        InstanceId     = ''
        DisplayName    = ''
        HeartbeatIntervalSec = 120
    }
}

function Read-Config {
    $cfg = Get-DefaultConfig
    if (Test-Path $ConfigPath) {
        try {
            $saved = Get-Content $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
            foreach ($p in $cfg.PSObject.Properties.Name) {
                if ($saved.PSObject.Properties[$p]) { $cfg.$p = $saved.$p }
            }
        } catch { Write-Log "config unreadable, using defaults: $($_.Exception.Message)" 'WARN' }
    }
    $cfg
}

function Save-Config([pscustomobject]$cfg) {
    if (-not (Test-Path $script:StateDir)) { New-Item -ItemType Directory -Force $script:StateDir | Out-Null }
    $cfg | ConvertTo-Json | Set-Content -Path $ConfigPath -Encoding UTF8
}

# DPAPI, current-user scope: the saved value is readable by the user who saved
# it, on this PC. (As in OptomedBridge.ps1 - keep the two word for word.)
function Protect-Token([string]$plain) {
    if ([string]::IsNullOrEmpty($plain)) { return '' }
    (ConvertTo-SecureString $plain -AsPlainText -Force) | ConvertFrom-SecureString
}

function Unprotect-Token([string]$protected) {
    if ([string]::IsNullOrEmpty($protected)) { return '' }
    try {
        $ss = ConvertTo-SecureString $protected
        $b  = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($ss)
        try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
    } catch { Write-Log 'token could not be unprotected (saved by another user?)' 'ERROR'; '' }
}

# ----------------------------------------------------------------------------
# HTTP - one client for the small requests; an .e2e upload gets its own (below)
# ----------------------------------------------------------------------------
Add-Type -AssemblyName System.Net.Http
$script:Http = New-Object System.Net.Http.HttpClient
$script:Http.Timeout = [TimeSpan]::FromMinutes(15)
# An .e2e is up to 2 GiB, so its upload gets a client of its own whose deadline
# is sized from the file (HttpClient.Timeout cannot change after the first
# request): this long for the handshake and the server's work, plus the file at
# no less than 1 MiB/s (a 2 GiB file: about 44 minutes). The tests replace
# New-UploadClient.
$script:UploadBaseTimeoutSec = 600
$script:UploadMinBytesPerSec = 1048576
$script:MaxE2eBytes = 2GB
function Get-UploadTimeoutSec([int64]$bytes) {
    [int]($script:UploadBaseTimeoutSec + [Math]::Ceiling([double]$bytes / $script:UploadMinBytesPerSec))
}
function New-UploadClient([int]$timeoutSec) {
    $c = New-Object System.Net.Http.HttpClient
    $c.Timeout = [TimeSpan]::FromSeconds($timeoutSec)
    $c
}

function Get-ApiUrl([pscustomobject]$cfg, [string]$path) {
    $cfg.BaseUrl.TrimEnd('/') + '/pages' + $path
}

# ----------------------------------------------------------------------------
# the uploader token (DR-040) - one per uploader, issued for one location
# The shared part is kept word for word as in OptomedBridge.ps1.
# ----------------------------------------------------------------------------
# It travels in the header X-MUW-Uploader-Token on every request to the
# platform, and nowhere else: never in the log, in a message, in the tray or in
# the heartbeat. None set = no header = the anonymous request of before.
$script:TokenHeader   = 'X-MUW-Uploader-Token'
$script:UpTokenSource = $null    # the saved (protected) value the cached token was read from
$script:UpTokenPlain  = ''

# 'mwu_' and 43 base64url characters (32 random bytes), as the platform issues it.
function Test-UploaderTokenFormat([string]$token) {
    $token -cmatch '^mwu_[A-Za-z0-9_-]{43}\z'
}

# For the log and the console: whether one is saved, never what it is.
function Get-UploaderTokenState([pscustomobject]$cfg) {
    if ([string]$cfg.UploaderTokenProtected) { 'set' } else { 'not set' }
}

# Unprotected once per saved value, not once per request: a token that cannot
# be read (saved by another user) is reported once, not at every request.
function Get-UploaderToken([pscustomobject]$cfg) {
    $protected = [string]$cfg.UploaderTokenProtected
    if ($protected -ne $script:UpTokenSource) {
        $script:UpTokenSource = $protected
        $script:UpTokenPlain = Unprotect-Token $protected
    }
    $script:UpTokenPlain
}

function Add-UploaderHeader([System.Net.Http.HttpRequestMessage]$req, [pscustomobject]$cfg) {
    $token = Get-UploaderToken $cfg
    if ($token) { $req.Headers.TryAddWithoutValidation($script:TokenHeader, $token) | Out-Null }
}

# Every request to the platform that is not the worklist leaves through here,
# so that the token is on all of them.
function Send-DeviceRequest([System.Net.Http.HttpClient]$client, [pscustomobject]$cfg, [string]$method, [string]$path, $content = $null) {
    $req = New-Object System.Net.Http.HttpRequestMessage $method, (Get-ApiUrl $cfg $path)
    if ($null -ne $content) { $req.Content = $content }
    Add-UploaderHeader $req $cfg
    $client.SendAsync($req).GetAwaiter().GetResult()
}

function Read-ResponseText($resp) {
    try { $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult() } catch { '' }
}

# What a 401 from the platform means. It answers {"message":"uploader token
# required"} to a request that needs a token and carries none, and
# {"message":"uploader token rejected"} to one whose token is unknown, revoked
# or belongs to a removed location. Any other 401 text (a proxy, an older
# platform) counts as a rejection. Whatever the text, a 401 is the platform not
# accepting this uploader: it must never end with a file filed anywhere.
function Get-TokenProblemCode([int]$status, [string]$body) {
    if ($status -ne 401) { return $null }
    if ($body -match 'uploader token required') { return 'token-required' }
    'token-rejected'
}

# The words for the tray, and the same for the icon's tooltip (63 characters).
function Get-TokenProblemText([string]$code) {
    if ($code -eq 'token-required') { 'Token fehlt' } else { 'Token abgelehnt - bitte in den Einstellungen pruefen' }
}
function Get-TokenProblemTip([string]$code) {
    if ($code -eq 'token-required') { 'Token fehlt' } else { 'Token abgelehnt - Einstellungen pruefen' }
}

# The token problem in a set of coded problems (code -> $true), or ''.
function Get-TokenProblemIn([hashtable]$problems) {
    foreach ($code in @('token-rejected', 'token-required')) { if ($problems.ContainsKey($code)) { return $code } }
    ''
}

# A refused token ends the round it happens in. The throw carries the code
# (not the token) up to the one place that records it.
function New-TokenRefusal([string]$code) {
    $ex = New-Object System.InvalidOperationException ('uploader token refused: ' + $code)
    $ex.Data['tokenProblem'] = $code
    $ex
}

function Get-TokenRefusalCode($errorRecord) {
    $e = $errorRecord.Exception
    while ($e) {
        if ($e.Data.Contains('tokenProblem')) { return [string]$e.Data['tokenProblem'] }
        $e = $e.InnerException
    }
    $null
}

# ----------------------------------------------------------------------------
# health - what the heartbeat reports (DR-033). Counts, ages, disk figures and
# coded problems; never a label, a filename or anything about a patient.
# ----------------------------------------------------------------------------
# Its own client with a short timeout: a heartbeat that hangs must not hold
# the tray for the up-to-an-hour an .e2e upload may take.
$script:HbHttp = New-Object System.Net.Http.HttpClient
$script:HbHttp.Timeout = [TimeSpan]::FromSeconds(10)
$script:HbLastCode = -1           # the last heartbeat's HTTP status; a change is logged, a repeat is not
$script:HbLastProblem = ''        # ... with the token problem a 401 came with; a change in it is logged too
$script:HbProblem = ''            # the token problem of the last heartbeat (token-required / token-rejected), for -Heartbeat
$script:StopSent = $false
$script:LastActivityUtc = $null   # end of the last sweep
$script:LastUploadUtc = $null     # the last 201
$script:UploadedDay = (Get-Date).Date
$script:UploadedToday = 0
# code -> $true, rebuilt by every sweep: the problems of the last sweep that ran
$script:SweepProblems = @{}

function Add-Problem([string]$code) { $script:SweepProblems[$code] = $true }

$script:TokenLogged = ''          # the token refusal last written to the log; a repeat is not (the sweep retries)

# A token the platform refused, recorded where the sweep ends: the coded problem
# for the heartbeat, one line in the log (a refusal that persists is written
# once, not at every retry) and the words for the tray, which are returned.
function Write-TokenRefusal([string]$code) {
    Add-Problem $code
    if ($code -ne $script:TokenLogged) {
        $script:TokenLogged = $code
        Write-Log "sweep: the platform refused the uploader token ($code) - the files stay where they are, the next sweep tries again" 'ERROR'
    }
    Get-TokenProblemText $code
}

function Add-Uploaded {
    if ((Get-Date).Date -ne $script:UploadedDay) { $script:UploadedDay = (Get-Date).Date; $script:UploadedToday = 0 }
    $script:UploadedToday++
    $script:LastUploadUtc = [datetime]::UtcNow
}

function Get-UploadedToday {
    if ((Get-Date).Date -ne $script:UploadedDay) { return 0 }
    $script:UploadedToday
}

function Get-InstanceId([pscustomobject]$cfg) {
    if (-not $cfg.InstanceId) {
        $cfg.InstanceId = [guid]::NewGuid().ToString()
        try { Save-Config $cfg } catch { Write-Log "config: could not save the new instance id: $($_.Exception.Message)" 'WARN' }
    }
    $cfg.InstanceId
}

function Get-DisplayName([pscustomobject]$cfg) {
    if ($cfg.DisplayName) { return [string]$cfg.DisplayName }
    if ($env:COMPUTERNAME) { return $env:COMPUTERNAME }
    [Environment]::MachineName
}

# The export files under $root that wait for upload, and how many were given
# up on. _uploaded\ and _skipped\ are not entered: they only grow, and a
# year of Clarus exports is a hundred thousand files nobody needs to list
# every twenty seconds.
function Get-WatchedFiles([string]$root) {
    $pending = New-Object Collections.ArrayList
    $failed = 0
    $dirs = New-Object Collections.Stack
    $dirs.Push($root)
    while ($dirs.Count -gt 0) {
        $dir = $dirs.Pop()
        foreach ($e in @(Get-ChildItem -LiteralPath $dir -ErrorAction SilentlyContinue)) {
            if ($e.PSIsContainer) {
                if ($e.Name -eq $script:FailedDir) {
                    $failed += @(Get-ChildItem -LiteralPath $e.FullName -File -ErrorAction SilentlyContinue |
                                 Where-Object { $_.Extension -match '^\.(dcm|e2e)$' }).Count
                } elseif ($e.Name -ne $script:UploadedDir -and $e.Name -ne $script:SkippedDir) {
                    $dirs.Push($e.FullName)
                }
            } elseif ($e.Extension -match '^\.(dcm|e2e)$') {
                [void]$pending.Add($e)
            }
        }
    }
    [pscustomobject]@{ Pending = @($pending.ToArray()); Failed = $failed }
}

# When a file arrived in the folder: a copy keeps the original's LastWriteTime
# (a 2021 capture exported today would look two years late) but gets a new
# CreationTime, so the later of the two.
function Get-ArrivedUtc([IO.FileInfo]$f) {
    if ($f.CreationTimeUtc -gt $f.LastWriteTimeUtc) { $f.CreationTimeUtc } else { $f.LastWriteTimeUtc }
}

function Get-DiskInfo([string]$path) {
    try {
        $root = [IO.Path]::GetPathRoot([IO.Path]::GetFullPath($path))
        if (-not $root -or $root.StartsWith('\\')) { return $null }   # a share: DriveInfo cannot size it
        $d = New-Object IO.DriveInfo($root)
        if (-not $d.IsReady) { return $null }
        [pscustomobject]@{ Free = [int64]$d.AvailableFreeSpace; Total = [int64]$d.TotalSize }
    } catch { $null }
}

function Get-HeartbeatBody([pscustomobject]$cfg, [bool]$running, [string]$stopReason) {
    $problems = New-Object Collections.ArrayList
    foreach ($k in $script:SweepProblems.Keys) { [void]$problems.Add($k) }
    $body = [ordered]@{
        instanceId           = (Get-InstanceId $cfg)
        kind                 = $script:Kind
        name                 = (Get-DisplayName $cfg)
        version              = $script:Version
        running              = $running
        enabled              = [bool]$cfg.Enabled
        heartbeatIntervalSec = [int]$cfg.HeartbeatIntervalSec
        uploadedToday        = [int](Get-UploadedToday)
    }
    if (-not $running) { $body.stopReason = $stopReason }
    $now = [datetime]::UtcNow
    if ($script:LastActivityUtc) { $body.secondsSinceActivity = [int64]($now - $script:LastActivityUtc).TotalSeconds }
    if ($script:LastUploadUtc) { $body.secondsSinceUpload = [int64]($now - $script:LastUploadUtc).TotalSeconds }
    if (Test-Path -LiteralPath $cfg.WatchFolder) {
        $w = Get-WatchedFiles $cfg.WatchFolder
        $body.pendingFiles = $w.Pending.Count
        $body.failedFiles = $w.Failed
        if ($w.Pending.Count -gt 0) {
            $oldest = $now
            foreach ($f in $w.Pending) { $a = Get-ArrivedUtc $f; if ($a -lt $oldest) { $oldest = $a } }
            $body.oldestPendingMinutes = [int][Math]::Floor(($now - $oldest).TotalMinutes)
        }
        $disk = Get-DiskInfo $cfg.WatchFolder
        if ($disk) { $body.diskFreeBytes = $disk.Free; $body.diskTotalBytes = $disk.Total }
    } elseif (-not $problems.Contains('watch-folder-missing')) {
        [void]$problems.Add('watch-folder-missing')
    }
    $body.problems = @($problems.ToArray())
    $body
}

# One heartbeat. Never throws: a platform that cannot be reached is exactly
# when the rest of the program must carry on. Returns the HTTP status (0 when
# nothing answered).
function Send-Heartbeat([pscustomobject]$cfg, [bool]$running = $true, [string]$stopReason = '') {
    $code = 0
    $problem = ''
    try {
        $json = Get-HeartbeatBody $cfg $running $stopReason | ConvertTo-Json -Depth 4 -Compress
        $content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, 'application/json')
        $resp = Send-DeviceRequest $script:HbHttp $cfg 'POST' '/api/v1/device/uploader/heartbeat' $content
        $code = [int]$resp.StatusCode
        # A 401 usually means no heartbeat arrives at all, so this line and the
        # sweep's tray status are the only places the refusal can be seen.
        if ($code -eq 401) { $problem = Get-TokenProblemCode 401 (Read-ResponseText $resp) }
    } catch { $code = 0 }
    $script:HbProblem = $problem
    if ($code -ne $script:HbLastCode -or $problem -ne $script:HbLastProblem) {
        $script:HbLastCode = $code
        $script:HbLastProblem = $problem
        switch ($code) {
            200     { Write-Log 'heartbeat: reporting to the platform' }
            0       { Write-Log 'heartbeat: the platform does not answer' 'WARN' }
            401     { Write-Log "heartbeat: the platform refused the uploader token ($problem)" 'WARN' }
            404     { Write-Log 'heartbeat: the platform does not take heartbeats (an older version, or switched off there)' 'WARN' }
            429     { Write-Log 'heartbeat: the platform refused it (HTTP 429: too many uploaders registered, or too many heartbeats from this address)' 'WARN' }
            default { Write-Log "heartbeat: HTTP $code" 'WARN' }
        }
    }
    $code
}

# The last word before the program ends. Sent once, however it ends.
function Send-StopHeartbeat([pscustomobject]$cfg, [string]$reason) {
    if ($script:StopSent) { return }
    $script:StopSent = $true
    Send-Heartbeat $cfg $false $reason | Out-Null
}

# ----------------------------------------------------------------------------
# DICOM header reader - only what this script needs (as in OptomedBridge.ps1)
# ----------------------------------------------------------------------------
$script:LongVRs = @('OB','OW','OF','SQ','UT','UN')

# Length of the element whose 4-byte tag has just been read. 0xFFFFFFFF means
# undefined (a sequence, or encapsulated pixel data) - compared below as
# [uint32]::MaxValue, because PowerShell parses the hex literal 0xFFFFFFFF as
# the Int32 -1, and a [uint32] length never equals that. Group 0002 is always
# explicit VR little endian; the dataset's syntax is what 0002,0010 said.
function Read-ElemLength([IO.BinaryReader]$r, [bool]$explicit) {
    if (-not $explicit) { return $r.ReadUInt32() }
    $vr = [Text.Encoding]::ASCII.GetString($r.ReadBytes(2))
    if ($script:LongVRs -contains $vr) { $r.ReadUInt16() | Out-Null; return $r.ReadUInt32() }
    [uint32]$r.ReadUInt16()
}

# Positioned just after an undefined-length header: consume the items up to
# the sequence delimiter, recursing into nested sequences. The Clarus writes
# undefined-length sequences (SourceImageSequence, AnatomicRegionSequence) in
# group 0008 - BEFORE the patient group - so a reader that stops at the first
# one never sees the PatientID. Verified on Clarus 700 exports, 2026-09-24.
function Skip-Sequence([IO.BinaryReader]$r, [bool]$explicit) {
    $fs = $r.BaseStream
    while ($fs.Position -lt $fs.Length) {
        $group = $r.ReadUInt16(); $elem = $r.ReadUInt16(); $len = $r.ReadUInt32()
        if ($group -ne 0xFFFE) { throw 'malformed DICOM sequence' }
        if ($elem -eq 0xE0DD) { return }                            # sequence delimiter
        if ($elem -ne 0xE000) { throw 'malformed DICOM sequence' }
        if ($len -ne [uint32]::MaxValue) { $fs.Position += $len; continue }
        while ($fs.Position -lt $fs.Length) {                       # undefined-length item
            $g = $r.ReadUInt16(); $e = $r.ReadUInt16()
            if ($g -eq 0xFFFE -and $e -eq 0xE00D) { $r.ReadUInt32() | Out-Null; break }   # item delimiter
            $l = Read-ElemLength $r $explicit
            if ($l -eq [uint32]::MaxValue) { Skip-Sequence $r $explicit } else { $fs.Position += $l }
        }
    }
}

function Read-DicomTags {
    param([string]$Path)
    $tags = @{ PatientID = ''; StudyDate = ''; Laterality = ''; ImageLaterality = ''; SOPClassUID = '' }
    $want = @{ '0008,0016' = 'SOPClassUID'; '0008,0020' = 'StudyDate'; '0010,0020' = 'PatientID'
               '0020,0060' = 'Laterality'; '0020,0062' = 'ImageLaterality' }
    $fs = [IO.File]::Open($Path, 'Open', 'Read', 'Read')
    try {
        $r = New-Object IO.BinaryReader($fs)
        if ($fs.Length -lt 132) { return $tags }
        $fs.Position = 128
        if ([Text.Encoding]::ASCII.GetString($r.ReadBytes(4)) -ne 'DICM') { $fs.Position = 0 }
        $explicit = $true
        while ($fs.Position -lt $fs.Length) {
            $group = $r.ReadUInt16(); $elem = $r.ReadUInt16()
            if ($group -gt 0x0020) { break }                        # everything wanted has been passed
            if ($group -ne 2 -and $explicit) {
                # no VR where one should be: implicit little endian without a meta header
                $probe = [Text.Encoding]::ASCII.GetString($r.ReadBytes(2)); $fs.Position -= 2
                if ($probe -notmatch '^[A-Z]{2}$') { $explicit = $false }
            }
            $len = Read-ElemLength $r ($explicit -or $group -eq 2)
            if ($len -eq [uint32]::MaxValue) { Skip-Sequence $r $explicit; continue }
            $key = ('{0:X4},{1:X4}' -f $group, $elem)
            if ($want.ContainsKey($key)) {
                $tags[$want[$key]] = [Text.Encoding]::ASCII.GetString($r.ReadBytes([int]$len)).Trim([char]0, ' ')
            } elseif ($group -eq 2 -and $elem -eq 0x0010) {
                $ts = [Text.Encoding]::ASCII.GetString($r.ReadBytes([int]$len)).Trim([char]0, ' ')
                $explicit = ($ts -ne '1.2.840.10008.1.2')
            } else { $fs.Position += $len }
        }
    } finally { $fs.Dispose() }
    $tags
}

function ConvertTo-Laterality([string]$dicom) {
    switch ($dicom.Trim().ToUpperInvariant()) { 'R' { 'OD' } 'L' { 'OS' } 'B' { 'OU' } default { $null } }
}

function ConvertTo-IsoDate([string]$da) {
    if ($da -match '^(\d{4})(\d{2})(\d{2})') { '{0}-{1}-{2}' -f $Matches[1], $Matches[2], $Matches[3] } else { $null }
}


# ----------------------------------------------------------------------------
# what a file would send
# ----------------------------------------------------------------------------
function Get-FileScans([pscustomobject]$cfg, [IO.FileInfo]$f) {
    # -> array of @{ PatientId; ScanDate; Laterality; ScanIndex; Device; Kind }
    if ($f.Extension -match '^\.dcm$') {
        $t = Read-DicomTags $f.FullName
        $lat = $null
        if ($t.ImageLaterality) { $lat = ConvertTo-Laterality $t.ImageLaterality } elseif ($t.Laterality) { $lat = ConvertTo-Laterality $t.Laterality }
        $nonImage = [bool]@($script:NonImageSopPrefixes | Where-Object { $t.SOPClassUID.StartsWith($_) })
        return ,@([pscustomobject]@{ PatientId = $t.PatientID; ScanDate = (ConvertTo-IsoDate $t.StudyDate); Laterality = $lat; ScanIndex = 0; Device = $cfg.DeviceDicom; Kind = 'dicom'; NonImage = $nonImage })
    }
    throw 'Get-FileScans reads DICOM only; an .e2e is uploaded whole and split by the platform (Invoke-E2eUploads)'
}

# ----------------------------------------------------------------------------
# Spectralis .e2e (DR-041) - uploaded whole, split into series by the platform
#
# The header is not read here any more. The folder the file sits in is the
# subject: <WatchFolder>\...\<subject>\x.e2e. The platform compares the header's
# patient id with it and parks the file's series when they differ.
# ----------------------------------------------------------------------------
function Test-IsE2e([IO.FileInfo]$f) { $f.Extension -ieq '.e2e' }

# The name of the folder that directly contains the file (its immediate parent), or
# $null for a file that sits directly in the watch folder (no subject: not uploaded).
function Get-E2eSubject([string]$root, [IO.FileInfo]$f) {
    $rootFull = [IO.Path]::GetFullPath($root).TrimEnd('\', '/')
    $dir = [IO.Path]::GetFullPath($f.DirectoryName).TrimEnd('\', '/')
    if ($dir.Length -le $rootFull.Length) { return $null }
    if (-not $dir.StartsWith($rootFull, [StringComparison]::OrdinalIgnoreCase)) { return $null }
    $next = $dir[$rootFull.Length]
    if ($next -ne '\' -and $next -ne '/') { return $null }      # a sibling such as <root>2, not a folder below it
    $last = Split-Path -Leaf $dir      # the folder that directly contains the file
    if ($last) { $last } else { $null }
}

# SHA-256 of a file, streamed (the file is never held in memory). Lower-case hex.
function Get-FileSha256([string]$path) {
    $fs = [IO.File]::Open($path, 'Open', 'Read', 'Read')
    try {
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $hash = $sha.ComputeHash($fs) } finally { $sha.Dispose() }
    } finally { $fs.Dispose() }
    -join ($hash | ForEach-Object { $_.ToString('x2') })
}

# A property of a parsed JSON object, or $null (strict mode throws on a missing one).
function Get-JsonProp($obj, [string]$name) {
    if ($null -ne $obj -and $obj.PSObject.Properties[$name]) { return $obj.$name }
    $null
}

# GET preflight: is this file (by content) already split on the platform?
# -> @{ Status; Body; Exists }. Only a clean 200 {"exists":true} is a hit;
# anything else goes on to the commit, whose 409 is the authority.
function Get-E2ePreflight([pscustomobject]$cfg, [string]$sha256) {
    $resp = Send-DeviceRequest $script:Http $cfg 'GET' ('/api/v1/public/upload/preflight?sha256={0}&split=true' -f $sha256)
    $status = [int]$resp.StatusCode
    $exists = $false
    $body = Read-ResponseText $resp
    if ($status -eq 200) {
        try { $exists = ((Get-JsonProp ($body | ConvertFrom-Json) 'exists') -eq $true) } catch { $exists = $false }
    }
    [pscustomobject]@{ Status = $status; Body = $body; Exists = $exists }
}

# POST commit, split mode: ONE streamed multipart request for the whole file.
# The file travels as a StreamContent over a read-only FileStream - never
# ReadAllBytes. No scanDate / laterality / scanIndex / studyEventId / park:
# the platform splits the file and decides (DR-041 Contract 2).
function Send-E2eCommit([pscustomobject]$cfg, [IO.FileInfo]$f, [string]$subject) {
    $fs = [IO.File]::Open($f.FullName, 'Open', 'Read', 'Read')
    $mp = New-Object System.Net.Http.MultipartFormDataContent
    try {
        $fc = New-Object System.Net.Http.StreamContent($fs, 81920)
        $fc.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/octet-stream')
        $mp.Add($fc, 'file', 'scan.e2e')      # neutral name: an export is often named after the patient
        $fields = [ordered]@{ split = 'true'; patientId = $subject; device = [string]$cfg.DeviceE2e }
        foreach ($k in $fields.Keys) { $mp.Add((New-Object System.Net.Http.StringContent([string]$fields[$k])), $k) }
        $client = New-UploadClient (Get-UploadTimeoutSec $f.Length)
        try {
            $resp = Send-DeviceRequest $client $cfg 'POST' '/api/v1/public/upload/commit' $mp
            [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = (Read-ResponseText $resp) }
        } finally { $client.Dispose() }
    } finally {
        $mp.Dispose()      # disposes the StreamContent and with it the FileStream
        $fs.Dispose()
    }
}

# What a 201 says about the file: how many series became items, and of those
# how many were bound and how many parked. @{ Items; Bound; Parked }.
# "parked": true means every item went to the inbox; otherwise an item still
# UNBOUND is in the inbox, any other status is bound.
function Get-E2eCommitCounts([string]$body) {
    $items = 0; $bound = 0; $parked = 0
    try {
        $j = $body | ConvertFrom-Json
        $list = @(Get-JsonProp $j 'items')
        $allParked = ((Get-JsonProp $j 'parked') -eq $true)
        foreach ($it in $list) {
            if ($null -eq $it) { continue }
            $items++
            if ($allParked -or [string](Get-JsonProp $it 'status') -ceq 'UNBOUND') { $parked++ } else { $bound++ }
        }
    } catch { }
    [pscustomobject]@{ Items = $items; Bound = $bound; Parked = $parked }
}

# Uploads the settled .e2e files of a sweep (each has a subject folder). Each
# one: SHA-256 (streamed), preflight, and - if the platform does not have it -
# one streamed commit. 201 and 409 move the file to _uploaded\; a 401 ends the
# round with the refused file (and every later one) untouched; anything else is
# retried at the next sweep and goes to _failed\ after MaxAttempts.
# -> counts, and TokenProblem (a code, or $null).
function Invoke-E2eUploads([pscustomobject]$cfg, [object[]]$files) {
    $r = [pscustomobject]@{ Files = 0; Series = 0; Bound = 0; Parked = 0; Already = 0; Failed = 0; TokenProblem = $null }
    foreach ($f in $files) {
        $subject = Get-E2eSubject $cfg.WatchFolder $f
        $outcome = $null      # 'ok' | 'failed'
        if ($f.Length -gt $script:MaxE2eBytes) {
            # over what the platform takes: not worth five attempts of two gigabytes
            Add-Problem 'file-too-large'; $r.Failed++
            Write-Log 'upload: an .e2e file is over 2 GiB - moved to _failed' 'ERROR'
            try { Move-Aside $f $script:FailedDir } catch { Add-Problem 'unreadable-files' }
            continue
        }
        try {
            $sha = Get-FileSha256 $f.FullName
            $pre = Get-E2ePreflight $cfg $sha
            $refused = Get-TokenProblemCode $pre.Status $pre.Body
            if ($refused) { $r.TokenProblem = $refused; break }
            if ($pre.Status -eq 429) { Add-Problem 'rate-limited'; Write-Log 'upload: preflight HTTP 429 - the rest waits for the next sweep' 'WARN'; break }
            if ($pre.Exists) {
                $r.Already++; $outcome = 'ok'
            } else {
                $c = Send-E2eCommit $cfg $f $subject
                $refused = Get-TokenProblemCode $c.Status $c.Body
                if ($refused) { $r.TokenProblem = $refused; break }
                switch ($c.Status) {
                    201 {
                        $n = Get-E2eCommitCounts $c.Body
                        $r.Files++; $r.Series += $n.Items; $r.Bound += $n.Bound; $r.Parked += $n.Parked
                        $outcome = 'ok'; Add-Uploaded
                    }
                    409 { $r.Already++; $outcome = 'ok' }
                    default {
                        $r.Failed++; $outcome = 'failed'
                        Add-Problem $(if ($c.Status -eq 429) { 'rate-limited' } else { 'upload-failed' })
                        $why = ($c.Body -replace '\s+', ' ')
                        Write-Log ("upload: HTTP {0} for label '{1}' ({2})" -f $c.Status, $subject, $why.Substring(0, [Math]::Min(120, $why.Length))) 'WARN'
                    }
                }
            }
        } catch {
            $r.Failed++; $outcome = 'failed'
            Add-Problem 'server-unreachable'
            Write-Log "upload: $($_.Exception.Message)" 'ERROR'
        }
        if ($outcome -eq 'ok') { Move-Aside $f $script:UploadedDir }
        elseif ($outcome -eq 'failed') {
            $n = 1 + [int]$script:Attempts[$f.FullName]; $script:Attempts[$f.FullName] = $n
            if ($n -ge $script:MaxAttempts) {
                Write-Log ("upload: giving up on an .e2e file after {0} attempts - moved to {1}" -f $n, $script:FailedDir) 'ERROR'
                Move-Aside $f $script:FailedDir
            }
        }
        Start-Sleep -Milliseconds 500     # the public front door is rate-limited per client
    }
    $r
}

# ----------------------------------------------------------------------------
# platform: resolve (one call per sweep) and commit (one call per scan)
# ----------------------------------------------------------------------------
function Resolve-Batch([pscustomobject]$cfg, [object[]]$scans) {
    # -> array parallel to $scans of studyEventId or $null. 'suggested' with
    # exactly one candidate holding a matching visit is the only state in
    # which binding without a person is defensible; everything else lands in
    # the inbox with the hints.
    $result = @($scans | ForEach-Object { $null })
    $ask = @(); $map = @()
    for ($i = 0; $i -lt $scans.Count; $i++) {
        $s = $scans[$i]
        if ($s.PatientId -and $s.ScanDate) { $ask += @{ patientId = $s.PatientId; scanDate = $s.ScanDate; laterality = $s.Laterality }; $map += $i }
    }
    if ($ask.Count -eq 0) { return ,$result }
    $payload = @{ scans = $ask } | ConvertTo-Json -Depth 4 -Compress
    $content = New-Object System.Net.Http.StringContent($payload, [Text.Encoding]::UTF8, 'application/json')
    $resp = Send-DeviceRequest $script:Http $cfg 'POST' '/api/v1/public/upload/resolve' $content
    if (-not $resp.IsSuccessStatusCode) {
        $status = [int]$resp.StatusCode
        # A 401 is not a lookup that failed - the platform does not take this
        # uploader as it is. Carrying on and filing the captures without a visit,
        # as a 429 does, would be wrong: the sweep ends here with every file
        # untouched (see Invoke-Sweep).
        if ($status -eq 401) { throw (New-TokenRefusal (Get-TokenProblemCode 401 (Read-ResponseText $resp))) }
        if ($status -eq 429) { Add-Problem 'rate-limited' }
        Write-Log "resolve: HTTP $status - filing without a visit this sweep" 'WARN'
        return ,$result
    }
    $r = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    for ($j = 0; $j -lt $map.Count; $j++) {
        $scan = $r.scans[$j]
        if ($scan.state -eq 'suggested' -and @($scan.candidates).Count -eq 1 -and $scan.candidates[0].matchingEvent) {
            $result[$map[$j]] = $scan.candidates[0].matchingEvent.studyEventId
        }
    }
    ,$result
}

function Send-Commit([pscustomobject]$cfg, [IO.FileInfo]$f, [pscustomobject]$scan, $studyEventId) {
    # DICOM only (a Clarus object is a few megabytes); an .e2e goes through Send-E2eCommit.
    $mp = New-Object System.Net.Http.MultipartFormDataContent
    $fc = New-Object System.Net.Http.ByteArrayContent(,[IO.File]::ReadAllBytes($f.FullName))
    $fc.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/dicom')
    $mp.Add($fc, 'file', 'image.dcm')     # neutral names: the real one may carry the patient's
    $fields = @{ patientId = $scan.PatientId; scanDate = $scan.ScanDate; laterality = $scan.Laterality
                 scanIndex = $scan.ScanIndex; device = $scan.Device; studyEventId = $studyEventId }
    foreach ($k in $fields.Keys) {
        if ($null -ne $fields[$k] -and "$($fields[$k])" -ne '') { $mp.Add((New-Object System.Net.Http.StringContent("$($fields[$k])")), $k) }
    }
    $resp = Send-DeviceRequest $script:Http $cfg 'POST' '/api/v1/public/upload/commit' $mp
    [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult() }
}

# ----------------------------------------------------------------------------
# the sweep
# ----------------------------------------------------------------------------
function Test-Settled([IO.FileInfo]$f) {
    # size unchanged since the previous sweep, and openable without sharing
    $key = $f.FullName
    $seen = $script:SizeSeen[$key]
    $script:SizeSeen[$key] = $f.Length
    if ($null -eq $seen -or $seen -ne $f.Length -or $f.Length -eq 0) { return $false }
    try { $h = [IO.File]::Open($key, 'Open', 'Read', 'None'); $h.Dispose(); $true } catch { $false }
}

function Move-Aside([IO.FileInfo]$f, [string]$sub) {
    $dest = Join-Path $f.DirectoryName $sub
    if (-not (Test-Path $dest)) { New-Item -ItemType Directory -Force $dest | Out-Null }
    $target = Join-Path $dest $f.Name
    Move-Item -Force $f.FullName $target
    if ($sub -eq $script:UploadedDir) {
        # Stamp the copy with the upload time; the retention counts from it.
        # The export date is inside the file, and a copy that has served its
        # purpose has no other use for its timestamp.
        try { (Get-Item -LiteralPath $target).LastWriteTimeUtc = [datetime]::UtcNow } catch { }
    }
    $script:SizeSeen.Remove($f.FullName); $script:Attempts.Remove($f.FullName)
}

# The files a sweep set aside or gave up on without asking the platform (see
# Invoke-SweepCore). Never throws: a file that will not move stays, and is met
# again by the next sweep.
function Move-SetAside($skipped, $failed) {
    foreach ($f in @($skipped)) {
        try { Move-Aside $f $script:SkippedDir } catch { Add-Problem 'unreadable-files'; Write-Log 'sweep: a non-image object could not be set aside' 'WARN' }
    }
    foreach ($f in @($failed)) {
        try { Move-Aside $f $script:FailedDir } catch { Add-Problem 'unreadable-files'; Write-Log 'sweep: an unreadable file could not be moved to _failed' 'WARN' }
    }
}

# ----------------------------------------------------------------------------
# retention of uploaded exports (2026-09-28)
# ----------------------------------------------------------------------------
# more than DeleteUploadedAfterDays ago: only _uploaded folders (the watch
# folder's and, since DR-041, each patient folder's), only their top level,
# more than DeleteUploadedAfterDays ago: only the _uploaded folders (the watch folder's and each patient folder's), only their top level,
# only .dcm and .e2e files. The upload time is the copy's modification time,
# which Move-Aside stamps; a copy from before this version carries the
# export's own time, which is earlier, so it goes no later than it should.
# At most once an hour: _uploaded\ can hold a year of exports, and listing it
# every twenty seconds is what Get-WatchedFiles avoids. Counts only in the
# log, never a filename.
function Invoke-Retention([pscustomobject]$cfg) {
    if (-not [bool]$cfg.DeleteUploaded) { return }
    $days = [int]$cfg.DeleteUploadedAfterDays
    if ($days -lt 1) { Write-Log 'retention: DeleteUploadedAfterDays must be at least 1; nothing deleted' 'WARN'; return }
    $now = [datetime]::UtcNow
    if (($now - $script:LastRetentionUtc).TotalMinutes -lt $script:RetentionEveryMinutes) { return }
    $script:LastRetentionUtc = $now
    $cutoff = $now.AddDays(-$days)
    $deleted = 0; $failed = 0; $kept = 0
    # Every _uploaded\ folder: the one in the watch folder and, since DR-041, the
    # one inside each patient folder (Move-Aside files a file next to where it was).
    $uploadedDirs = New-Object Collections.ArrayList
    $stack = New-Object Collections.Stack
    $stack.Push($cfg.WatchFolder)
    while ($stack.Count -gt 0) {
        $d = $stack.Pop()
        foreach ($e in @(Get-ChildItem -LiteralPath $d -Directory -ErrorAction SilentlyContinue)) {
            if ($e.Name -eq $script:UploadedDir) { [void]$uploadedDirs.Add($e.FullName) }
            elseif ($e.Name -ne $script:FailedDir -and $e.Name -ne $script:SkippedDir) { $stack.Push($e.FullName) }
        }
    }
    foreach ($dir in $uploadedDirs) {
        foreach ($f in @(Get-ChildItem -LiteralPath $dir -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -match '^\.(dcm|e2e)$' })) {
            if ($f.LastWriteTimeUtc -ge $cutoff) { $kept++; continue }
            try { Remove-Item -LiteralPath $f.FullName -Force; $deleted++ } catch { $failed++ }
        }
    }
    if ($deleted -gt 0 -or $failed -gt 0) {
        Write-Log ("retention: deleted {0} uploaded export(s) older than {1} day(s); {2} kept, {3} could not be deleted" -f $deleted, $days, $kept, $failed)
    }
}

# The problems the heartbeat reports are those of the last sweep: rebuilt on
# every sweep, so one that went away stops being reported by itself.
function Invoke-Sweep([pscustomobject]$cfg) {
    $script:SweepProblems = @{}
    try { Invoke-SweepCore $cfg }
    catch {
        # A refused uploader token is neither the network nor a file's failure:
        # the sweep stopped with every file where it was, and the words for the
        # tray are its result. The next sweep tries again, at the normal interval.
        $refused = Get-TokenRefusalCode $_
        if ($refused) { return (Write-TokenRefusal $refused) }
        # Whatever the sweep did not handle itself was the network or the platform.
        Add-Problem 'server-unreachable'
        throw
    }
    finally { $script:LastActivityUtc = [datetime]::UtcNow }
}

$script:NoSubjectLogged = -1      # the "no subject folder" count last written to the log; a repeat is not (the sweep runs every 20 s)

function Invoke-SweepCore([pscustomobject]$cfg) {
    $root = $cfg.WatchFolder
    if (-not (Test-Path $root)) {
        Add-Problem 'watch-folder-missing'
        Write-Log "sweep: watch folder missing: $root" 'ERROR'
        return 'watch folder missing'
    }
    try { Invoke-Retention $cfg } catch { Write-Log "retention: $($_.Exception.Message)" 'WARN' }
    $all = @((Get-WatchedFiles $root).Pending)

    # An .e2e directly in the watch folder has no subject (the folder name is
    # the subject label): it is not uploaded, only counted. Coded problem and a
    # count in the log - never a name.
    $noSubject = @($all | Where-Object { (Test-IsE2e $_) -and $null -eq (Get-E2eSubject $root $_) })
    if ($noSubject.Count -gt 0) { Add-Problem 'no-subject-folder' }
    if ($noSubject.Count -ne $script:NoSubjectLogged) {
        $script:NoSubjectLogged = $noSubject.Count
        if ($noSubject.Count -gt 0) { Write-Log ("sweep: {0} .e2e file(s) directly in the watch folder - not uploaded, they need a patient folder" -f $noSubject.Count) 'WARN' }
    }
    $files = @($all | Where-Object { -not (Test-IsE2e $_) -or $null -ne (Get-E2eSubject $root $_) })
    $tail = $(if ($noSubject.Count -gt 0) { ', {0} without subject folder' -f $noSubject.Count } else { '' })
    if ($files.Count -eq 0) { return ('nothing to upload' + $tail) }
    $ready = @($files | Where-Object { Test-Settled $_ })
    if ($ready.Count -eq 0) { return ('{0} file(s) still being written' -f $files.Count) + $tail }
    $readyE2e = @($ready | Where-Object { Test-IsE2e $_ })
    $readyDcm = @($ready | Where-Object { -not (Test-IsE2e $_) })

    $dicomSummary = $null
    if ($readyDcm.Count -gt 0) { $dicomSummary = Invoke-DicomSweep $cfg $readyDcm }     # throws on a refused token
    if ($readyE2e.Count -eq 0) { return ([string]$dicomSummary + $tail) }

    $e = Invoke-E2eUploads $cfg $readyE2e
    if ($e.TokenProblem) {
        if (($e.Files + $e.Already) -gt 0) { Write-Log ("sweep: uploaded {0} file(s) -> {1} series, {2} already there, then the platform refused the uploader token" -f $e.Files, $e.Series, $e.Already) }
        throw (New-TokenRefusal $e.TokenProblem)
    }
    $script:TokenLogged = ''      # the platform took the token: a refusal after this is news again
    $summary = 'uploaded {0} files -> {1} series ({2} bound, {3} parked), {4} already there, {5} failed, {6} without subject folder' -f $e.Files, $e.Series, $e.Bound, $e.Parked, $e.Already, $e.Failed, $noSubject.Count
    Write-Log "sweep: $summary"
    if ($dicomSummary) { $summary = $summary + '; DICOM: ' + $dicomSummary }
    $summary
}

# The DICOM part of a sweep: read the headers, ONE /resolve for all of them,
# one commit per file. Unchanged since before DR-041.
function Invoke-DicomSweep([pscustomobject]$cfg, [object[]]$ready) {
    if ($ready.Count -eq 0) { return ('{0} file(s) still being written' -f $files.Count) }

    # identify everything first, so one /resolve covers the sweep
    $work = New-Object Collections.ArrayList   # @{ File; Scan }
    $unreadable = 0; $setAside = 0
    # What this sweep decides without the platform - Raw Data objects to set
    # aside, unreadable files out of attempts - is moved only at the end, once
    # the platform has answered: a sweep that stops on a refused token moves
    # no file at all (see Invoke-Sweep).
    $skipLater = New-Object Collections.ArrayList
    $failLater = New-Object Collections.ArrayList
    foreach ($f in $ready) {
        try {
            # No @( ) around this call. Get-FileScans returns its list as ,@(...), which is what keeps a
            # single scan an array; @( ) would keep that wrapper too and nest two or more scans inside ONE
            # element, and the filter below would then drop the file as a non-image (a two-volume .e2e
            # was set aside to _skipped\ and never uploaded). Assigned plainly, $scans is the list itself.
            $scans = Get-FileScans $cfg $f
            if (-not $cfg.UploadNonImage -and @($scans | Where-Object { -not $_.NonImage }).Count -eq 0) {
                # a Raw Data object or a report, not a picture: not for the visit
                $setAside++; [void]$skipLater.Add($f); continue
            }
            foreach ($s in $scans) { [void]$work.Add(@{ File = $f; Scan = $s }) }
        } catch {
            $unreadable++
            Add-Problem 'unreadable-files'
            $n = 1 + [int]$script:Attempts[$f.FullName]; $script:Attempts[$f.FullName] = $n
            Write-Log ("identify: unreadable {0} file (attempt {1}): {2}" -f $f.Extension, $n, $_.Exception.Message) 'WARN'
            if ($n -ge $script:MaxAttempts) { [void]$failLater.Add($f) }
        }
    }
    if ($work.Count -eq 0) {
        Move-SetAside $skipLater $failLater       # nothing was asked of the platform, so nothing can have been refused
        $summary = 'nothing to upload ({0} unreadable, {1} non-image set aside)' -f $unreadable, $setAside
        if ($setAside -gt 0) { Write-Log "sweep: $summary" }
        return $summary
    }

    $scans = @($work | ForEach-Object { $_.Scan })
    $events = Resolve-Batch $cfg $scans       # throws on a refused token: nothing has been moved yet

    $ok = 0; $bound = 0; $dup = 0; $fail = 0
    $tokenProblem = $null
    $filesDone = @{}
    for ($i = 0; $i -lt $work.Count; $i++) {
        $f = $work[$i].File; $s = $work[$i].Scan; $ev = $events[$i]
        if ($filesDone.ContainsKey($f.FullName) -and $filesDone[$f.FullName] -eq 'failed') { continue }
        try {
            $r = Send-Commit $cfg $f $s $ev
            # A 401 is not this file's failure: it stays as it is, no attempt is
            # counted, and the loop ends - so every file after it stays too.
            $refused = Get-TokenProblemCode $r.Status $r.Body
            if ($refused) { $tokenProblem = $refused; $filesDone[$f.FullName] = 'refused'; break }
            switch ($r.Status) {
                201 { $ok++; if ($ev) { $bound++ }; $filesDone[$f.FullName] = 'ok'; Add-Uploaded }
                409 { $dup++; if (-not $filesDone.ContainsKey($f.FullName)) { $filesDone[$f.FullName] = 'ok' } }
                default {
                    $fail++; $filesDone[$f.FullName] = 'failed'
                    Add-Problem $(if ($r.Status -eq 429) { 'rate-limited' } else { 'upload-failed' })
                    $why = ($r.Body -replace '\s+', ' ')
                    Write-Log ("upload: HTTP {0} for label '{1}' ({2})" -f $r.Status, $s.PatientId, $why.Substring(0, [Math]::Min(120, $why.Length))) 'WARN'
                }
            }
        } catch {
            $fail++; $filesDone[$f.FullName] = 'failed'
            Add-Problem 'server-unreachable'
            Write-Log "upload: $($_.Exception.Message)" 'ERROR'
        }
        Start-Sleep -Milliseconds 500     # the public front door is rate-limited per client
    }
    foreach ($path in @($filesDone.Keys)) {
        $f = Get-Item $path -ErrorAction SilentlyContinue
        if (-not $f) { continue }
        if ($filesDone[$path] -eq 'ok') { Move-Aside $f $script:UploadedDir; continue }
        if ($filesDone[$path] -eq 'refused') { continue }
        $n = 1 + [int]$script:Attempts[$path]; $script:Attempts[$path] = $n
        if ($n -ge $script:MaxAttempts) { Write-Log ("upload: giving up on a {0} file after {1} attempts - moved to {2}" -f $f.Extension, $n, $script:FailedDir) 'ERROR'; Move-Aside $f $script:FailedDir }
    }
    if ($tokenProblem) {
        # What the platform accepted before the refusal is filed above; every
        # other file is exactly where it was.
        if (($ok + $dup) -gt 0) { Write-Log ("sweep: uploaded {0} ({1} bound), {2} already there, then the platform refused the uploader token" -f $ok, $bound, $dup) }
        throw (New-TokenRefusal $tokenProblem)
    }
    Move-SetAside $skipLater $failLater
    $script:TokenLogged = ''      # the platform took the token: a refusal after this is news again
    $summary = 'uploaded {0} ({1} bound), {2} already there, {3} failed, {4} non-image set aside' -f $ok, $bound, $dup, $fail, $setAside
    Write-Log "sweep: $summary"
    $summary
}

# ----------------------------------------------------------------------------
# headless modes
# ----------------------------------------------------------------------------
if ($SelfTest) {
    if (-not $File) { throw 'SelfTest needs -File <path to a .dcm or .e2e>' }
    $cfg = Read-Config
    $f = Get-Item -LiteralPath $File
    Write-Host ("{0} ({1:N0} bytes)" -f $f.Extension, $f.Length)
    if (Test-IsE2e $f) {
        # Nothing is read from the header (the platform splits the file): the
        # subject is the folder, the SHA-256 is what the preflight would ask.
        $subject = Get-E2eSubject $cfg.WatchFolder $f
        $where = 'folder that directly contains the file'
        if (-not $subject) {
            # the file is not in a sub-folder of the watch folder: show what the
            # rule gives for it if it were - the folder it sits in
            $subject = Split-Path -Leaf $f.DirectoryName
            $where = 'its own folder (the file is not below the watch folder)'
        }
        if ($f.Length -gt $script:MaxE2eBytes) { Write-Host '  over 2 GiB: would be moved to _failed, not uploaded' }
        Write-Host ("  subject (patientId) = '{0}' [{1}]; device={2}; split=true" -f $subject, $where, $cfg.DeviceE2e)
        Write-Host ("  sha256 = {0}" -f (Get-FileSha256 $f.FullName))
        exit 0
    }
    foreach ($s in (Get-FileScans $cfg $f)) {
        $note = $(if ($s.NonImage) { ' [non-image object: set aside]' } else { '' })
        Write-Host ("  scan {0}: label='{1}' date={2} laterality={3} device={4}{5}" -f $s.ScanIndex, $s.PatientId, $(if ($s.ScanDate) { $s.ScanDate } else { '-' }), $(if ($s.Laterality) { $s.Laterality } else { '-' }), $s.Device, $note)
    }
    exit 0
}

if ($Heartbeat) {
    $cfg = Read-Config
    $code = Send-Heartbeat $cfg
    $answer = if ($code -eq 200) { 'recorded' } elseif ($code -eq 0) { 'no answer' } else { "HTTP $code" }
    if ($script:HbProblem) { $answer = "HTTP 401 - $(Get-TokenProblemText $script:HbProblem) ($script:HbProblem)" }
    Write-Host ("heartbeat as '{0}' ({1} {2}) to {3}: {4}; uploader token: {5}" -f (Get-DisplayName $cfg), $script:Kind, $script:Version, $cfg.BaseUrl, $answer, (Get-UploaderTokenState $cfg))
    if ($code -eq 200) { exit 0 } else { exit 1 }
}

if ($Once) {
    $cfg = Read-Config
    Write-Log "$script:AppName one sweep of $($cfg.WatchFolder) against $($cfg.BaseUrl) (uploader token: $(Get-UploaderTokenState $cfg))"
    # a file must be seen twice to count as settled; a headless sweep does both passes
    Invoke-Sweep $cfg | Out-Null
    Start-Sleep -Seconds 2
    $summary = Invoke-Sweep $cfg
    Write-Host "result: $summary"
    # A scheduled-task deployment reports like the tray does; set
    # HeartbeatIntervalSec to the task's interval so the page does not read
    # the gaps between runs as silence.
    Send-Heartbeat $cfg | Out-Null
    exit 0
}

# ----------------------------------------------------------------------------
# tray app (Windows)
# ----------------------------------------------------------------------------
$mutex = New-Object Threading.Mutex($false, 'Global\LibreClinicaExportWatcher')
if (-not $mutex.WaitOne(0, $false)) { exit 0 }   # already running

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()

# Launched by hand - a double-click, a shell - rather than by the installer's
# hidden-window shortcut, the script owns a console window that sits on the
# desktop for as long as the tray icon lives (2026-09-24, first Windows run).
# The tray icon is this app's surface, so the console is hidden here, for
# every way of starting it. -Once and -SelfTest never reach this point: they
# print to that console and exit.
Add-Type -Namespace LibreClinicaTray -Name Console -MemberDefinition @'
[DllImport("kernel32.dll")] public static extern IntPtr GetConsoleWindow();
[DllImport("user32.dll")]   public static extern bool ShowWindow(IntPtr h, int cmd);
'@
$ownConsole = [LibreClinicaTray.Console]::GetConsoleWindow()
if ($ownConsole -ne [IntPtr]::Zero) { [LibreClinicaTray.Console]::ShowWindow($ownConsole, 0) | Out-Null }   # 0 = SW_HIDE

$script:Cfg = Read-Config
Write-Log "$script:AppName starting (enabled=$($script:Cfg.Enabled), folder=$($script:Cfg.WatchFolder), uploader token: $(Get-UploaderTokenState $script:Cfg))"

function New-TrayIcon([bool]$enabled) {
    # Drawn, not shipped: a filled square (the bridge is a circle), teal when enabled, grey when not.
    $bmp = New-Object Drawing.Bitmap 16, 16
    $g = [Drawing.Graphics]::FromImage($bmp)
    $brush = New-Object Drawing.SolidBrush ($(if ($enabled) { [Drawing.Color]::FromArgb(0, 128, 128) } else { [Drawing.Color]::Gray }))
    $g.FillRectangle($brush, 2, 2, 12, 12)
    $g.Dispose()
    [Drawing.Icon]::FromHandle($bmp.GetHicon())
}

$tray = New-Object Windows.Forms.NotifyIcon
$tray.Text = $script:AppName
$tray.Icon = New-TrayIcon $script:Cfg.Enabled
$tray.Visible = $true

$menu = New-Object Windows.Forms.ContextMenuStrip
$status = $menu.Items.Add('Idle'); $status.Enabled = $false
$menu.Items.Add('-') | Out-Null
$enabledItem = $menu.Items.Add('Enabled'); $enabledItem.CheckOnClick = $true; $enabledItem.Checked = [bool]$script:Cfg.Enabled
$sweepItem   = $menu.Items.Add('Sweep now')
$folderItem  = $menu.Items.Add('Open export folder')
$menu.Items.Add('-') | Out-Null
$settingsItem = $menu.Items.Add('Settings...')
$logItem      = $menu.Items.Add('Open log')
$menu.Items.Add('-') | Out-Null
$exitItem = $menu.Items.Add('Exit')
$tray.ContextMenuStrip = $menu

# $tip: what the icon's tooltip says when it should not be the status line (the
# tooltip is 63 characters at most, which the German token texts exceed).
function Set-Status([string]$text, [string]$tip = '') {
    if (-not $tip) { $tip = $text }
    $status.Text = $text
    $tray.Text = "$script:AppName - $tip".Substring(0, [Math]::Min(63, ("$script:AppName - $tip").Length))
}

function Show-Problem([string]$text) {
    # One balloon per ten minutes: a dead network must not become a stream of them.
    if (([datetime]::Now - $script:LastBalloon).TotalMinutes -lt 10) { return }
    $script:LastBalloon = [datetime]::Now
    $tray.ShowBalloonTip(5000, $script:AppName, $text, [Windows.Forms.ToolTipIcon]::Warning)
}

function Invoke-SweepNow {
    try {
        $summary = Invoke-Sweep $script:Cfg
        # A refused token needs a person at Settings: the status line and the
        # tooltip say so in words, and so does a balloon (at most one in ten minutes).
        $tokenProblem = Get-TokenProblemIn $script:SweepProblems
        $tip = ''
        if ($tokenProblem) { $tip = Get-TokenProblemTip $tokenProblem; Show-Problem (Get-TokenProblemText $tokenProblem) }
        Set-Status ($summary + ' @ ' + (Get-Date -Format 'HH:mm')) $tip
    }
    catch { Write-Log "sweep: $($_.Exception.Message)" 'ERROR'; Set-Status 'sweep: failed'; Show-Problem "Upload failed: $($_.Exception.Message)" }
}

$sweepTimer = New-Object Windows.Forms.Timer
function Update-Timers {
    $sweepTimer.Interval = [Math]::Max(5, [int]$script:Cfg.SweepIntervalSec) * 1000
    $sweepTimer.Enabled = [bool]$script:Cfg.Enabled
    $tray.Icon = New-TrayIcon $script:Cfg.Enabled
    if (-not $script:Cfg.Enabled) { Set-Status 'Disabled' }
}
$sweepTimer.Add_Tick({ Invoke-SweepNow })

# DR-033 - the heartbeat runs whether uploading is on or off: "switched off"
# is one of the things the System Status page needs to see.
$hbTimer = New-Object Windows.Forms.Timer
$hbTimer.Interval = [Math]::Max(30, [int]$script:Cfg.HeartbeatIntervalSec) * 1000
$hbTimer.Add_Tick({ Send-Heartbeat $script:Cfg | Out-Null })

$enabledItem.Add_Click({
    $script:Cfg.Enabled = $enabledItem.Checked
    Save-Config $script:Cfg
    Update-Timers
    Write-Log ("enabled={0}" -f $script:Cfg.Enabled)
    if ($script:Cfg.Enabled) { Invoke-SweepNow }
    Send-Heartbeat $script:Cfg | Out-Null
})
$sweepItem.Add_Click({ Invoke-SweepNow })
$folderItem.Add_Click({ if (Test-Path $script:Cfg.WatchFolder) { Start-Process explorer.exe $script:Cfg.WatchFolder } })
$logItem.Add_Click({ if (Test-Path $script:LogPath) { Start-Process notepad.exe $script:LogPath } })
$exitItem.Add_Click({ Send-StopHeartbeat $script:Cfg 'exit'; $tray.Visible = $false; [Windows.Forms.Application]::Exit() })
# Logoff or shutdown: say so, so the page shows an evening, not an outage.
# Raised on this (STA, message-pumping) thread, where the script can run.
[Microsoft.Win32.SystemEvents]::add_SessionEnding({ Send-StopHeartbeat $script:Cfg 'session-end' })

$settingsItem.Add_Click({
    $f = New-Object Windows.Forms.Form
    $f.Text = "$script:AppName - Settings"; $f.StartPosition = 'CenterScreen'; $f.FormBorderStyle = 'FixedDialog'
    $f.MaximizeBox = $false; $f.MinimizeBox = $false; $f.ClientSize = New-Object Drawing.Size 520, 436

    $y = 14
    function Add-Row([string]$label, [Windows.Forms.Control]$ctl) {
        $l = New-Object Windows.Forms.Label; $l.Text = $label; $l.Location = New-Object Drawing.Point 12, ($y + 3); $l.AutoSize = $true
        $ctl.Location = New-Object Drawing.Point 170, $y; $ctl.Width = 330
        $f.Controls.Add($l); $f.Controls.Add($ctl)
        Set-Variable -Scope 1 -Name y -Value ($y + 34)
    }
    $tbUrl    = New-Object Windows.Forms.TextBox; $tbUrl.Text = $script:Cfg.BaseUrl
    $tbFolder = New-Object Windows.Forms.TextBox; $tbFolder.Text = $script:Cfg.WatchFolder
    $tbDcm    = New-Object Windows.Forms.TextBox; $tbDcm.Text = $script:Cfg.DeviceDicom
    $tbE2e    = New-Object Windows.Forms.TextBox; $tbE2e.Text = $script:Cfg.DeviceE2e
    $nuSweep  = New-Object Windows.Forms.NumericUpDown; $nuSweep.Minimum = 5; $nuSweep.Maximum = 3600; $nuSweep.Value = [int]$script:Cfg.SweepIntervalSec
    $tbName   = New-Object Windows.Forms.TextBox; $tbName.Text = $script:Cfg.DisplayName
    $cbDel    = New-Object Windows.Forms.CheckBox; $cbDel.Text = 'Delete uploaded exports from _uploaded'; $cbDel.Checked = [bool]$script:Cfg.DeleteUploaded
    $nuKeep   = New-Object Windows.Forms.NumericUpDown; $nuKeep.Minimum = 1; $nuKeep.Maximum = 3650
    $nuKeep.Value = [Math]::Min(3650, [Math]::Max(1, [int]$script:Cfg.DeleteUploadedAfterDays))
    $cbOn     = New-Object Windows.Forms.CheckBox; $cbOn.Text = 'Enabled'; $cbOn.Checked = [bool]$script:Cfg.Enabled
    # The uploader token is never shown, not even masked - only whether one is
    # saved. Typing a new one replaces it; an empty field keeps it; the tick
    # removes it (uploads then go without a token, as before DR-040).
    $tbUpTok  = New-Object Windows.Forms.TextBox; $tbUpTok.UseSystemPasswordChar = $true
    $cbUpTokClear = $null
    if ([bool]$script:Cfg.UploaderTokenProtected) {
        $cbUpTokClear = New-Object Windows.Forms.CheckBox; $cbUpTokClear.Text = 'Token entfernen (derzeit gesetzt; leer = behalten)'
        $upTokState = $cbUpTokClear
    } else {
        $upTokState = New-Object Windows.Forms.Label; $upTokState.Text = 'derzeit nicht gesetzt (Uploads ohne Token)'; $upTokState.AutoSize = $true
    }

    Add-Row 'Platform base URL' $tbUrl
    Add-Row 'Uploader-Token' $tbUpTok
    Add-Row '' $upTokState
    Add-Row 'Export folder to watch' $tbFolder
    Add-Row 'Device name for .dcm' $tbDcm
    Add-Row 'Device name for .e2e' $tbE2e
    Add-Row 'Sweep every (sec)' $nuSweep
    Add-Row 'Name on the status page' $tbName
    Add-Row 'Uploaded exports' $cbDel
    Add-Row 'Delete them after (days)' $nuKeep
    Add-Row '' $cbOn

    $ok = New-Object Windows.Forms.Button; $ok.Text = 'Save'; $ok.DialogResult = 'OK'; $ok.Location = New-Object Drawing.Point 330, 396
    $cancel = New-Object Windows.Forms.Button; $cancel.Text = 'Cancel'; $cancel.DialogResult = 'Cancel'; $cancel.Location = New-Object Drawing.Point 420, 396
    $f.Controls.AddRange(@($ok, $cancel)); $f.AcceptButton = $ok; $f.CancelButton = $cancel

    # An Uploader-Token that does not look like one is refused here, with the
    # dialog shown again and everything typed still in it - never saved as garbage.
    $answer = 'Cancel'
    while ($true) {
        $answer = $f.ShowDialog()
        if ($answer -ne 'OK') { break }
        $typed = $tbUpTok.Text.Trim()
        if (-not $typed -or (Test-UploaderTokenFormat $typed)) { break }
        [Windows.Forms.MessageBox]::Show("Der Uploader-Token hat nicht das erwartete Format: 'mwu_' gefolgt von 43 Zeichen (Buchstaben, Ziffern, - und _). Bitte genau so einfuegen, wie er angezeigt wurde, ohne Leerzeichen oder Zeilenumbrueche. Es wurde nichts gespeichert.", "$script:AppName - Uploader-Token", 'OK', 'Warning') | Out-Null
    }
    if ($answer -eq 'OK') {
        $script:Cfg.BaseUrl = $tbUrl.Text.Trim()
        $script:Cfg.WatchFolder = $tbFolder.Text.Trim()
        $script:Cfg.DeviceDicom = $tbDcm.Text.Trim()
        $script:Cfg.DeviceE2e = $tbE2e.Text.Trim()
        $script:Cfg.SweepIntervalSec = [int]$nuSweep.Value
        $script:Cfg.DisplayName = $tbName.Text.Trim()
        $script:Cfg.DeleteUploaded = $cbDel.Checked
        $script:Cfg.DeleteUploadedAfterDays = [int]$nuKeep.Value
        $script:Cfg.Enabled = $cbOn.Checked
        $typed = $tbUpTok.Text.Trim()
        if ($typed) { $script:Cfg.UploaderTokenProtected = Protect-Token $typed }
        elseif ($null -ne $cbUpTokClear -and $cbUpTokClear.Checked) { $script:Cfg.UploaderTokenProtected = '' }
        $typed = $null
        Save-Config $script:Cfg
        $enabledItem.Checked = $cbOn.Checked
        Update-Timers
        # a changed retention applies on the next sweep, not in an hour
        $script:LastRetentionUtc = [datetime]::MinValue
        Write-Log ("settings saved (delete uploaded exports: {0}, after {1} day(s); uploader token: {2})" -f $script:Cfg.DeleteUploaded, $script:Cfg.DeleteUploadedAfterDays, (Get-UploaderTokenState $script:Cfg))
        if ($script:Cfg.Enabled) { Invoke-SweepNow }
        Send-Heartbeat $script:Cfg | Out-Null
    }
    $f.Dispose()
})

Update-Timers
if ($script:Cfg.Enabled) { Invoke-SweepNow }
Send-Heartbeat $script:Cfg | Out-Null
$hbTimer.Start()

$ctx = New-Object Windows.Forms.ApplicationContext
try { [Windows.Forms.Application]::Run($ctx) }
finally {
    $hbTimer.Stop()
    # However it ended (the menu, a crash): the page should not wait three
    # intervals to learn it. A no-op when Exit or the session end said so already.
    Send-StopHeartbeat $script:Cfg 'exit'
    $tray.Visible = $false; $tray.Dispose()
    $mutex.ReleaseMutex() | Out-Null
    Write-Log "$script:AppName stopped"
}
