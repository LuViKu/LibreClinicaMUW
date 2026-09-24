<#
.SYNOPSIS
  Export Watcher — tray app that uploads device exports from a watched folder
  to the LibreClinica upload front door: Zeiss Clarus DICOM (.dcm) and
  Heidelberg Spectralis (.e2e).

.DESCRIPTION
  Neither device speaks to the platform. The photographer exports to a folder
  on the acquisition PC — Clarus writes three .dcm per capture, HEYEX one .e2e per
  export — and until now re-uploaded every file through the browser page.
  This script watches that folder instead and carries each new file through
  the same public upload API the page uses (DR-029), so nothing changes
  server-side and the file arrives exactly as if uploaded by hand:

    sweep      every N seconds (default 20): list the watched folder for
               *.dcm and *.e2e. A file counts as finished when its size has
               not changed since the previous sweep AND it can be opened
               without sharing — an export in progress fails one of the two.
               Sub-folders named _uploaded / _failed / _skipped are skipped.

    identify   .dcm: PatientID, StudyDate and (Image)Laterality out of the
               header — the same reader the Optomed bridge uses. A Clarus
               export is three objects per capture: the photograph, a Raw
               Data object (the vendor's sensor data, 8 MB) and a small OT
               Raw Data object stamped with the export time. Only the
               photograph is an image; the other two are set aside in
               _skipped\ (never deleted) unless UploadNonImage is on.
               .e2e: patient id, acquisition date and laterality per OCT
               volume out of the Heidelberg chunk directory — a port of the
               upload page's own reader (web/src/spa/src/lib/e2eParser.ts;
               keep the two in step). A file with several volumes is
               uploaded once per volume, as the page does.

    resolve    ONE POST /resolve for every scan found in the sweep, not one
               per file: the public front door budgets lookups at 30 per
               hour per client, and a Clarus session is easily 40 files.
               Exactly one subject with exactly one visit on that day ->
               bound on arrival; anything else -> the reconciliation inbox,
               with the label and date as hints.

    commit     POST /commit per scan. 201 and 409 (already there, by
               content hash) both move the file into _uploaded\. Any other
               answer leaves it for the next sweep; after 5 attempts it goes
               to _failed\ so a bad file cannot burn the budget forever.

  The convention this relies on is the one every device ingress here uses:
  the patient id the photographer types into the device IS the study subject
  label. Clarus: the DICOM PatientID. Spectralis: the HEYEX patient id —
  or, after HEYEX anonymisation, whatever sits in the surname slot, which is
  where MUW keeps the label (the page reads it the same way).

  Runs at login as a tray icon (see Install-ExportWatcher.ps1). Right-click:
  enable/disable, sweep now, settings, open log, exit. One instance per PC;
  on the Clarus PC it runs beside the Optomed bridge, on the Spectralis PC
  alone — one watcher per export folder, never one watcher for both PCs.

  Settings: %ProgramData%\LibreClinica\export-watcher.json. No secret: the
  public front door takes none. Log alongside, rotated at 1 MB. Only counts
  and pseudonymous labels are ever logged — never names, dates of birth or
  filenames (an export is often named after the patient).

  What stays on the PC: the exports themselves, moved aside but never
  deleted, and for Clarus those carry the patient's name in the header until
  the platform pseudonymises its copy on ingest. Retention there is a local
  decision, as for the Optomed Client's Studies\ folder.

.PARAMETER Once
  Headless: run one sweep against the configured folder and exit with a
  summary. For scheduled-task deployments, for the installer's smoke run,
  and for testing the whole chain without a desktop session.

.PARAMETER SelfTest
  Headless: read -File and print what this script would send for it (label,
  date, laterality, volumes) — nothing is uploaded. Lets the readers be
  checked on a real export before the watcher is switched on.

.PARAMETER Heartbeat
  Headless: send one heartbeat to the platform and print the answer — the
  quickest check that this PC reaches the platform and shows up on the
  System Status page. Nothing is uploaded.

  The tray app sends one every HeartbeatIntervalSec (default 120), switched
  on or not (DR-033): whether it runs, whether uploading is on, how many
  export files wait and for how long, how many went to _failed\, how full
  the export drive is, and coded problems from the last sweep. Counts, ages
  and codes only — no label, no filename, nothing about a patient. It says
  "stopped" when closed from its menu or when Windows ends the session, so
  the page can tell a PC switched off in the evening from a crash.

.NOTES
  Windows PowerShell 5.1 — no 6+ features (no ternary, no ??, no -Form).
  The headless modes (-Once, -SelfTest) also run on PowerShell 7 on Linux,
  which is how the chain is tested from a Mac against the dev stack.
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
$script:Version     = '2026-09-24'
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
# path -> size seen at the previous sweep; a file is settled when it matches.
$script:SizeSeen    = @{}
# path -> failed attempts this session.
$script:Attempts    = @{}

# ----------------------------------------------------------------------------
# logging — counts and labels only
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
# settings — a JSON file, no secret in it
# ----------------------------------------------------------------------------
function Get-DefaultConfig {
    [pscustomobject]@{
        BaseUrl        = 'https://ecrf.augen.meduniwien.ac.at/LibreClinica'
        WatchFolder    = (Join-Path $(if ($env:USERPROFILE) { $env:USERPROFILE } else { $HOME }) 'Documents\LibreClinica-Export')
        SweepIntervalSec = 20
        DeviceDicom    = 'clarus'      # the device key on a .dcm upload (imaging catalogue: FUNDUS_CLARUS)
        DeviceE2e      = 'spectralis'  # on an .e2e upload (OCT_SPECTRALIS and the Spectralis image rows)
        UploadNonImage = $false       # also upload Raw Data / report objects (see 'identify' above)
        Enabled        = $false
        # DR-033 — the System Status page. InstanceId is generated on first
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

# ----------------------------------------------------------------------------
# HTTP — one client; long timeout because an .e2e is tens of megabytes
# ----------------------------------------------------------------------------
Add-Type -AssemblyName System.Net.Http
$script:Http = New-Object System.Net.Http.HttpClient
$script:Http.Timeout = [TimeSpan]::FromMinutes(15)

function Get-ApiUrl([pscustomobject]$cfg, [string]$path) {
    $cfg.BaseUrl.TrimEnd('/') + '/pages' + $path
}

# ----------------------------------------------------------------------------
# health — what the heartbeat reports (DR-033). Counts, ages, disk figures and
# coded problems; never a label, a filename or anything about a patient.
# ----------------------------------------------------------------------------
# Its own client with a short timeout: a heartbeat that hangs must not hold
# the tray for the fifteen minutes an .e2e upload is allowed.
$script:HbHttp = New-Object System.Net.Http.HttpClient
$script:HbHttp.Timeout = [TimeSpan]::FromSeconds(10)
$script:HbLastCode = -1           # the last heartbeat's HTTP status; a change is logged, a repeat is not
$script:StopSent = $false
$script:LastActivityUtc = $null   # end of the last sweep
$script:LastUploadUtc = $null     # the last 201
$script:UploadedDay = (Get-Date).Date
$script:UploadedToday = 0
# code -> $true, rebuilt by every sweep: the problems of the last sweep that ran
$script:SweepProblems = @{}

function Add-Problem([string]$code) { $script:SweepProblems[$code] = $true }

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
    try {
        $json = Get-HeartbeatBody $cfg $running $stopReason | ConvertTo-Json -Depth 4 -Compress
        $content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, 'application/json')
        $resp = $script:HbHttp.PostAsync((Get-ApiUrl $cfg '/api/v1/device/uploader/heartbeat'), $content).GetAwaiter().GetResult()
        $code = [int]$resp.StatusCode
    } catch { $code = 0 }
    if ($code -ne $script:HbLastCode) {
        $script:HbLastCode = $code
        switch ($code) {
            200     { Write-Log 'heartbeat: reporting to the platform' }
            0       { Write-Log 'heartbeat: the platform does not answer' 'WARN' }
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
# DICOM header reader — only what this script needs (as in OptomedBridge.ps1)
# ----------------------------------------------------------------------------
$script:LongVRs = @('OB','OW','OF','SQ','UT','UN')

# Length of the element whose 4-byte tag has just been read. 0xFFFFFFFF means
# undefined (a sequence, or encapsulated pixel data) — compared below as
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
# group 0008 — BEFORE the patient group — so a reader that stops at the first
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
# Spectralis .e2e reader — a port of web/src/spa/src/lib/e2eParser.ts
#
# Chunk directory walk, then per chunk: type 9 (patient id: canonical slot,
# else the surname slot where MUW keeps the label after anonymisation, else
# first name), 10004 (B-scan metadata: acquisition time as Windows FILETIME,
# one volume per patient_db_id/study_id/series_id), 10 (session date as an
# OLE Automation date — the fallback for fundus-only exports), 3 and 11
# (laterality). Offsets are the ones in the TypeScript file; change both.
# ----------------------------------------------------------------------------
function Read-E2eScans {
    param([string]$Path)
    $b = [IO.File]::ReadAllBytes($Path)
    $skip = 0
    $mv = 'E2EMultipleVolumeFile'
    if ($b.Length -ge $mv.Length -and [Text.Encoding]::GetEncoding(28591).GetString($b, 0, $mv.Length) -eq $mv) { $skip = 64 }
    if ($b.Length -lt $skip + 36 + 52) { throw 'too short for an .e2e header' }
    $magic0 = $b[$skip]
    if ($magic0 -lt 0x20 -or $magic0 -ge 0x7f) { throw 'not a Spectralis .e2e file (header magic)' }

    # directory linked list: first main directory sits right after the 36-byte file header
    $dirs = New-Object Collections.ArrayList
    $first = $skip + 36
    $current = [BitConverter]::ToUInt32($b, $first + 40)
    $guard = 0
    while ($current -ne 0 -and $guard -lt 100000) {
        $abs = [long]$current + $skip
        if ($abs + 52 -gt $b.Length) { break }
        [void]$dirs.Add($abs)
        $prev = [BitConverter]::ToUInt32($b, [int]$abs + 44)
        if ($prev -eq $current) { break }
        $current = $prev; $guard++
    }

    # sub-directory entries -> chunk starts
    $starts = New-Object Collections.ArrayList
    foreach ($d in $dirs) {
        $n = [BitConverter]::ToUInt32($b, [int]$d + 36)
        $e = [int]$d + 52
        for ($i = 0; $i -lt $n; $i++) {
            if ($e + 44 -gt $b.Length) { break }
            $pos = [BitConverter]::ToUInt32($b, $e); $start = [BitConverter]::ToUInt32($b, $e + 4)
            if ($start -gt $pos -and $start -gt 0) { [void]$starts.Add($start) }
            $e += 44
        }
    }

    $latin1 = [Text.Encoding]::GetEncoding(28591)
    function Get-Str([byte[]]$buf, [int]$off, [int]$len) {
        $end = $off + $len; while ($end -gt $off -and $buf[$end - 1] -eq 0) { $end-- }
        $latin1.GetString($buf, $off, $end - $off).Trim()
    }
    $volumes = [ordered]@{}
    $patientIds = @{}
    $fileDate = $null
    foreach ($s in $starts) {
        $c = [long]$s + $skip
        if ($c + 60 -gt $b.Length) { continue }
        $c = [int]$c
        $pdb = [BitConverter]::ToUInt32($b, $c + 32); $sid = [BitConverter]::ToUInt32($b, $c + 36); $ser = [BitConverter]::ToUInt32($b, $c + 40)
        $type = [BitConverter]::ToUInt32($b, $c + 52)
        $p = $c + 60
        $key = "$pdb`_$sid`_$ser"
        switch ($type) {
            9 {
                if ($p + 102 + 25 -gt $b.Length) { break }
                $id = Get-Str $b ($p + 102) 25
                if ($id.Length -eq 0) { $id = Get-Str $b ($p + 31) 51 }
                if ($id.Length -eq 0) { $id = Get-Str $b $p 31 }
                if ($id.Length -gt 0) { $patientIds[[string]$pdb] = $id }
            }
            10 {
                if ($null -ne $fileDate -or $p + 6 + 8 -gt $b.Length) { break }
                $days = [BitConverter]::ToDouble($b, $p + 6)
                if ([double]::IsNaN($days) -or $days -lt 30000 -or $days -gt 80000) { break }
                $fileDate = [DateTime]::FromOADate($days)
            }
            3 {
                if ($p + 5 -gt $b.Length) { break }
                $lat = ConvertTo-Laterality ([string][char]$b[$p + 4])
                if ($lat) {
                    if (-not $volumes.Contains($key)) { $volumes[$key] = @{ Laterality = $null; Ticks = 0; NumImages = 0; PatientDb = [string]$pdb } }
                    $volumes[$key].Laterality = $lat
                }
            }
            11 {
                if ($p + 15 -gt $b.Length) { break }
                $lat = ConvertTo-Laterality ([string][char]$b[$p + 14])
                if ($lat) {
                    if (-not $volumes.Contains($key)) { $volumes[$key] = @{ Laterality = $null; Ticks = 0; NumImages = 0; PatientDb = [string]$pdb } }
                    if ($null -eq $volumes[$key].Laterality) { $volumes[$key].Laterality = $lat }
                }
            }
            10004 {
                if ($p + 88 + 8 -gt $b.Length) { break }
                if (-not $volumes.Contains($key)) { $volumes[$key] = @{ Laterality = $null; Ticks = 0; NumImages = 0; PatientDb = [string]$pdb } }
                $n = [BitConverter]::ToUInt32($b, $p + 64)
                if ($n -gt $volumes[$key].NumImages) { $volumes[$key].NumImages = $n }
                $ticks = [BitConverter]::ToUInt64($b, $p + 88)
                if ($volumes[$key].Ticks -eq 0 -and $ticks -gt 0) { $volumes[$key].Ticks = $ticks }
            }
        }
    }

    $out = New-Object Collections.ArrayList
    $idx = 0
    foreach ($k in $volumes.Keys) {
        $v = $volumes[$k]
        # ($pid is PowerShell's read-only process id; a label is what this is.)
        $label = ''
        if ($patientIds.ContainsKey($v.PatientDb)) { $label = $patientIds[$v.PatientDb] }
        $date = $null
        if ($v.Ticks -gt 0 -and $v.Ticks -lt 9223372036854775807) {
            try { $date = [DateTime]::FromFileTimeUtc([long]$v.Ticks).ToLocalTime() } catch { $date = $null }
        }
        if ($null -eq $date -and $null -ne $fileDate) { $date = $fileDate }
        $lat = 'OD'; if ($v.Laterality) { $lat = $v.Laterality }
        [void]$out.Add([pscustomobject]@{
            PatientId  = $label
            ScanDate   = $(if ($date) { $date.ToString('yyyy-MM-dd') } else { $null })
            Laterality = $lat
            ScanIndex  = $idx
            NumImages  = [int]$v.NumImages
        })
        $idx++
    }
    ,$out.ToArray()
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
    $scans = Read-E2eScans $f.FullName
    if ($scans.Count -eq 0) {
        # a file with no volume chunk at all: send it once, undated, so the inbox gets it
        return ,@([pscustomobject]@{ PatientId = ''; ScanDate = $null; Laterality = $null; ScanIndex = 0; Device = $cfg.DeviceE2e; Kind = 'e2e'; NonImage = $false })
    }
    ,@($scans | ForEach-Object { [pscustomobject]@{ PatientId = $_.PatientId; ScanDate = $_.ScanDate; Laterality = $_.Laterality; ScanIndex = $_.ScanIndex; Device = $cfg.DeviceE2e; Kind = 'e2e'; NonImage = $false } })
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
    $resp = $script:Http.PostAsync((Get-ApiUrl $cfg '/api/v1/public/upload/resolve'), $content).GetAwaiter().GetResult()
    if (-not $resp.IsSuccessStatusCode) {
        if ([int]$resp.StatusCode -eq 429) { Add-Problem 'rate-limited' }
        Write-Log "resolve: HTTP $([int]$resp.StatusCode) — filing without a visit this sweep" 'WARN'
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
    $mp = New-Object System.Net.Http.MultipartFormDataContent
    $fc = New-Object System.Net.Http.ByteArrayContent(,[IO.File]::ReadAllBytes($f.FullName))
    if ($scan.Kind -eq 'dicom') {
        $fc.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/dicom')
        $mp.Add($fc, 'file', 'image.dcm')     # neutral names: the real one may carry the patient's
    } else {
        $fc.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/octet-stream')
        $mp.Add($fc, 'file', 'scan.e2e')      # the sniffer's .e2e concession keys on this extension
    }
    $fields = @{ patientId = $scan.PatientId; scanDate = $scan.ScanDate; laterality = $scan.Laterality
                 scanIndex = $scan.ScanIndex; device = $scan.Device; studyEventId = $studyEventId }
    # An .e2e must name its visit or be parked: the OCT route refuses a
    # scan with neither (400), because a scan on a visit starts inference and
    # one without must not. Parked = the reconciliation inbox, no job until
    # somebody binds it — the same place an unresolved photo lands.
    if ($scan.Kind -eq 'e2e' -and -not $studyEventId) { $fields['park'] = 'true' }
    foreach ($k in $fields.Keys) {
        if ($null -ne $fields[$k] -and "$($fields[$k])" -ne '') { $mp.Add((New-Object System.Net.Http.StringContent("$($fields[$k])")), $k) }
    }
    $resp = $script:Http.PostAsync((Get-ApiUrl $cfg '/api/v1/public/upload/commit'), $mp).GetAwaiter().GetResult()
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
    Move-Item -Force $f.FullName (Join-Path $dest $f.Name)
    $script:SizeSeen.Remove($f.FullName); $script:Attempts.Remove($f.FullName)
}

# The problems the heartbeat reports are those of the last sweep: rebuilt on
# every sweep, so one that went away stops being reported by itself.
function Invoke-Sweep([pscustomobject]$cfg) {
    $script:SweepProblems = @{}
    try { Invoke-SweepCore $cfg }
    catch {
        # Whatever the sweep did not handle itself was the network or the platform.
        Add-Problem 'server-unreachable'
        throw
    }
    finally { $script:LastActivityUtc = [datetime]::UtcNow }
}

function Invoke-SweepCore([pscustomobject]$cfg) {
    $root = $cfg.WatchFolder
    if (-not (Test-Path $root)) {
        Add-Problem 'watch-folder-missing'
        Write-Log "sweep: watch folder missing: $root" 'ERROR'
        return 'watch folder missing'
    }
    $files = @((Get-WatchedFiles $root).Pending)
    if ($files.Count -eq 0) { return 'nothing to upload' }
    $ready = @($files | Where-Object { Test-Settled $_ })
    if ($ready.Count -eq 0) { return ('{0} file(s) still being written' -f $files.Count) }

    # identify everything first, so one /resolve covers the sweep
    $work = New-Object Collections.ArrayList   # @{ File; Scan }
    $unreadable = 0; $setAside = 0
    foreach ($f in $ready) {
        try {
            $scans = @(Get-FileScans $cfg $f)
            if (-not $cfg.UploadNonImage -and @($scans | Where-Object { -not $_.NonImage }).Count -eq 0) {
                # a Raw Data object or a report, not a picture: not for the visit
                $setAside++; Move-Aside $f $script:SkippedDir; continue
            }
            foreach ($s in $scans) { [void]$work.Add(@{ File = $f; Scan = $s }) }
        } catch {
            $unreadable++
            Add-Problem 'unreadable-files'
            $n = 1 + [int]$script:Attempts[$f.FullName]; $script:Attempts[$f.FullName] = $n
            Write-Log ("identify: unreadable {0} file (attempt {1}): {2}" -f $f.Extension, $n, $_.Exception.Message) 'WARN'
            if ($n -ge $script:MaxAttempts) { Move-Aside $f $script:FailedDir }
        }
    }
    if ($work.Count -eq 0) {
        $summary = 'nothing to upload ({0} unreadable, {1} non-image set aside)' -f $unreadable, $setAside
        if ($setAside -gt 0) { Write-Log "sweep: $summary" }
        return $summary
    }

    $scans = @($work | ForEach-Object { $_.Scan })
    $events = Resolve-Batch $cfg $scans

    $ok = 0; $bound = 0; $dup = 0; $fail = 0
    $filesDone = @{}
    for ($i = 0; $i -lt $work.Count; $i++) {
        $f = $work[$i].File; $s = $work[$i].Scan; $ev = $events[$i]
        if ($filesDone.ContainsKey($f.FullName) -and $filesDone[$f.FullName] -eq 'failed') { continue }
        try {
            $r = Send-Commit $cfg $f $s $ev
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
        $n = 1 + [int]$script:Attempts[$path]; $script:Attempts[$path] = $n
        if ($n -ge $script:MaxAttempts) { Write-Log ("upload: giving up on a {0} file after {1} attempts — moved to {2}" -f $f.Extension, $n, $script:FailedDir) 'ERROR'; Move-Aside $f $script:FailedDir }
    }
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
    $f = Get-Item $File
    Write-Host ("{0} ({1:N0} bytes)" -f $f.Extension, $f.Length)
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
    Write-Host ("heartbeat as '{0}' ({1} {2}) to {3}: {4}" -f (Get-DisplayName $cfg), $script:Kind, $script:Version, $cfg.BaseUrl, $answer)
    if ($code -eq 200) { exit 0 } else { exit 1 }
}

if ($Once) {
    $cfg = Read-Config
    Write-Log "$script:AppName one sweep of $($cfg.WatchFolder) against $($cfg.BaseUrl)"
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

$script:Cfg = Read-Config
Write-Log "$script:AppName starting (enabled=$($script:Cfg.Enabled), folder=$($script:Cfg.WatchFolder))"

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

function Set-Status([string]$text) { $status.Text = $text; $tray.Text = "$script:AppName - $text".Substring(0, [Math]::Min(63, ("$script:AppName - $text").Length)) }

function Show-Problem([string]$text) {
    # One balloon per ten minutes: a dead network must not become a stream of them.
    if (([datetime]::Now - $script:LastBalloon).TotalMinutes -lt 10) { return }
    $script:LastBalloon = [datetime]::Now
    $tray.ShowBalloonTip(5000, $script:AppName, $text, [Windows.Forms.ToolTipIcon]::Warning)
}

function Invoke-SweepNow {
    try { Set-Status ((Invoke-Sweep $script:Cfg) + ' @ ' + (Get-Date -Format 'HH:mm')) }
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

# DR-033 — the heartbeat runs whether uploading is on or off: "switched off"
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
    $f.MaximizeBox = $false; $f.MinimizeBox = $false; $f.ClientSize = New-Object Drawing.Size 520, 300

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
    $cbOn     = New-Object Windows.Forms.CheckBox; $cbOn.Text = 'Enabled'; $cbOn.Checked = [bool]$script:Cfg.Enabled

    Add-Row 'Platform base URL' $tbUrl
    Add-Row 'Export folder to watch' $tbFolder
    Add-Row 'Device name for .dcm' $tbDcm
    Add-Row 'Device name for .e2e' $tbE2e
    Add-Row 'Sweep every (sec)' $nuSweep
    Add-Row 'Name on the status page' $tbName
    Add-Row '' $cbOn

    $ok = New-Object Windows.Forms.Button; $ok.Text = 'Save'; $ok.DialogResult = 'OK'; $ok.Location = New-Object Drawing.Point 330, 260
    $cancel = New-Object Windows.Forms.Button; $cancel.Text = 'Cancel'; $cancel.DialogResult = 'Cancel'; $cancel.Location = New-Object Drawing.Point 420, 260
    $f.Controls.AddRange(@($ok, $cancel)); $f.AcceptButton = $ok; $f.CancelButton = $cancel

    if ($f.ShowDialog() -eq 'OK') {
        $script:Cfg.BaseUrl = $tbUrl.Text.Trim()
        $script:Cfg.WatchFolder = $tbFolder.Text.Trim()
        $script:Cfg.DeviceDicom = $tbDcm.Text.Trim()
        $script:Cfg.DeviceE2e = $tbE2e.Text.Trim()
        $script:Cfg.SweepIntervalSec = [int]$nuSweep.Value
        $script:Cfg.DisplayName = $tbName.Text.Trim()
        $script:Cfg.Enabled = $cbOn.Checked
        Save-Config $script:Cfg
        $enabledItem.Checked = $cbOn.Checked
        Update-Timers
        Write-Log 'settings saved'
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
