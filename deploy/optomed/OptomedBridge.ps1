<#
.SYNOPSIS
  Optomed Bridge — tray app that carries the LibreClinica worklist to the
  Optomed Client and the Client's pulled studies back to LibreClinica.

.DESCRIPTION
  The Optomed Lumo cannot join the institution's WPA2-Enterprise WLAN and no
  other WLAN may be run, so the camera never reaches the DICOM receiver. It
  does reach this PC over USB through the vendor's Optomed Client. This script
  closes the loop from the PC side:

    worklist   every N seconds (default 30): GET /api/v1/device/optomed/worklist.txt
               from the platform and drop it into the Client's watched folder
               as worklist_optomed_lumo.txt. The Client imports it, REPLACES
               its whole list, pushes it to the camera on the next dock, and
               deletes the file. Seconds, not minutes, because the photographer
               enrols a subject and walks to the dock expecting it on the
               camera: the poll has to beat the walk. It is cheap to poll this
               often - one scoped single-day query server-side, and the file
               is only dropped when its content changed, so polling never
               causes a needless camera re-import.

    upload     every M minutes: for each Studies\*\DICOM\*.dcm the Client
               pulled off the camera, read PatientID + StudyDate + Laterality
               out of the header, ask /resolve which visit that is, and POST
               the file to the public upload front door. Exactly one visit that
               day -> bound; anything else -> reconciliation inbox with the
               label as a hint. 201 and 409 (already there) both move the file
               into DICOM\_uploaded\.

  Runs at login as a tray icon (see Install-OptomedBridge.ps1). Right-click:
  enable/disable, fetch or upload now, show/hide the Optomed Client, settings,
  open log, exit.

  It also keeps the Optomed Client itself running and out of the way: starts
  it if it is not running (the whole workflow dies quietly without it), and
  minimises its window and removes its taskbar button so this icon is the
  only one - the Client has no minimise-to-tray of its own. Minimised, never
  hidden: hiding that window from outside blanks its WebView2 for good. Show
  it from the menu when a dialog of its own needs answering; it is shown
  again when the bridge exits.

  Settings: %ProgramData%\LibreClinica\optomed-bridge.json. The token is
  DPAPI-protected to the user who saved it. Log alongside, rotated at 1 MB.
  Only counts and pseudonymous labels are ever logged — never names, dates of
  birth or filenames (the Client names folders after the patient).

.PARAMETER SelfTest
  Headless check and exit: parse -DicomFile and print the tags this script
  reads, or with no file, round-trip the config store. Lets the hard part be
  verified on a real export without a tray session.

.NOTES
  Windows PowerShell 5.1 — no 6+ features (no -Form, no ?? etc.).
#>
[CmdletBinding()]
param(
    [switch]$SelfTest,
    [string]$DicomFile,
    [string]$ConfigPath = (Join-Path $env:ProgramData 'LibreClinica\optomed-bridge.json')
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$script:AppName      = 'Optomed Bridge'
$script:WorklistName = 'worklist_optomed_lumo.txt'   # the one filename the Client imports
$script:StateDir     = Split-Path -Parent $ConfigPath
$script:LogPath      = Join-Path $script:StateDir 'optomed-bridge.log'
$script:LastListPath = Join-Path $script:StateDir 'last-worklist.txt'
$script:UploadedDir  = '_uploaded'
$script:LastBalloon  = [datetime]::MinValue
$script:LastStaleWarned = [datetime]::MinValue   # mtime of the last drop file reported as not imported

# ----------------------------------------------------------------------------
# logging
# ----------------------------------------------------------------------------
function Write-Log {
    param([string]$Message, [ValidateSet('INFO','WARN','ERROR')][string]$Level = 'INFO')
    try {
        if (-not (Test-Path $script:StateDir)) { New-Item -ItemType Directory -Force $script:StateDir | Out-Null }
        if ((Test-Path $script:LogPath) -and (Get-Item $script:LogPath).Length -gt 1MB) {
            Move-Item -Force $script:LogPath ($script:LogPath + '.1')
        }
        $line = '{0} {1,-5} {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Message
        Add-Content -Path $script:LogPath -Value $line -Encoding UTF8
    } catch { }
    if ($SelfTest) { Write-Host $Message }
}

# ----------------------------------------------------------------------------
# settings — a JSON file; the token DPAPI-protected to the saving user
# ----------------------------------------------------------------------------
function Get-DefaultConfig {
    [pscustomobject]@{
        BaseUrl             = 'https://ecrf.augen.meduniwien.ac.at/LibreClinica'
        TokenProtected      = ''
        ClientRoot          = (Join-Path $env:USERPROFILE 'Documents\OptomedClient\Optomed Lumo')
        WorklistIntervalSec = 30
        UploadIntervalMin   = 5
        Device              = 'optomed-lumo'
        Enabled             = $false
        # The Optomed Client has no minimise-to-tray of its own (WinForms
        # hosting a WebView2; nothing tray-related in its install, nothing
        # in its settings). The bridge minimises the Client's window and
        # removes its taskbar button instead, and offers Show/Hide in its
        # menu, so this icon is the only one. The process, not the window,
        # does the USB and folder watching, so this changes nothing about
        # the sync - verified on the real Client.
        HideClientWindow    = $true
        ClientExe           = (Join-Path $env:LOCALAPPDATA 'Optomed\OptomedClient\OptomedClient.exe')
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
            # The first cut polled the worklist in minutes. It is seconds now,
            # because the photographer enrols a subject and walks to the dock
            # expecting it on the camera; carry an old setting across.
            if ($saved.PSObject.Properties['WorklistIntervalMin'] -and -not $saved.PSObject.Properties['WorklistIntervalSec']) {
                $cfg.WorklistIntervalSec = [Math]::Max(10, [int]$saved.WorklistIntervalMin * 60)
            }
        } catch { Write-Log "config unreadable, using defaults: $($_.Exception.Message)" 'WARN' }
    }
    $cfg
}

function Save-Config([pscustomobject]$cfg) {
    if (-not (Test-Path $script:StateDir)) { New-Item -ItemType Directory -Force $script:StateDir | Out-Null }
    $cfg | ConvertTo-Json | Set-Content -Path $ConfigPath -Encoding UTF8
}

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
# HTTP — one client, one token header
# ----------------------------------------------------------------------------
Add-Type -AssemblyName System.Net.Http
$script:Http = New-Object System.Net.Http.HttpClient
$script:Http.Timeout = [TimeSpan]::FromSeconds(120)

function Get-ApiUrl([pscustomobject]$cfg, [string]$path) {
    ($cfg.BaseUrl.TrimEnd('/')) + '/pages' + $path
}

# ----------------------------------------------------------------------------
# DICOM header reader — only what this script needs
#
# The Lumo's export is a Part-10 file, explicit VR little-endian for the
# dataset (transfer syntax JPEG Baseline). The tags wanted all sit below group
# 0x0020, before any sequence, so a linear walk that stops there is enough.
# Implicit VR LE is handled too, because it costs a few lines and a vendor
# firmware update is the kind of thing that changes it.
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
    $fs = [IO.File]::OpenRead($Path)
    try {
        $r = New-Object IO.BinaryReader($fs)
        $r.ReadBytes(128) | Out-Null
        if ([Text.Encoding]::ASCII.GetString($r.ReadBytes(4)) -ne 'DICM') { throw 'not a Part-10 DICOM file' }

        $want = @{ '00080018'='SOPInstanceUID'; '00080020'='StudyDate'; '00100020'='PatientID'; '00200060'='Laterality'; '00200062'='ImageLaterality' }
        # Every wanted tag pre-set to '' so a header that lacks one reads as
        # empty rather than throwing under strict mode.
        $out  = @{}
        foreach ($name in $want.Values) { $out[$name] = '' }
        $transfer = ''
        $explicit = $true   # no syntax given: assume explicit
        while ($fs.Position -lt $fs.Length) {
            $group = $r.ReadUInt16(); $elem = $r.ReadUInt16()
            $key = ('{0:x4}{1:x4}' -f $group, $elem)

            # Group 0002 (file meta) is always explicit VR LE; the dataset's
            # syntax is whatever 0002,0010 said.
            $isMeta = ($group -eq 2)
            if (-not $isMeta -and $group -gt 0x0020) { break }   # everything wanted has been passed

            $len = Read-ElemLength $r ($isMeta -or $explicit)
            # An undefined-length sequence: nothing wanted lies inside one,
            # but the patient group lies beyond it — step over, never stop.
            if ($len -eq [uint32]::MaxValue) { Skip-Sequence $r $explicit; continue }
            $bytes = $r.ReadBytes([int]$len)

            if ($key -eq '00020010') {
                $transfer = ([Text.Encoding]::ASCII.GetString($bytes)).Trim([char]0, ' ')
                $explicit = ($transfer -ne '1.2.840.10008.1.2')
            } elseif ($want.ContainsKey($key)) {
                $out[$want[$key]] = ([Text.Encoding]::ASCII.GetString($bytes)).Trim([char]0, ' ')
            }
        }
        $out['TransferSyntax'] = $transfer
        [pscustomobject]$out
    } finally { $fs.Dispose() }
}

function ConvertTo-Laterality([string]$dicom) {
    switch (($dicom + '').Trim().ToUpperInvariant()) {
        'R'  { 'OD' } 'OD' { 'OD' }
        'L'  { 'OS' } 'OS' { 'OS' }
        'B'  { 'OU' } 'OU' { 'OU' }
        default { $null }
    }
}

function ConvertTo-IsoDate([string]$da) {
    if ($da -match '^(\d{4})(\d{2})(\d{2})') { '{0}-{1}-{2}' -f $Matches[1], $Matches[2], $Matches[3] } else { $null }
}

# ----------------------------------------------------------------------------
# worklist: platform -> Client drop folder
# ----------------------------------------------------------------------------
function Invoke-WorklistFetch([pscustomobject]$cfg) {
    $token = Unprotect-Token $cfg.TokenProtected
    if (-not $token) { Write-Log 'worklist: no token configured' 'WARN'; return 'no token' }

    $dropDir = Join-Path $cfg.ClientRoot 'Worklist'
    if (-not (Test-Path $dropDir)) { Write-Log "worklist: drop folder missing: $dropDir" 'ERROR'; return 'drop folder missing' }

    # A file still sitting in the drop folder means the Client REFUSED the
    # previous one - it says nothing, it just leaves the file - and the camera
    # has not had a list since. Checked before the fetch, because a refused
    # file is exactly the case where the server content has NOT changed and
    # the unchanged-return below would otherwise skip everything. The known
    # cause is one PatientID twice in a file (the platform now merges those).
    # Said once per file.
    $stale = Join-Path $dropDir $script:WorklistName
    if ((Test-Path $stale) -and ((Get-Date) - (Get-Item $stale).LastWriteTime).TotalSeconds -gt 60) {
        $mt = (Get-Item $stale).LastWriteTime
        if ($script:LastStaleWarned -ne $mt) {
            $script:LastStaleWarned = $mt
            Write-Log "worklist: the Client did not import the file dropped at $($mt.ToString('HH:mm:ss')) - it refuses a file that names one patient id twice; check the visits scheduled today" 'WARN'
        }
    }

    $req = New-Object System.Net.Http.HttpRequestMessage 'GET', (Get-ApiUrl $cfg '/api/v1/device/optomed/worklist.txt')
    $req.Headers.TryAddWithoutValidation('X-MUW-Optomed-Token', $token) | Out-Null
    $resp = $script:Http.SendAsync($req).GetAwaiter().GetResult()
    $body = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    if (-not $resp.IsSuccessStatusCode) {
        Write-Log "worklist: HTTP $([int]$resp.StatusCode)" 'ERROR'
        return "HTTP $([int]$resp.StatusCode)"
    }

    # Drop only on change. The Client consumes the file, so the reference copy
    # of what was last served lives here, not in the drop folder.
    $sha = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($body)) -replace '-', ''
    $prev = if (Test-Path $script:LastListPath) {
        [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([IO.File]::ReadAllBytes($script:LastListPath))) -replace '-', ''
    } else { '' }
    $records = if ($body.Length -eq 0) { 0 } else { ([Text.Encoding]::ASCII.GetString($body) -split "`r`n`r`n").Where({ $_.Trim() }).Count }
    if ($sha -eq $prev) { return "unchanged ($records)" }

    # Atomic drop: write beside, then rename onto the watched name, so the
    # Client never imports a half-written file.
    $tmp = Join-Path $dropDir ('.' + $script:WorklistName + '.part')
    [IO.File]::WriteAllBytes($tmp, $body)
    Move-Item -Force $tmp (Join-Path $dropDir $script:WorklistName)
    [IO.File]::WriteAllBytes($script:LastListPath, $body)
    Write-Log "worklist: dropped $records record(s)"
    "dropped $records"
}

# ----------------------------------------------------------------------------
# upload: Client studies -> platform
# ----------------------------------------------------------------------------
function Resolve-Visit([pscustomobject]$cfg, [string]$patientId, [string]$scanDate, [string]$laterality) {
    $payload = @{ scans = @(@{ patientId = $patientId; scanDate = $scanDate; laterality = $laterality }) } | ConvertTo-Json -Depth 4 -Compress
    $content = New-Object System.Net.Http.StringContent($payload, [Text.Encoding]::UTF8, 'application/json')
    $resp = $script:Http.PostAsync((Get-ApiUrl $cfg '/api/v1/public/upload/resolve'), $content).GetAwaiter().GetResult()
    if (-not $resp.IsSuccessStatusCode) { return $null }
    $r = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    $scan = $r.scans[0]
    # 'suggested' = one subject with exactly one visit on that date — the only
    # state in which binding without a person is defensible.
    if ($scan.state -eq 'suggested' -and $scan.candidates.Count -eq 1 -and $scan.candidates[0].matchingEvent) {
        return $scan.candidates[0].matchingEvent.studyEventId
    }
    $null
}

function Send-Commit([pscustomobject]$cfg, [string]$path, [hashtable]$fields) {
    $mp = New-Object System.Net.Http.MultipartFormDataContent
    $fileBytes = [IO.File]::ReadAllBytes($path)
    $fc = New-Object System.Net.Http.ByteArrayContent(,$fileBytes)
    $fc.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/dicom')
    $mp.Add($fc, 'file', 'image.dcm')     # a neutral name: the real one carries the patient's
    foreach ($k in $fields.Keys) {
        if ($null -ne $fields[$k] -and "$($fields[$k])" -ne '') { $mp.Add((New-Object System.Net.Http.StringContent("$($fields[$k])")), $k) }
    }
    $resp = $script:Http.PostAsync((Get-ApiUrl $cfg '/api/v1/public/upload/commit'), $mp).GetAwaiter().GetResult()
    [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult() }
}

function Invoke-StudyUpload([pscustomobject]$cfg) {
    $studies = Join-Path $cfg.ClientRoot 'Studies'
    if (-not (Test-Path $studies)) { return 'no Studies folder' }
    $files = Get-ChildItem -Path $studies -Recurse -Filter *.dcm -File |
             Where-Object { $_.Directory.Name -eq 'DICOM' }   # not the ones already in DICOM\_uploaded
    if (-not $files) { return 'nothing to upload' }

    $ok = 0; $dup = 0; $bound = 0; $fail = 0
    foreach ($f in $files) {
        try {
            $tags  = Read-DicomTags $f.FullName
            # ($pid is PowerShell's read-only process id; a label is what this is.)
            $label = $tags.PatientID
            $date  = ConvertTo-IsoDate $tags.StudyDate
            # Laterality (0020,0060) is the study's; ImageLaterality (0020,0062)
            # the image's. The Lumo sets both; prefer the image's if present.
            $lat = if ($tags.ImageLaterality) { ConvertTo-Laterality $tags.ImageLaterality }
                   else                       { ConvertTo-Laterality $tags.Laterality }
            $eventId = if ($label -and $date) { Resolve-Visit $cfg $label $date $lat } else { $null }

            $r = Send-Commit $cfg $f.FullName @{
                patientId = $label; scanDate = $date; laterality = $lat
                device = $cfg.Device; studyEventId = $eventId
            }
            switch ($r.Status) {
                201 { $ok++; if ($eventId) { $bound++ }; Move-Uploaded $f }
                409 { $dup++; Move-Uploaded $f }
                default {
                    $fail++
                    $why = ($r.Body -replace '\s+', ' ')
                    Write-Log ("upload: HTTP {0} for label '{1}' ({2})" -f $r.Status, $label, $why.Substring(0, [Math]::Min(120, $why.Length))) 'WARN'
                }
            }
        } catch {
            $fail++; Write-Log "upload: $($_.Exception.Message)" 'ERROR'
        }
        Start-Sleep -Milliseconds 750     # the public front door is rate-limited per client
    }
    $summary = "uploaded $ok ($bound bound), $dup already there, $fail failed"
    Write-Log "upload: $summary"
    $summary
}

function Move-Uploaded([IO.FileInfo]$f) {
    $dest = Join-Path $f.DirectoryName $script:UploadedDir
    if (-not (Test-Path $dest)) { New-Item -ItemType Directory -Force $dest | Out-Null }
    Move-Item -Force $f.FullName (Join-Path $dest $f.Name)
}

# ----------------------------------------------------------------------------
# self-test — headless
# ----------------------------------------------------------------------------
if ($SelfTest) {
    if ($DicomFile) {
        $t = Read-DicomTags $DicomFile
        Write-Host ("TransferSyntax : {0}" -f $t.TransferSyntax)
        Write-Host ("PatientID      : {0}" -f $t.PatientID)
        Write-Host ("StudyDate      : {0}  -> {1}" -f $t.StudyDate, (ConvertTo-IsoDate $t.StudyDate))
        Write-Host ("Laterality     : {0}  -> {1}" -f $t.Laterality, (ConvertTo-Laterality $t.Laterality))
        Write-Host ("ImageLaterality: {0}  -> {1}" -f $t.ImageLaterality, (ConvertTo-Laterality $t.ImageLaterality))
        Write-Host ("SOPInstanceUID : {0}" -f $t.SOPInstanceUID)
        exit 0
    }
    # With -ConfigPath pointing at a real (or legacy) settings file, show what
    # Read-Config makes of it — this is how the minutes->seconds migration is
    # checked without a tray session.
    if (Test-Path $ConfigPath) {
        $loaded = Read-Config
        Write-Host ("config load    : {0}" -f $ConfigPath)
        Write-Host ("  worklist every {0} s, upload every {1} min, enabled={2}" -f $loaded.WorklistIntervalSec, $loaded.UploadIntervalMin, $loaded.Enabled)
    }
    $c = Get-DefaultConfig
    $c.TokenProtected = Protect-Token 'self-test-token'
    $tmp = Join-Path $env:TEMP ('optomed-bridge-selftest-' + [guid]::NewGuid() + '.json')
    $c | ConvertTo-Json | Set-Content $tmp -Encoding UTF8
    $back = Get-Content $tmp -Raw | ConvertFrom-Json
    Remove-Item $tmp
    $round = Unprotect-Token $back.TokenProtected
    if ($round -ne 'self-test-token') { Write-Host 'config round-trip FAILED'; exit 1 }
    Write-Host 'config round-trip OK (token survives DPAPI)'
    exit 0
}

# ----------------------------------------------------------------------------
# tray app
# ----------------------------------------------------------------------------
$mutex = New-Object Threading.Mutex($false, 'Global\LibreClinicaOptomedBridge')
if (-not $mutex.WaitOne(0, $false)) { exit 0 }   # already running

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()

# ----------------------------------------------------------------------------
# the Optomed Client: keep it running, keep its window out of the way
# ----------------------------------------------------------------------------
if (-not ('OptomedBridge.Win32' -as [type])) {
    # Minimise, never hide. The Client is WinForms hosting a WebView2, and
    # hiding that window from outside (SW_HIDE) detaches the WebView's
    # render target: it comes back as a white or black rectangle and no
    # resize revives it. Minimise/restore is the normal lifecycle and
    # survives. The taskbar button of the minimised window is removed with
    # the shell's own ITaskbarList::DeleteTab - the documented way, and the
    # one the classic tray-minimiser utilities use - and put back with
    # AddTab on show. DWM cloaking, the other invisible-but-alive option, is
    # refused for another process's window (E_ACCESSDENIED).
    Add-Type -Namespace OptomedBridge -Name Win32 -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
[DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
[DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
[ComImport, Guid("56FDF342-FD6D-11d0-958A-006097C9A090"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface ITaskbarList { void HrInit(); void AddTab(IntPtr h); void DeleteTab(IntPtr h); void ActivateTab(IntPtr h); void SetActiveAlt(IntPtr h); }
[ComImport, Guid("56FDF344-FD6D-11d0-958A-006097C9A090")] public class TaskbarListCo { }
public static void DeleteTab(IntPtr h) { var t = (ITaskbarList)new TaskbarListCo(); t.HrInit(); t.DeleteTab(h); }
public static void AddTab(IntPtr h)    { var t = (ITaskbarList)new TaskbarListCo(); t.HrInit(); t.AddTab(h); }
'@
}
$script:SW_MINIMIZE = 6; $script:SW_RESTORE = 9
$script:ClientShownByUser = $false   # "Show" from the menu stops the bridge re-minimising it
$script:ClientHwnd = [IntPtr]::Zero  # last handle seen, for the moments .NET reports none

function Get-ClientProcess {
    Get-Process -Name OptomedClient -ErrorAction SilentlyContinue | Select-Object -First 1
}

function Start-ClientIfNeeded {
    if (Get-ClientProcess) { return $false }
    $exe = $script:Cfg.ClientExe
    if (-not $exe -or -not (Test-Path $exe)) { return $false }
    Start-Process -FilePath $exe -WorkingDirectory (Split-Path $exe) | Out-Null
    Write-Log 'optomed client: was not running, started it'
    $true
}

# Process.MainWindowHandle is the right handle for a WinForms app, and it
# stays valid while the window is MINIMISED (only a hidden window zeroes
# it - which is one more reason this never hides). Re-read on every call:
# the window may not exist yet right after the Client starts, and
# Get-Process caches the value. The last handle seen covers the moments
# .NET reports none, such as while a modal dialog of the Client's is up.
function Get-ClientWindowHandle {
    $p = Get-ClientProcess
    if (-not $p) { $script:ClientHwnd = [IntPtr]::Zero; return [IntPtr]::Zero }
    $p.Refresh()
    $h = $p.MainWindowHandle
    if ($h -ne [IntPtr]::Zero) { $script:ClientHwnd = $h; return $h }
    $script:ClientHwnd
}

# "Hidden" means minimised with no taskbar button; "shown" means restored
# with its button back. Nothing here changes the window's size or style, and
# nothing hides it: see the comment on the Win32 type for why.
function Set-ClientWindowVisible([bool]$visible) {
    $h = Get-ClientWindowHandle
    if ($h -eq [IntPtr]::Zero) { return $false }
    if (-not $visible) {
        [OptomedBridge.Win32]::ShowWindow($h, $script:SW_MINIMIZE) | Out-Null
        $tries = 0
        while ($tries -lt 30 -and -not [OptomedBridge.Win32]::IsIconic($h)) { Start-Sleep -Milliseconds 100; $tries++ }
        # Soft-fail: a shell that refuses leaves an ordinary minimised window,
        # which is still the right state, just with a button.
        try { [OptomedBridge.Win32]::DeleteTab($h) } catch { Write-Log "optomed client: taskbar button could not be removed: $($_.Exception.Message)" 'WARN' }
        return $true
    }
    try { [OptomedBridge.Win32]::AddTab($h) } catch { }
    [OptomedBridge.Win32]::ShowWindow($h, $script:SW_RESTORE) | Out-Null
    $tries = 0
    while ($tries -lt 30 -and [OptomedBridge.Win32]::IsIconic($h)) { Start-Sleep -Milliseconds 100; $tries++ }
    [OptomedBridge.Win32]::SetForegroundWindow($h) | Out-Null
    $true
}

# Hide once the window exists - polled, because at login the Client (which
# autostarts too) is usually still coming up when the bridge is already here.
$hideTimer = New-Object Windows.Forms.Timer
$hideTimer.Interval = 2000
$script:HideAttempts = 0
$hideTimer.Add_Tick({
    $script:HideAttempts++
    $h = Get-ClientWindowHandle
    if ($h -ne [IntPtr]::Zero -and [OptomedBridge.Win32]::IsWindowVisible($h) -and -not [OptomedBridge.Win32]::IsIconic($h)) {
        Set-ClientWindowVisible $false | Out-Null
        Write-Log 'optomed client: window minimised, taskbar button removed'
        $hideTimer.Stop()
    } elseif ($script:HideAttempts -ge 45) {   # 90 s: it is not coming; stop polling
        $hideTimer.Stop()
    }
})
function Request-ClientHide {
    if (-not $script:Cfg.HideClientWindow -or $script:ClientShownByUser) { return }
    $script:HideAttempts = 0
    $hideTimer.Start()
}

$script:Cfg = Read-Config
Write-Log "$script:AppName starting (enabled=$($script:Cfg.Enabled))"

function New-TrayIcon([bool]$enabled) {
    # Drawn, not shipped: a filled circle, teal when enabled, grey when not.
    $bmp = New-Object Drawing.Bitmap 16, 16
    $g = [Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $brush = New-Object Drawing.SolidBrush ($(if ($enabled) { [Drawing.Color]::FromArgb(0, 128, 128) } else { [Drawing.Color]::Gray }))
    $g.FillEllipse($brush, 2, 2, 12, 12)
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
$fetchItem   = $menu.Items.Add('Fetch worklist now')
$uploadItem  = $menu.Items.Add('Upload studies now')
$menu.Items.Add('-') | Out-Null
$showClientItem = $menu.Items.Add('Show Optomed Client')
$hideClientItem = $menu.Items.Add('Hide Optomed Client')
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

function Invoke-FetchNow {
    try { Set-Status ('worklist: ' + (Invoke-WorklistFetch $script:Cfg) + ' @ ' + (Get-Date -Format 'HH:mm')) }
    catch { Write-Log "worklist: $($_.Exception.Message)" 'ERROR'; Set-Status 'worklist: failed'; Show-Problem "Worklist fetch failed: $($_.Exception.Message)" }
}
function Invoke-UploadNow {
    try { Set-Status ('upload: ' + (Invoke-StudyUpload $script:Cfg) + ' @ ' + (Get-Date -Format 'HH:mm')) }
    catch { Write-Log "upload: $($_.Exception.Message)" 'ERROR'; Set-Status 'upload: failed'; Show-Problem "Upload failed: $($_.Exception.Message)" }
}

$fetchTimer  = New-Object Windows.Forms.Timer
$uploadTimer = New-Object Windows.Forms.Timer
function Update-Timers {
    # Worklist in seconds: the photographer enrols a subject and walks to the
    # dock expecting it on the camera, so the poll has to beat the walk. Cheap
    # on both ends — the fetch is one scoped single-day query and the file is
    # only dropped when it changed. Uploads stay in minutes; nobody waits on them.
    $fetchTimer.Interval  = [Math]::Max(10, [int]$script:Cfg.WorklistIntervalSec) * 1000
    $uploadTimer.Interval = [Math]::Max(1, [int]$script:Cfg.UploadIntervalMin) * 60000
    $fetchTimer.Enabled = [bool]$script:Cfg.Enabled
    $uploadTimer.Enabled = [bool]$script:Cfg.Enabled
    $tray.Icon = New-TrayIcon $script:Cfg.Enabled
    if (-not $script:Cfg.Enabled) { Set-Status 'Disabled' }
}
$fetchTimer.Add_Tick({ Invoke-FetchNow })
$uploadTimer.Add_Tick({ if (Start-ClientIfNeeded) { Request-ClientHide }; Invoke-UploadNow })

$enabledItem.Add_Click({
    $script:Cfg.Enabled = $enabledItem.Checked
    Save-Config $script:Cfg
    Update-Timers
    Write-Log ("enabled={0}" -f $script:Cfg.Enabled)
    if ($script:Cfg.Enabled) { Invoke-FetchNow }
})
$fetchItem.Add_Click({ Invoke-FetchNow })
$uploadItem.Add_Click({ Invoke-UploadNow })
$logItem.Add_Click({ if (Test-Path $script:LogPath) { Start-Process notepad.exe $script:LogPath } })
$showClientItem.Add_Click({
    $script:ClientShownByUser = $true; $hideTimer.Stop()
    if (-not (Set-ClientWindowVisible $true)) {
        if (Start-ClientIfNeeded) { Set-Status 'optomed client: starting' }
        else { Set-Status 'optomed client: no window found'; Write-Log 'optomed client: show requested but no window found' 'WARN' }
    }
})
$hideClientItem.Add_Click({
    $script:ClientShownByUser = $false
    if (-not (Set-ClientWindowVisible $false)) { Request-ClientHide }
})
$exitItem.Add_Click({ $tray.Visible = $false; [Windows.Forms.Application]::Exit() })

$settingsItem.Add_Click({
    $f = New-Object Windows.Forms.Form
    $f.Text = "$script:AppName - Settings"; $f.StartPosition = 'CenterScreen'; $f.FormBorderStyle = 'FixedDialog'
    $f.MaximizeBox = $false; $f.MinimizeBox = $false; $f.ClientSize = New-Object Drawing.Size 520, 334

    $y = 14
    function Add-Row([string]$label, [Windows.Forms.Control]$ctl) {
        $l = New-Object Windows.Forms.Label; $l.Text = $label; $l.Location = New-Object Drawing.Point 12, ($y + 3); $l.AutoSize = $true
        $ctl.Location = New-Object Drawing.Point 170, $y; $ctl.Width = 330
        $f.Controls.Add($l); $f.Controls.Add($ctl)
        Set-Variable -Scope 1 -Name y -Value ($y + 34)
    }
    $tbUrl   = New-Object Windows.Forms.TextBox; $tbUrl.Text = $script:Cfg.BaseUrl
    $tbTok   = New-Object Windows.Forms.TextBox; $tbTok.UseSystemPasswordChar = $true; $tbTok.Text = (Unprotect-Token $script:Cfg.TokenProtected)
    $tbRoot  = New-Object Windows.Forms.TextBox; $tbRoot.Text = $script:Cfg.ClientRoot
    $tbDev   = New-Object Windows.Forms.TextBox; $tbDev.Text = $script:Cfg.Device
    $nuFetch = New-Object Windows.Forms.NumericUpDown; $nuFetch.Minimum = 10; $nuFetch.Maximum = 3600; $nuFetch.Value = [int]$script:Cfg.WorklistIntervalSec
    $nuUp    = New-Object Windows.Forms.NumericUpDown; $nuUp.Minimum = 1; $nuUp.Maximum = 120; $nuUp.Value = [int]$script:Cfg.UploadIntervalMin
    $cbOn    = New-Object Windows.Forms.CheckBox; $cbOn.Text = 'Enabled'; $cbOn.Checked = [bool]$script:Cfg.Enabled
    $cbHide  = New-Object Windows.Forms.CheckBox; $cbHide.Text = 'Hide the Optomed Client window (this icon is the only one)'; $cbHide.Checked = [bool]$script:Cfg.HideClientWindow

    Add-Row 'Platform base URL' $tbUrl
    Add-Row 'Worklist token' $tbTok
    Add-Row 'Optomed Client folder' $tbRoot
    Add-Row 'Device name (on upload)' $tbDev
    Add-Row 'Worklist fetch, every (sec)' $nuFetch
    Add-Row 'Study upload, every (min)' $nuUp
    Add-Row '' $cbOn
    Add-Row '' $cbHide

    $ok = New-Object Windows.Forms.Button; $ok.Text = 'Save'; $ok.DialogResult = 'OK'; $ok.Location = New-Object Drawing.Point 330, 294
    $cancel = New-Object Windows.Forms.Button; $cancel.Text = 'Cancel'; $cancel.DialogResult = 'Cancel'; $cancel.Location = New-Object Drawing.Point 420, 294
    $f.Controls.AddRange(@($ok, $cancel)); $f.AcceptButton = $ok; $f.CancelButton = $cancel

    if ($f.ShowDialog() -eq 'OK') {
        $script:Cfg.BaseUrl = $tbUrl.Text.Trim()
        $script:Cfg.TokenProtected = Protect-Token $tbTok.Text.Trim()
        $script:Cfg.ClientRoot = $tbRoot.Text.Trim()
        $script:Cfg.Device = $tbDev.Text.Trim()
        $script:Cfg.WorklistIntervalSec = [int]$nuFetch.Value
        $script:Cfg.UploadIntervalMin = [int]$nuUp.Value
        $script:Cfg.Enabled = $cbOn.Checked
        $script:Cfg.HideClientWindow = $cbHide.Checked
        Save-Config $script:Cfg
        if ($script:Cfg.HideClientWindow) { $script:ClientShownByUser = $false; Request-ClientHide }
        else { Set-ClientWindowVisible $true | Out-Null }
        $enabledItem.Checked = $cbOn.Checked
        Update-Timers
        Write-Log 'settings saved'
        if ($script:Cfg.Enabled) { Invoke-FetchNow }
    }
    $f.Dispose()
})

Update-Timers
Start-ClientIfNeeded | Out-Null
Request-ClientHide
if ($script:Cfg.Enabled) { Invoke-FetchNow; Invoke-UploadNow }

$ctx = New-Object Windows.Forms.ApplicationContext
try { [Windows.Forms.Application]::Run($ctx) }
finally {
    Set-ClientWindowVisible $true | Out-Null   # the bridge hid it; the bridge gives it back
    $tray.Visible = $false; $tray.Dispose()
    $mutex.ReleaseMutex() | Out-Null
    Write-Log "$script:AppName stopped"
}
