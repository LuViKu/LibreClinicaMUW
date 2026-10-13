<#
.SYNOPSIS
  Behaviour tests for the uploader-token handling (DR-040) of ExportWatcher.ps1
  and OptomedBridge.ps1.

.DESCRIPTION
  Both scripts are Windows PowerShell 5.1 tray programs, but what is under test
  here - what a request carries, and what a 401 does to the files - is plain
  code. This test loads a script's function library (everything before its
  first headless mode, so no tray and no GUI), puts a fake platform behind its
  HTTP client, replaces DPAPI (Windows only) by a reversible stand-in, and then
  drives real sweeps, worklist fetches and upload rounds over a temporary
  folder. Nothing touches the network, the real settings or a real watch folder.

  What it checks:
    - X-MUW-Uploader-Token is on every request when a token is set (resolve,
      commit, heartbeat, and the bridge's worklist) and on none when not set;
    - a 401 - "token required", "token rejected", or any other text - leaves
      every file where it was: nothing moved to _uploaded, _failed or
      _skipped, no attempt counted, nothing filed without a visit, no further
      request; it yields the coded problem token-required / token-rejected and
      the German tray text, and the next sweep simply tries again;
    - a 401 in the middle of a sweep still files what the platform accepted
      before it, and leaves the refused file (also a half-uploaded .e2e) alone;
    - the bridge's worklist call keeps the last good worklist on a 401 and
      writes no empty one;
    - a 429, a 500 and the give-up after 5 attempts behave as they did;
    - (watcher, DR-041) an .e2e is uploaded whole and split by the platform: the
      subject is the folder that directly contains the file (an .e2e directly in the watch folder is
      not uploaded, only counted: summary and coded problem no-subject-folder, no
      file name in the log); per file a streamed SHA-256, GET preflight
      ?sha256=..&split=true (a hit goes to _uploaded without a commit), else ONE
      commit with the fields file/split/patientId/device only, the file as a
      StreamContent (no ReadAllBytes, no header parse, no resolve); 201 counts
      files, series, bound and parked, 409 is "already there", a 401 ends the round
      untouched, other answers are retried and end in _failed after 5; DICOM is
      unchanged (and the .dcm tests above still pass);
    - a refusal that persists is logged once, and the token never appears in
      the log or the heartbeat body.

  Each guarantee above was checked to fail against a copy of the script with
  the old behaviour put back (a 401 treated as a failed lookup or an ordinary
  failed upload, counted as an attempt, local moves done before the platform
  answered, a request sent without the header).

  Run it with PowerShell 7 or Windows PowerShell 5.1 (powershell.exe -NoProfile -ExecutionPolicy Bypass -File ...), once per script. On a Mac, from the repository root
  (--platform linux/amd64 is required, the default arm64 image resolves to a
  32-bit build that hangs; the environment variable works around a crash of
  .NET under x86 emulation on Apple Silicon):

    docker run --rm --platform linux/amd64 -e DOTNET_EnableWriteXorExecute=0 \
      -v "$PWD/deploy:/deploy:ro" mcr.microsoft.com/powershell:latest \
      pwsh -NoProfile -File /deploy/tests/Test-UploaderToken.ps1 -Target watcher -Deploy /deploy

  and again with -Target bridge. Exit code 0 = every check passed.

  ASCII only, like the scripts it tests.
#>
param(
    [Parameter(Mandatory)][ValidateSet('watcher', 'bridge')][string]$Target,
    [Parameter(Mandatory)][string]$Deploy
)
$ErrorActionPreference = 'Stop'
$script:Pass = 0; $script:Fail = 0
function Check([bool]$ok, [string]$what) {
    if ($ok) { $script:Pass++ } else { $script:Fail++; Write-Host "  FAIL  $what" }
}
function Section([string]$name) { Write-Host "-- $name" }

$watcherPath = Join-Path $Deploy 'export-watcher/ExportWatcher.ps1'
$bridgePath  = Join-Path $Deploy 'optomed/OptomedBridge.ps1'
$scriptPath  = if ($Target -eq 'watcher') { $watcherPath } else { $bridgePath }
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('lc-token-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $tmp | Out-Null
$TOK = 'mwu_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ'     # 'mwu_' + 43 characters
$OLD = 'old-shared-worklist-token'
$REQUIRED = '{"message":"uploader token required"}'
$REJECTED = '{"message":"uploader token rejected"}'
$TEXT_REQUIRED = 'Token fehlt'
$TEXT_REJECTED = 'Token abgelehnt - bitte in den Einstellungen pruefen'

try {
Write-Host "== $Target ($scriptPath)"

# ----------------------------------------------------------------------------
# static guards on the source text
# ----------------------------------------------------------------------------
Section 'source'
$bytes = [IO.File]::ReadAllBytes($scriptPath)
Check (@($bytes | Where-Object { $_ -gt 127 }).Count -eq 0) 'ASCII only (Windows PowerShell 5.1 reads a BOM-less file as cp1252)'
$source = [IO.File]::ReadAllText($scriptPath)
Check (-not ($source -match 'PostAsync|GetAsync|GetStringAsync|Invoke-WebRequest|Invoke-RestMethod|WebClient')) 'no request is sent around Send-DeviceRequest / the worklist SendAsync'
$expectedSends = if ($Target -eq 'watcher') { 1 } else { 2 }
Check (([regex]::Matches($source, '\.SendAsync\(')).Count -eq $expectedSends) "exactly $expectedSends SendAsync call(s) in the script"
Check (-not ($source -match 'Write-(Log|Host)[^\r\n]*\$(token|plain|tok)\b')) 'no log line interpolates a token variable'

function Get-SharedBlock([string]$path) {
    $t = [IO.File]::ReadAllText($path)
    $s = $t.IndexOf('# the uploader token (DR-040)'); $e = $t.IndexOf('# health - what the heartbeat reports')
    $t.Substring($s, $e - $s)
}
function Get-DpapiPair([string]$path) {
    [regex]::Match([IO.File]::ReadAllText($path), '(?ms)^function Protect-Token.*?^}.*?^function Unprotect-Token.*?^}').Value
}
Check ((Get-SharedBlock $watcherPath) -ceq (Get-SharedBlock $bridgePath)) 'the shared token block is word for word the same in both scripts'
Check ((Get-DpapiPair $watcherPath).Length -gt 200 -and (Get-DpapiPair $watcherPath) -ceq (Get-DpapiPair $bridgePath)) 'Protect-Token / Unprotect-Token are the same in both scripts'

# ----------------------------------------------------------------------------
# load the script's function library: everything before its first headless mode
# ----------------------------------------------------------------------------
$cut = $source.IndexOf("`nif (`$SelfTest) {")
if ($cut -lt 0) { throw 'no headless section found in the script' }
foreach ($v in 'USERPROFILE', 'LOCALAPPDATA', 'ProgramData') { if (-not [Environment]::GetEnvironmentVariable($v)) { [Environment]::SetEnvironmentVariable($v, $tmp) } }
$configPath = Join-Path $tmp 'state/settings.json'
. ([scriptblock]::Create($source.Substring(0, $cut))) -ConfigPath $configPath

# ----------------------------------------------------------------------------
# a token that cannot be read: the REAL Unprotect-Token (DPAPI does not exist
# here, so this is exactly "saved by another user"), reported once
# ----------------------------------------------------------------------------
Section 'a token that cannot be read'
$unreadable = Get-DefaultConfig; $unreadable.UploaderTokenProtected = 'not-a-dpapi-blob'
Check ((Get-UploaderToken $unreadable) -eq '' -and (Get-UploaderToken $unreadable) -eq '') 'no token, and no exception'
Check (([regex]::Matches((Get-Content $script:LogPath -Raw), 'token could not be unprotected')).Count -eq 1) 'one log line for two requests, not one per request'

# stand-ins from here on
function Protect-Token([string]$plain) { if ([string]::IsNullOrEmpty($plain)) { return '' }; 'prot:' + $plain }
function Unprotect-Token([string]$protected) {
    if ([string]::IsNullOrEmpty($protected)) { return '' }
    if ($protected.StartsWith('prot:')) { return $protected.Substring(5) }
    ''
}
function Start-Sleep { param([int]$Milliseconds, [int]$Seconds) }

# ----------------------------------------------------------------------------
# pure decisions
# ----------------------------------------------------------------------------
Section 'token format'
Check (Test-UploaderTokenFormat $TOK) 'a well-formed token passes'
Check (-not (Test-UploaderTokenFormat ($TOK + 'x'))) '44 characters after the prefix is refused'
Check (-not (Test-UploaderTokenFormat $TOK.Substring(0, 46))) '42 characters after the prefix is refused'
Check (-not (Test-UploaderTokenFormat ('MWU_' + $TOK.Substring(4)))) 'the prefix is lower case'
Check (-not (Test-UploaderTokenFormat ('tok_' + $TOK.Substring(4)))) 'another prefix is refused'
Check (-not (Test-UploaderTokenFormat ($TOK.Substring(0, 20) + '+' + $TOK.Substring(21)))) 'a base64 (not base64url) character is refused'
Check (-not (Test-UploaderTokenFormat ($TOK.Substring(0, 20) + ' ' + $TOK.Substring(21)))) 'a space inside is refused'
Check (-not (Test-UploaderTokenFormat ($TOK + "`n"))) 'a trailing newline is refused (the dialog trims first)'
Check (-not (Test-UploaderTokenFormat '') -and -not (Test-UploaderTokenFormat $null)) 'empty is not a token'
Check (Test-UploaderTokenFormat ('mwu_' + ('-' * 21) + ('_' * 22))) 'base64url dash and underscore are allowed'

Section 'what a 401 means'
Check ((Get-TokenProblemCode 401 $REQUIRED) -eq 'token-required') 'token required'
Check ((Get-TokenProblemCode 401 $REJECTED) -eq 'token-rejected') 'token rejected'
Check ((Get-TokenProblemCode 401 '<html>401 Unauthorized</html>') -eq 'token-rejected') 'any other text is a rejection'
Check ((Get-TokenProblemCode 401 '{"message":"Invalid or missing X-MUW-Optomed-Token"}') -eq 'token-rejected') 'the old worklist refusal is a rejection'
Check ((Get-TokenProblemCode 401 '') -eq 'token-rejected' -and (Get-TokenProblemCode 401 $null) -eq 'token-rejected') 'an empty body is a rejection'
foreach ($status in 200, 201, 400, 403, 404, 409, 429, 500, 503) {
    Check ($null -eq (Get-TokenProblemCode $status $REQUIRED)) "HTTP $status is not a token problem, whatever the body says"
}
Check ((Get-TokenProblemText 'token-required') -ceq $TEXT_REQUIRED) 'German text: Token fehlt'
Check ((Get-TokenProblemText 'token-rejected') -ceq $TEXT_REJECTED) 'German text: Token abgelehnt ...'
Check ((("Export Watcher - " + (Get-TokenProblemTip 'token-rejected')).Length -le 63) -and (("Optomed Bridge - " + (Get-TokenProblemTip 'token-rejected')).Length -le 63)) 'the tooltip variant fits the 63 characters of a tray tooltip'
Check ((Get-TokenProblemIn @{ 'upload-failed' = $true; 'token-required' = $true }) -eq 'token-required' -and (Get-TokenProblemIn @{ 'upload-failed' = $true }) -eq '') 'token problem found in a set of coded problems'

Section 'a refusal travels as an exception'
$got = $null; try { throw (New-TokenRefusal 'token-rejected') } catch { $got = Get-TokenRefusalCode $_ }
Check ($got -eq 'token-rejected') 'code survives throw and catch'
$got = $null; try { try { throw (New-TokenRefusal 'token-required') } catch { throw (New-Object System.Exception 'outer', $_.Exception) } } catch { $got = Get-TokenRefusalCode $_ }
Check ($got -eq 'token-required') 'found when wrapped as an inner exception'
$got = 'x'; try { throw 'boom' } catch { $got = Get-TokenRefusalCode $_ }
Check ($null -eq $got) 'an ordinary error is no refusal'

# ----------------------------------------------------------------------------
# a fake platform behind the HTTP client
# ----------------------------------------------------------------------------
Add-Type -ReferencedAssemblies System.Net.Http -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Threading;
using System.Threading.Tasks;
public class FakePlatform : HttpMessageHandler
{
    // Replies are queued per endpoint (the last segment of the path). Seen records
    // "endpoint up=<X-MUW-Uploader-Token or -> old=<X-MUW-Optomed-Token or ->"; Fields is parallel to
    // it: the form fields of an upload ("name=value;" each, the file itself as <file>) or the JSON of a lookup.
    public Dictionary<string, Queue<HttpResponseMessage>> Replies = new Dictionary<string, Queue<HttpResponseMessage>>();
    public List<string> Seen = new List<string>();
    public List<string> Fields = new List<string>();
    public List<string> Queries = new List<string>();     // parallel to Seen: "METHOD ?query"
    public List<string> FileParts = new List<string>();   // per upload: "<part class>:<bytes read>:<sha256 of them>"
    public int Unexpected = 0;
    public void Enqueue(string endpoint, HttpResponseMessage reply)
    {
        if (!Replies.ContainsKey(endpoint)) Replies[endpoint] = new Queue<HttpResponseMessage>();
        Replies[endpoint].Enqueue(reply);
    }
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        IEnumerable<string> v; string up = "-", old = "-";
        if (request.Headers.TryGetValues("X-MUW-Uploader-Token", out v)) up = string.Join(",", v);
        if (request.Headers.TryGetValues("X-MUW-Optomed-Token", out v)) old = string.Join(",", v);
        string path = request.RequestUri.AbsolutePath;
        string endpoint = path.Substring(path.LastIndexOf('/') + 1);
        Seen.Add(endpoint + " up=" + up + " old=" + old);
        Queries.Add(request.Method.Method + " " + request.RequestUri.Query);
        string fields = "";
        MultipartFormDataContent mp = request.Content as MultipartFormDataContent;
        if (mp != null)
        {
            foreach (HttpContent part in mp)
            {
                ContentDispositionHeaderValue cd = part.Headers.ContentDisposition;
                if (cd == null || cd.Name == null) continue;
                string name = cd.Name.Trim('"');
                if (cd.FileName != null)
                {
                    fields += name + "=<file>;";
                    using (System.IO.Stream s = part.ReadAsStreamAsync().Result)
                    using (System.Security.Cryptography.HashAlgorithm sha = System.Security.Cryptography.SHA256.Create())
                    {
                        long n = 0; byte[] buf = new byte[65536]; int r;
                        while ((r = s.Read(buf, 0, buf.Length)) > 0) { sha.TransformBlock(buf, 0, r, null, 0); n += r; }
                        sha.TransformFinalBlock(buf, 0, 0);
                        FileParts.Add(part.GetType().Name + ":" + n + ":" + BitConverter.ToString(sha.Hash).Replace("-", "").ToLowerInvariant());
                    }
                    continue;
                }
                fields += name + "=" + part.ReadAsStringAsync().Result + ";";
            }
        }
        else if (request.Content != null) fields = request.Content.ReadAsStringAsync().Result;
        Fields.Add(fields);
        HttpResponseMessage reply;
        if (Replies.ContainsKey(endpoint) && Replies[endpoint].Count > 0) reply = Replies[endpoint].Dequeue();
        else
        {
            Unexpected++;
            reply = new HttpResponseMessage((HttpStatusCode)599);
            reply.Content = new StringContent("unexpected request to " + endpoint);
        }
        return Task.FromResult(reply);
    }
}
'@
function New-Reply([int]$status, [string]$body = '') {
    $r = New-Object System.Net.Http.HttpResponseMessage ([Enum]::ToObject([System.Net.HttpStatusCode], $status))   # not a cast: 5.1 has no name for 429
    $r.Content = New-Object System.Net.Http.StringContent($body, [Text.Encoding]::UTF8, 'application/json')
    $r
}
function New-ResolveReply([int]$count) {
    $items = for ($i = 0; $i -lt $count; $i++) { '{"state":"suggested","candidates":[{"matchingEvent":{"studyEventId":' + (1000 + $i) + '}}]}' }
    New-Reply 200 ('{"scans":[' + ($items -join ',') + ']}')
}
function Reset-Fakes {
    $script:Fake = New-Object FakePlatform; $script:FakeHb = New-Object FakePlatform
    $script:Http = New-Object System.Net.Http.HttpClient($script:Fake)
    $script:HbHttp = New-Object System.Net.Http.HttpClient($script:FakeHb)
    $script:HbLastCode = -1; $script:HbLastProblem = ''; $script:HbProblem = ''
    if (Test-Path $script:LogPath) { Remove-Item $script:LogPath -Force }      # one log per scenario
}
function Get-LogText { if (Test-Path $script:LogPath) { Get-Content $script:LogPath -Raw } else { '' } }
function Get-LogCount([string]$pattern) { ([regex]::Matches((Get-LogText), $pattern)).Count }
function Get-Names([string]$dir, [string]$sub = '') {
    $p = if ($sub) { Join-Path $dir $sub } else { $dir }
    if (-not (Test-Path -LiteralPath $p)) { return '(none)' }
    (@(Get-ChildItem -LiteralPath $p -File -Force | Sort-Object Name | ForEach-Object { $_.Name })) -join ','
}
# every secret stays out of the log, whatever the scenario did
function Assert-NoSecret([string]$what) {
    $log = Get-LogText
    Check (-not $log.Contains($TOK) -and -not $log.Contains('prot:') -and -not $log.Contains($OLD)) "$what : no token in the log"
}
function Assert-Header([object]$fake, [string]$token, [string]$what) {
    $bad = @($fake.Seen | Where-Object { $_ -notlike "* up=$token *" })
    Check ($fake.Seen.Count -gt 0 -and $bad.Count -eq 0) "$what : every request carried the right X-MUW-Uploader-Token ($($fake.Seen.Count) request(s))"
}

# ----------------------------------------------------------------------------
# the Export Watcher: real sweeps over a temporary watch folder
# ----------------------------------------------------------------------------
if ($Target -eq 'watcher') {
    $script:OrigGetWatchedFiles = ${function:Get-WatchedFiles}
    function Get-WatchedFiles([string]$root) {            # the real listing, sorted: the replies below are queued in file order
        $r = & $script:OrigGetWatchedFiles $root
        [pscustomobject]@{ Pending = @($r.Pending | Sort-Object Name); Failed = $r.Failed }
    }
    function New-Scan([string]$label, [int]$index, [string]$kind, [bool]$nonImage) {
        [pscustomobject]@{ PatientId = $label; ScanDate = '2026-10-12'; Laterality = 'OD'; ScanIndex = $index
                           Device = $(if ($kind -eq 'e2e') { 'spectralis' } else { 'clarus' }); Kind = $kind; NonImage = $nonImage }
    }
    # Stands in for the DICOM reader (an .e2e is no longer read) and returns the REAL SHAPE: the scans of every file wrapped as
    # ,@(...), whether there is one or several - which is what the real Get-FileScans does, and what the sweep
    # must cope with (it once called it as @(Get-FileScans ...), which keeps two or more scans nested in one
    # element). raw* is a Raw Data object (NonImage), bad* cannot be read.
    function Get-FileScans([pscustomobject]$cfg, [IO.FileInfo]$f) {
        if ($f.Name -like 'bad*') { throw 'unreadable test file' }
        if ($f.Name -like 'raw*') { return ,@(New-Scan 'PRAW' 0 'dicom' $true) }
        return ,@(New-Scan ('P' + $f.BaseName) 0 'dicom' $false)
    }
    function Reset-Watcher {
        Reset-Fakes
        $script:SizeSeen = @{}; $script:Attempts = @{}; $script:SweepProblems = @{}; $script:TokenLogged = ''
    }
    function New-Folder([string[]]$names) {
        $dir = Join-Path $tmp ([guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Force $dir | Out-Null
        foreach ($n in $names) {
            $p = Join-Path $dir $n
            New-Item -ItemType Directory -Force (Split-Path -Parent $p) | Out-Null
            [IO.File]::WriteAllBytes($p, [byte[]](1..40))
        }
        $dir
    }
    function New-Cfg([string]$dir, [string]$token) {
        $c = Get-DefaultConfig
        $c.WatchFolder = $dir; $c.BaseUrl = 'http://platform.test/LibreClinica'
        if ($token) { $c.UploaderTokenProtected = Protect-Token $token }
        $c
    }
    function Invoke-TwoSweeps($cfg) { Invoke-Sweep $cfg | Out-Null; Invoke-Sweep $cfg }     # the first only marks the sizes
    function Add-Commits($fake, [int[]]$statuses) { foreach ($s in $statuses) { $fake.Enqueue('commit', (New-Reply $s)) } }
    function Get-CommitScans($fake) {         # "<label>:<scanIndex>" of every commit the fake saw, in order
        for ($i = 0; $i -lt $fake.Seen.Count; $i++) {
            if ($fake.Seen[$i] -like 'commit *') {
                $label = [regex]::Match($fake.Fields[$i], 'patientId=([^;]*);').Groups[1].Value
                $index = [regex]::Match($fake.Fields[$i], 'scanIndex=([^;]*);').Groups[1].Value
                "${label}:${index}"
            }
        }
    }

    # ------------------------------------------------------------------------
    # DR-041: an .e2e is uploaded whole - subject from the folder, SHA-256 and
    # preflight, one streamed split commit
    # ------------------------------------------------------------------------
    $script:UploadTimeouts = New-Object Collections.ArrayList
    function New-UploadClient([int]$timeoutSec) {       # the upload client of the script, over the fake platform
        [void]$script:UploadTimeouts.Add($timeoutSec)
        New-Object System.Net.Http.HttpClient -ArgumentList $script:Fake, $false
    }
    function Get-ExpectedSha([string]$path) {            # independent of the script's own hashing: read whole, hash, hex
        $sha = [Security.Cryptography.SHA256]::Create()
        try { (($sha.ComputeHash([IO.File]::ReadAllBytes($path)) | ForEach-Object { $_.ToString('x2') }) -join '') } finally { $sha.Dispose() }
    }
    function New-SplitBody([string[]]$statuses, [bool]$parked) {
        $items = @(for ($i = 0; $i -lt $statuses.Count; $i++) {
            '{"ingestItemId":' + (100 + $i) + ',"seriesKey":"s' + $i + '","seriesModality":"IR","laterality":"OD","frames":1,"status":"' + $statuses[$i] + '","jobs":[]}'
        })
        '{"sha256":"x","headerPatient":"empty","parked":' + $(if ($parked) { 'true' } else { 'false' }) + ',"items":[' + ($items -join ',') + ']}'
    }
    function Add-Preflight($fake, [bool]$exists) { $fake.Enqueue('preflight', (New-Reply 200 ('{"exists":' + $(if ($exists) { 'true' } else { 'false' }) + ',"ingestItemId":null,"jobId":null}'))) }
    function Get-EndpointList($fake) { @($fake.Seen | ForEach-Object { ($_ -split ' ')[0] }) -join ',' }

    Section 'e2e patientId: the folder that directly contains the file, none for a file in the watch folder'
    $root = Join-Path $tmp 'subject-root'
    New-Item -ItemType Directory -Force (Join-Path $root 'EIAMD005/session/deeper') | Out-Null
    New-Item -ItemType Directory -Force (Join-Path $tmp 'subject-root2/X') | Out-Null
    foreach ($rel in 'top.e2e', 'EIAMD005/a.e2e', 'EIAMD005/session/b.e2e', 'EIAMD005/session/deeper/c.E2E') { [IO.File]::WriteAllBytes((Join-Path $root $rel), [byte[]](1..4)) }
    [IO.File]::WriteAllBytes((Join-Path $tmp 'subject-root2/X/d.e2e'), [byte[]](1..4))
    Check ($null -eq (Get-E2eSubject $root (Get-Item (Join-Path $root 'top.e2e')))) 'directly in the watch folder: no folder, so no patientId is sent'
    Check ((Get-E2eSubject $root (Get-Item (Join-Path $root 'EIAMD005/a.e2e'))) -ceq 'EIAMD005') 'one level down: the folder name'
    Check ((Get-E2eSubject $root (Get-Item (Join-Path $root 'EIAMD005/session/b.e2e'))) -ceq 'session') 'deeper: the immediate parent, not the first folder below the watch folder'
    Check ((Get-E2eSubject $root (Get-Item (Join-Path $root 'EIAMD005/session/deeper/c.E2E'))) -ceq 'deeper') 'much deeper, upper-case extension: the immediate parent'
    Check ((Get-E2eSubject ($root + '\') (Get-Item (Join-Path $root 'EIAMD005/a.e2e'))) -ceq 'EIAMD005') 'a watch folder given with a trailing separator'
    Check ($null -eq (Get-E2eSubject $root (Get-Item (Join-Path $tmp 'subject-root2/X/d.e2e')))) 'a sibling folder that starts with the same text is not below the watch folder'
    Check ((Test-IsE2e (Get-Item (Join-Path $root 'EIAMD005/session/deeper/c.E2E')))) 'the extension test is case-insensitive'

    Section 'e2e: a file directly in the watch folder is uploaded without a patientId'
    Reset-Watcher
    $dir = New-Folder @('root.e2e'); $cfg = New-Cfg $dir $TOK
    $sha = Get-ExpectedSha (Join-Path $dir 'root.e2e')
    Add-Preflight $script:Fake $false
    $script:Fake.Enqueue('commit', (New-Reply 201 (New-SplitBody @('BOUND', 'UNBOUND') $false)))
    $result = Invoke-TwoSweeps $cfg
    Check ($result -ceq 'uploaded 1 files -> 2 series (1 bound, 1 parked), 0 already there, 0 failed') "summary: $result"
    Check ((Get-EndpointList $script:Fake) -eq 'preflight,commit' -and $script:Fake.Unexpected -eq 0) 'preflight then commit, nothing else'
    Check ($script:Fake.Fields[1] -ceq 'file=<file>;split=true;device=spectralis;') "commit field set is file, split, device - no patientId: $($script:Fake.Fields[1])"
    Check ($script:Fake.Fields[1] -notmatch 'patientId|scanDate|laterality|scanIndex|studyEventId|park') 'no patientId, scanDate, laterality, scanIndex, studyEventId or park'
    Check ($script:Fake.FileParts.Count -eq 1 -and $script:Fake.FileParts[0] -ceq "StreamContent:40:$sha") 'streamed, all 40 bytes, hash intact'
    Check ((Get-Names $dir) -eq '' -and (Get-Names $dir '_uploaded') -eq 'root.e2e') 'filed in _uploaded of the watch folder'
    Check (-not $script:SweepProblems.ContainsKey('no-subject-folder') -and $script:SweepProblems.Count -eq 0) 'no problem code'
    $hb = Get-HeartbeatBody $cfg $true ''
    Check (@($hb.problems) -notcontains 'no-subject-folder') 'the heartbeat carries no no-subject-folder'
    Check (-not (Get-LogText).Contains('root.e2e')) 'the log names no file'
    Assert-NoSecret 'e2e flat upload'

    Section 'e2e: a flat file beside a patient folder'
    Reset-Watcher
    $dir = New-Folder @('root.e2e', 'EIAMD005/a.e2e'); $cfg = New-Cfg $dir $TOK
    $sha = Get-ExpectedSha (Join-Path $dir 'EIAMD005/a.e2e')
    foreach ($i in 1, 2) { Add-Preflight $script:Fake $false; $script:Fake.Enqueue('commit', (New-Reply 201 (New-SplitBody @('UNBOUND', 'UNBOUND', 'BOUND') $false))) }
    $script:UploadTimeouts.Clear()
    $result = Invoke-TwoSweeps $cfg
    Check ($result -ceq 'uploaded 2 files -> 6 series (2 bound, 4 parked), 0 already there, 0 failed') "summary: $result"
    Check ((Get-Names $dir '_uploaded') -eq 'root.e2e' -and (Get-Names (Join-Path $dir 'EIAMD005') '_uploaded') -eq 'a.e2e') 'each file is filed in the _uploaded folder next to it'
    $commits = @($script:Fake.Fields | Where-Object { $_ -like 'file=*' })
    Check ($commits.Count -eq 2 -and @($commits | Where-Object { $_ -ceq 'file=<file>;split=true;patientId=EIAMD005;device=spectralis;' }).Count -eq 1 -and @($commits | Where-Object { $_ -ceq 'file=<file>;split=true;device=spectralis;' }).Count -eq 1) "the folder file sends patientId=EIAMD005, the flat one none: $($commits -join ' | ')"
    Check ($script:Fake.Unexpected -eq 0) 'no stray requests'

    Section 'e2e: a patient folder file'
    Reset-Watcher
    $dir = New-Folder @('EIAMD005/a.e2e'); $cfg = New-Cfg $dir $TOK
    $sha = Get-ExpectedSha (Join-Path $dir 'EIAMD005/a.e2e')
    Add-Preflight $script:Fake $false
    $script:Fake.Enqueue('commit', (New-Reply 201 (New-SplitBody @('UNBOUND', 'UNBOUND', 'BOUND') $false)))
    $script:UploadTimeouts.Clear()
    $result = Invoke-TwoSweeps $cfg
    Check ($result -ceq 'uploaded 1 files -> 3 series (1 bound, 2 parked), 0 already there, 0 failed') "summary: $result"
    Check ((Get-Names (Join-Path $dir 'EIAMD005') '_uploaded') -eq 'a.e2e' -and (Get-Names $dir 'EIAMD005') -eq '') 'filed in its own _uploaded folder'
    Check ((Get-EndpointList $script:Fake) -eq 'preflight,commit' -and $script:Fake.Unexpected -eq 0) 'preflight then commit - no resolve, one commit for the whole file'
    Check ($script:Fake.Queries[0] -ceq "GET ?sha256=$sha&split=true") "preflight is GET ?sha256=<streamed hash>&split=true"
    Check ($script:Fake.Fields[1] -ceq 'file=<file>;split=true;patientId=EIAMD005;device=spectralis;') "commit field set is exactly file, split, patientId, device: $($script:Fake.Fields[1])"
    Check ($script:Fake.Fields[1] -notmatch 'scanDate|laterality|scanIndex|studyEventId|park') 'no scanDate, laterality, scanIndex, studyEventId or park'
    Check ($script:Fake.FileParts.Count -eq 1 -and $script:Fake.FileParts[0] -ceq "StreamContent:40:$sha") 'the file part is a StreamContent and the platform receives all 40 bytes, hash intact'
    Check ($script:Fake.Seen[0] -ceq "preflight up=$TOK old=-" -and $script:Fake.Seen[1] -ceq "commit up=$TOK old=-") 'preflight and commit both carry the uploader token'
    Check ($script:UploadTimeouts.Count -eq 1 -and $script:UploadTimeouts[0] -eq (Get-UploadTimeoutSec 40)) 'the upload runs on a client with the size-based deadline'
    Check ((Get-UploadTimeoutSec 2GB) -ge 2400 -and (Get-UploadTimeoutSec 2GB) -le 7200 -and (Get-UploadTimeoutSec 1) -ge 600) 'deadline: at least 10 minutes, about 44 minutes for 2 GiB'
    Check ($script:UploadedToday -ge 1) 'counted as uploaded today for the heartbeat'
    Check (-not (Get-LogText).Contains('a.e2e')) 'the log names no file'
    Assert-NoSecret 'e2e upload'

    Section 'e2e: the sweep summary counts files, series, bound and parked'
    foreach ($case in @(
        @{ Name = 'all parked by the platform'; Body = (New-SplitBody @('UNBOUND', 'UNBOUND') $true); Result = 'uploaded 1 files -> 2 series (0 bound, 2 parked), 0 already there, 0 failed' },
        @{ Name = 'every series bound';         Body = (New-SplitBody @('BOUND', 'BOUND', 'BOUND', 'BOUND') $false); Result = 'uploaded 1 files -> 4 series (4 bound, 0 parked), 0 already there, 0 failed' },
        @{ Name = 'a body that cannot be read'; Body = 'not json'; Result = 'uploaded 1 files -> 0 series (0 bound, 0 parked), 0 already there, 0 failed' }
    )) {
        Reset-Watcher
        $dir = New-Folder @('P1/a.e2e'); $cfg = New-Cfg $dir $TOK
        Add-Preflight $script:Fake $false; $script:Fake.Enqueue('commit', (New-Reply 201 $case.Body))
        $result = Invoke-TwoSweeps $cfg
        Check ($result -ceq $case.Result -and (Get-Names (Join-Path $dir 'P1') '_uploaded') -eq 'a.e2e') "$($case.Name) : $result"
    }

    Section 'e2e: already on the platform - preflight hit, 409'
    Reset-Watcher
    $dir = New-Folder @('P1/a.e2e'); $cfg = New-Cfg $dir $TOK
    Add-Preflight $script:Fake $true
    $result = Invoke-TwoSweeps $cfg
    Check ($result -ceq 'uploaded 0 files -> 0 series (0 bound, 0 parked), 1 already there, 0 failed') "preflight hit: $result"
    Check ((Get-EndpointList $script:Fake) -eq 'preflight' -and $script:Fake.FileParts.Count -eq 0) 'no commit, no byte of the file sent'
    Check ((Get-Names (Join-Path $dir 'P1') '_uploaded') -eq 'a.e2e' -and (Get-Names $dir 'P1') -eq '') 'moved to _uploaded'
    Reset-Watcher
    $dir = New-Folder @('P1/a.e2e'); $cfg = New-Cfg $dir $TOK
    Add-Preflight $script:Fake $false; $script:Fake.Enqueue('commit', (New-Reply 409 '{"duplicate":true,"existingIngestItemIds":[1,2]}'))
    $result = Invoke-TwoSweeps $cfg
    Check ($result -ceq 'uploaded 0 files -> 0 series (0 bound, 0 parked), 1 already there, 0 failed' -and (Get-Names (Join-Path $dir 'P1') '_uploaded') -eq 'a.e2e') "409 on commit: $result - moved to _uploaded"
    Reset-Watcher
    $dir = New-Folder @('P1/a.e2e'); $cfg = New-Cfg $dir $TOK
    $script:Fake.Enqueue('preflight', (New-Reply 500 'boom')); $script:Fake.Enqueue('commit', (New-Reply 201 (New-SplitBody @('UNBOUND') $false)))
    $result = Invoke-TwoSweeps $cfg
    Check ((Get-EndpointList $script:Fake) -eq 'preflight,commit' -and (Get-Names (Join-Path $dir 'P1') '_uploaded') -eq 'a.e2e') 'a preflight that fails is no reason not to try the commit (its 409 is the authority)'

    Section 'e2e: one patient folder per file, two patients'
    Reset-Watcher
    $dir = New-Folder @('P1/a.e2e', 'P2/b.e2e'); $cfg = New-Cfg $dir $TOK
    foreach ($i in 1, 2) { Add-Preflight $script:Fake $false; $script:Fake.Enqueue('commit', (New-Reply 201 (New-SplitBody @('UNBOUND') $false))) }
    $result = Invoke-TwoSweeps $cfg
    Check ($result -ceq 'uploaded 2 files -> 2 series (0 bound, 2 parked), 0 already there, 0 failed') "summary: $result"
    Check ((@($script:Fake.Fields | Where-Object { $_ -like 'file=*' } | ForEach-Object { [regex]::Match($_, 'patientId=([^;]*);').Groups[1].Value })) -join ',' -ceq 'P1,P2') 'each commit names its own folder as patientId'

    Section 'e2e: failures are retried, then given up'
    Reset-Watcher
    $dir = New-Folder @('P1/a.e2e'); $cfg = New-Cfg $dir $TOK
    Invoke-Sweep $cfg | Out-Null
    for ($i = 1; $i -le 5; $i++) {
        Add-Preflight $script:Fake $false; $script:Fake.Enqueue('commit', (New-Reply 500 'boom'))
        $result = Invoke-Sweep $cfg
        if ($i -lt 5) { Check ((Get-Names $dir 'P1') -eq 'a.e2e') "attempt $i : the file waits for the next sweep" }
    }
    Check ((Get-Names (Join-Path $dir 'P1') '_failed') -eq 'a.e2e' -and (Get-Names $dir 'P1') -eq '') 'after 5 attempts it goes to _failed'
    Check ($result -ceq 'uploaded 0 files -> 0 series (0 bound, 0 parked), 0 already there, 1 failed' -and $script:SweepProblems.ContainsKey('upload-failed')) 'last sweep: 1 failed, coded upload-failed'
    Check ($script:Fake.Unexpected -eq 0) 'no stray requests'
    Reset-Watcher
    $dir = New-Folder @('P1/a.e2e'); $cfg = New-Cfg $dir $TOK
    Invoke-Sweep $cfg | Out-Null
    Add-Preflight $script:Fake $false; $script:Fake.Enqueue('commit', (New-Reply 500 'boom'))
    Invoke-Sweep $cfg | Out-Null
    Add-Preflight $script:Fake $false; $script:Fake.Enqueue('commit', (New-Reply 201 (New-SplitBody @('UNBOUND') $false)))
    Invoke-Sweep $cfg | Out-Null
    Check ((Get-Names (Join-Path $dir 'P1') '_uploaded') -eq 'a.e2e' -and $script:Attempts.Count -eq 0) 'a retry that works files it as uploaded and forgets the failed attempt'

    Section 'e2e: a file over 2 GiB is not sent'
    Reset-Watcher
    $dir = New-Folder @('P1/a.e2e'); $cfg = New-Cfg $dir $TOK
    $keep = $script:MaxE2eBytes; $script:MaxE2eBytes = 10
    try { $result = Invoke-TwoSweeps $cfg } finally { $script:MaxE2eBytes = $keep }
    Check ($script:Fake.Seen.Count -eq 0 -and (Get-Names (Join-Path $dir 'P1') '_failed') -eq 'a.e2e') 'no request; moved to _failed at once'
    Check ($script:SweepProblems.ContainsKey('file-too-large') -and $result -like '*1 failed*') 'coded file-too-large, counted as failed'

    Section 'e2e: a 401 leaves the file and everything after it where it is'
    foreach ($case in @(
        @{ Name = 'on the preflight'; Pre = (New-Reply 401 $REJECTED); Code = 'token-rejected'; Seen = 1 },
        @{ Name = 'on the commit';    Pre = (New-Reply 200 '{"exists":false}'); Commit = (New-Reply 401 $REQUIRED); Code = 'token-required'; Seen = 2 }
    )) {
        Reset-Watcher
        $dir = New-Folder @('P1/a.e2e', 'P2/b.e2e'); $cfg = New-Cfg $dir $(if ($case.Code -eq 'token-required') { '' } else { $TOK })
        $script:Fake.Enqueue('preflight', $case.Pre); if ($case.ContainsKey('Commit')) { $script:Fake.Enqueue('commit', $case.Commit) }
        $result = Invoke-TwoSweeps $cfg
        Check ($result -ceq $(if ($case.Code -eq 'token-required') { $TEXT_REQUIRED } else { $TEXT_REJECTED })) "$($case.Name) : the German text"
        Check ((Get-Names $dir 'P1') -eq 'a.e2e' -and (Get-Names $dir 'P2') -eq 'b.e2e') "$($case.Name) : both files still where they were"
        Check ((Get-Names (Join-Path $dir 'P1') '_uploaded') -eq '(none)' -and (Get-Names (Join-Path $dir 'P1') '_failed') -eq '(none)') "$($case.Name) : nothing filed"
        Check ($script:Attempts.Count -eq 0 -and $script:SweepProblems.ContainsKey($case.Code)) "$($case.Name) : no attempt counted, coded $($case.Code)"
        Check ($script:Fake.Seen.Count -eq $case.Seen -and $script:Fake.Unexpected -eq 0) "$($case.Name) : the round ended at the refusal ($($case.Seen) request(s))"
    }
    Reset-Watcher
    $dir = New-Folder @('P1/a.e2e', 'P2/b.e2e'); $cfg = New-Cfg $dir $TOK
    Add-Preflight $script:Fake $false; $script:Fake.Enqueue('commit', (New-Reply 201 (New-SplitBody @('UNBOUND') $false)))
    $script:Fake.Enqueue('preflight', (New-Reply 401 $REJECTED))
    $result = Invoke-TwoSweeps $cfg
    Check ($result -ceq $TEXT_REJECTED -and (Get-Names (Join-Path $dir 'P1') '_uploaded') -eq 'a.e2e' -and (Get-Names $dir 'P2') -eq 'b.e2e') 'a 401 on the second file: the first is filed (accepted before), the second stays'
    Check ((Get-LogCount 'then the platform refused') -eq 1) 'the log says how many went before'

    Section 'e2e: a 429 on the preflight ends the round without an attempt'
    Reset-Watcher
    $dir = New-Folder @('P1/a.e2e', 'P2/b.e2e'); $cfg = New-Cfg $dir $TOK
    $script:Fake.Enqueue('preflight', (New-Reply 429))
    Invoke-TwoSweeps $cfg | Out-Null
    Check ($script:Fake.Seen.Count -eq 1 -and $script:Attempts.Count -eq 0 -and $script:SweepProblems.ContainsKey('rate-limited')) 'one request, no attempt, coded rate-limited'

    Section 'e2e beside DICOM in one sweep'
    Reset-Watcher
    $dir = New-Folder @('a.dcm', 'P1/x.e2e'); $cfg = New-Cfg $dir $TOK
    $script:Fake.Enqueue('resolve', (New-ResolveReply 1)); Add-Commits $script:Fake 201
    Add-Preflight $script:Fake $false; $script:Fake.Enqueue('commit', (New-Reply 201 (New-SplitBody @('UNBOUND', 'UNBOUND') $false)))
    $result = Invoke-TwoSweeps $cfg
    Check ($result -ceq 'uploaded 1 files -> 2 series (0 bound, 2 parked), 0 already there, 0 failed; DICOM: uploaded 1 (1 bound), 0 already there, 0 failed, 0 non-image set aside') "summary: $result"
    Check ((Get-EndpointList $script:Fake) -eq 'resolve,commit,preflight,commit' -and $script:Fake.Unexpected -eq 0) 'DICOM keeps its resolve + commit; the .e2e adds only preflight + commit'
    Check ((Get-Names $dir '_uploaded') -eq 'a.dcm' -and (Get-Names (Join-Path $dir 'P1') '_uploaded') -eq 'x.e2e') 'both filed'
    Check ($script:Fake.Fields[0] -notmatch 'x\b' -and ([regex]::Matches($script:Fake.Fields[0], 'patientId')).Count -eq 1) 'the resolve asks about the DICOM only'

    Section 'e2e: no whole-file read on the .e2e path'
    foreach ($fn in 'Get-E2eSubject', 'Get-FileSha256', 'Get-E2ePreflight', 'Send-E2eCommit', 'Get-E2eCommitCounts', 'Invoke-E2eUploads', 'Invoke-SweepCore') {
        $body = (Get-Command $fn -CommandType Function).ScriptBlock.ToString()
        Check ($body -notmatch 'ReadAllBytes|ReadAllText|ByteArrayContent|Read-E2eScans|Resolve-Batch|Get-FileScans') "$fn : no ReadAllBytes, ByteArrayContent, header parse or resolve"
    }
    Check ((Get-Command Send-E2eCommit -CommandType Function).ScriptBlock.ToString() -match 'StreamContent') 'Send-E2eCommit uses StreamContent'
    Check ((Get-Command Get-FileSha256 -CommandType Function).ScriptBlock.ToString() -match 'ComputeHash\(\$fs\)') 'Get-FileSha256 hashes the stream'
    Check (([regex]::Matches($source, 'ReadAllBytes')).Count -eq 2) 'the script reads whole files in exactly one place (DICOM Send-Commit; the other match is the comment in Send-E2eCommit)'
    Check (-not ($source -match 'Read-E2eScans')) 'the header reader is gone from the script'
    Check ((Get-FileSha256 (Join-Path $root 'EIAMD005/a.e2e')) -ceq (Get-ExpectedSha (Join-Path $root 'EIAMD005/a.e2e'))) 'the streamed SHA-256 equals the whole-file one'

    Section 'e2e: retention reaches the _uploaded folders inside patient folders'
    Reset-Watcher
    $dir = New-Folder @('P1/_uploaded/old.e2e', 'P1/_uploaded/new.e2e', '_uploaded/oldtop.dcm', 'P1/_failed/keep.e2e'); $cfg = New-Cfg $dir $TOK
    $cfg.DeleteUploaded = $true; $cfg.DeleteUploadedAfterDays = 1
    foreach ($p in 'P1/_uploaded/old.e2e', '_uploaded/oldtop.dcm', 'P1/_failed/keep.e2e') { (Get-Item (Join-Path $dir $p)).LastWriteTimeUtc = [datetime]::UtcNow.AddDays(-5) }
    $script:LastRetentionUtc = [datetime]::MinValue
    Invoke-Retention $cfg
    Check ((Get-Names (Join-Path $dir 'P1') '_uploaded') -eq 'new.e2e' -and (Get-Names $dir '_uploaded') -eq '') 'old files in both kinds of _uploaded are deleted, the new one stays'
    Check ((Get-Names (Join-Path $dir 'P1') '_failed') -eq 'keep.e2e') '_failed is never touched'

    Section 'watcher: a 401 on the lookup leaves every file where it is'
    foreach ($case in @(
        @{ Name = 'required, no token set';  Token = '';   Body = $REQUIRED;                       Code = 'token-required'; Text = $TEXT_REQUIRED },
        @{ Name = 'rejected, token set';     Token = $TOK; Body = $REJECTED;                       Code = 'token-rejected'; Text = $TEXT_REJECTED },
        @{ Name = 'other text (a proxy)';    Token = $TOK; Body = '<html>401 Unauthorized</html>'; Code = 'token-rejected'; Text = $TEXT_REJECTED },
        @{ Name = 'empty body';              Token = $TOK; Body = '';                              Code = 'token-rejected'; Text = $TEXT_REJECTED }
    )) {
        $n = $case.Name
        Reset-Watcher
        $dir = New-Folder @('a.dcm', 'b.dcm', 'c.dcm', 'raw.dcm')
        $cfg = New-Cfg $dir $case.Token
        $script:Fake.Enqueue('resolve', (New-Reply 401 $case.Body))
        $result = Invoke-TwoSweeps $cfg
        Check ($result -ceq $case.Text) "$n : the sweep's result is the German text"
        Check ((Get-Names $dir) -eq 'a.dcm,b.dcm,c.dcm,raw.dcm') "$n : every file is still in the watch folder"
        Check ((Get-Names $dir '_uploaded') -eq '(none)' -and (Get-Names $dir '_failed') -eq '(none)' -and (Get-Names $dir '_skipped') -eq '(none)') "$n : nothing moved to _uploaded / _failed / _skipped (the folders were not even made)"
        Check ($script:Attempts.Count -eq 0) "$n : no attempt counted against any file"
        Check ($script:SweepProblems.ContainsKey($case.Code) -and -not $script:SweepProblems.ContainsKey('server-unreachable')) "$n : coded problem $($case.Code), not server-unreachable"
        Check ($script:Fake.Seen.Count -eq 1 -and $script:Fake.Unexpected -eq 0) "$n : one request (the lookup) and nothing after it - no commit, no unbound filing"
        Check ($script:Fake.Seen[0] -ceq $(if ($case.Token) { "resolve up=$($case.Token) old=-" } else { 'resolve up=- old=-' })) "$n : the header is there if and only if a token is set"
        Check ((Get-LogCount 'refused the uploader token') -eq 1) "$n : one log line"
        $body = Get-HeartbeatBody $cfg $true ''
        Check (@($body.problems) -contains $case.Code -and -not (ConvertTo-Json $body -Depth 4 -Compress).Contains($TOK)) "$n : the heartbeat carries the code, never the token"
        Assert-NoSecret $n
    }

    Section 'watcher: a 401 on a commit after a successful lookup'
    Reset-Watcher
    $dir = New-Folder @('a.dcm', 'b.dcm', 'c.dcm', 'raw.dcm')
    $cfg = New-Cfg $dir $TOK
    $script:Fake.Enqueue('resolve', (New-ResolveReply 4))
    $script:Fake.Enqueue('commit', (New-Reply 201)); $script:Fake.Enqueue('commit', (New-Reply 401 $REJECTED))
    $result = Invoke-TwoSweeps $cfg
    Check ($result -ceq $TEXT_REJECTED) 'the result is the German text'
    Check ((Get-Names $dir '_uploaded') -eq 'a.dcm') 'what the platform accepted before the refusal is filed'
    Check ((Get-Names $dir) -eq 'b.dcm,c.dcm,raw.dcm') 'the refused file, the files after it and the Raw Data object stay where they are'
    Check ((Get-Names $dir '_failed') -eq '(none)' -and (Get-Names $dir '_skipped') -eq '(none)') 'nothing moved to _failed or _skipped'
    Check ($script:Attempts.Count -eq 0) 'no attempt counted'
    Check ($script:Fake.Seen.Count -eq 3 -and $script:Fake.Unexpected -eq 0) 'lookup, commit, commit - and no commit after the refusal'
    Check ($script:SweepProblems.ContainsKey('token-rejected')) 'coded problem token-rejected'
    Check ((Get-LogCount 'then the platform refused') -eq 1) 'the log says how many went before (a count, no names)'

    Section 'watcher: the next sweep after a refusal'
    $script:Fake.Enqueue('resolve', (New-ResolveReply 2)); Add-Commits $script:Fake 201, 201
    $result = Invoke-Sweep $cfg
    Check ($result -ceq 'uploaded 2 (2 bound), 0 already there, 0 failed, 1 non-image set aside') 'the platform takes the token: everything goes through'
    Check ((Get-Names $dir '_uploaded') -eq 'a.dcm,b.dcm,c.dcm' -and (Get-Names $dir '_skipped') -eq 'raw.dcm' -and (Get-Names $dir) -eq '') 'files filed, the Raw Data object set aside, the folder empty'
    Check (-not $script:SweepProblems.ContainsKey('token-rejected') -and $script:TokenLogged -eq '') 'the problem is gone, and a later refusal will be logged again'
    Assert-Header $script:Fake $TOK 'refusal and retry'
    Assert-NoSecret 'refusal and retry'

    Section 'watcher: a refusal that persists'
    Reset-Watcher
    $dir = New-Folder @('a.dcm', 'bad.dcm', 'raw.dcm'); $cfg = New-Cfg $dir $TOK
    Invoke-Sweep $cfg | Out-Null                              # marks the sizes
    for ($i = 1; $i -le 7; $i++) { $script:Fake.Enqueue('resolve', (New-Reply 401 $REJECTED)); Invoke-Sweep $cfg | Out-Null }
    Check ((Get-Names $dir) -eq 'a.dcm,bad.dcm,raw.dcm') 'seven refused sweeps later every file is still in the watch folder'
    Check ((Get-Names $dir '_failed') -eq '(none)' -and (Get-Names $dir '_skipped') -eq '(none)' -and (Get-Names $dir '_uploaded') -eq '(none)') 'and none was moved: not the unreadable file past its 5 attempts, not the Raw Data object'
    Check ($script:Fake.Seen.Count -eq 7 -and $script:Fake.Unexpected -eq 0) 'one request per sweep: no hot loop, no extra requests'
    Check ((Get-LogCount 'refused the uploader token') -eq 1) 'logged once, not at every retry'
    $script:Fake.Enqueue('resolve', (New-ResolveReply 1)); Add-Commits $script:Fake 201
    Invoke-Sweep $cfg | Out-Null
    Check ((Get-Names $dir '_uploaded') -eq 'a.dcm' -and (Get-Names $dir '_skipped') -eq 'raw.dcm' -and (Get-Names $dir '_failed') -eq 'bad.dcm' -and (Get-Names $dir) -eq '') 'once the platform takes the token the local decisions are carried out as well'

    Section 'watcher: only local decisions - nothing is asked of the platform'
    Reset-Watcher
    $dir = New-Folder @('raw.dcm', 'bad.dcm'); $cfg = New-Cfg $dir $TOK
    Invoke-TwoSweeps $cfg | Out-Null
    Check ($script:Fake.Seen.Count -eq 0) 'no request'
    Check ((Get-Names $dir '_skipped') -eq 'raw.dcm' -and (Get-Names $dir) -eq 'bad.dcm') 'the Raw Data object is set aside as before, the unreadable file waits'

    Section 'watcher: a 429 is still not a refusal (unchanged)'
    Reset-Watcher
    $dir = New-Folder @('a.dcm', 'b.dcm', 'raw.dcm'); $cfg = New-Cfg $dir $TOK
    $script:Fake.Enqueue('resolve', (New-Reply 429)); Add-Commits $script:Fake 201, 201
    $result = Invoke-TwoSweeps $cfg
    Check ((Get-Names $dir '_uploaded') -eq 'a.dcm,b.dcm' -and (Get-Names $dir '_skipped') -eq 'raw.dcm') 'filed without a visit, the Raw Data object set aside'
    Check ($script:SweepProblems.ContainsKey('rate-limited') -and (Get-TokenProblemIn $script:SweepProblems) -eq '') 'coded problem rate-limited, no token problem'
    Check ((Get-LogCount 'filing without a visit') -eq 1 -and $result -like 'uploaded 2 (0 bound)*') 'logged and summarised as before'

    Section 'watcher: other failures count attempts and give up after five (unchanged)'
    Reset-Watcher
    $dir = New-Folder @('a.dcm'); $cfg = New-Cfg $dir $TOK
    Invoke-Sweep $cfg | Out-Null
    for ($i = 1; $i -le 5; $i++) {
        $script:Fake.Enqueue('resolve', (New-ResolveReply 1)); $script:Fake.Enqueue('commit', (New-Reply 500 'boom'))
        Invoke-Sweep $cfg | Out-Null
        if ($i -lt 5) { Check ((Get-Names $dir) -eq 'a.dcm') "attempt $i : the file waits for the next sweep" }
    }
    Check ((Get-Names $dir '_failed') -eq 'a.dcm' -and $script:SweepProblems.ContainsKey('upload-failed')) 'after 5 attempts it goes to _failed'

    Section 'watcher: no token set - the requests are the anonymous ones of before'
    Reset-Watcher
    $dir = New-Folder @('a.dcm'); $cfg = New-Cfg $dir ''
    $script:Fake.Enqueue('resolve', (New-ResolveReply 1)); Add-Commits $script:Fake 201
    Invoke-TwoSweeps $cfg | Out-Null
    Check ((Get-Names $dir '_uploaded') -eq 'a.dcm') 'uploaded as ever'
    Assert-Header $script:Fake '-' 'anonymous'

    Section 'watcher: the heartbeat'
    Reset-Watcher
    $dir = New-Folder @(); $cfg = New-Cfg $dir $TOK
    $script:FakeHb.Enqueue('heartbeat', (New-Reply 401 $REQUIRED))
    $code = Send-Heartbeat $cfg
    Check ($code -eq 401 -and $script:HbProblem -eq 'token-required') 'a 401 comes back with its code'
    Check ($script:FakeHb.Seen[0] -ceq "heartbeat up=$TOK old=-") 'the heartbeat carries the header'
    $script:FakeHb.Enqueue('heartbeat', (New-Reply 401 $REQUIRED)); Send-Heartbeat $cfg | Out-Null
    Check ((Get-LogCount 'heartbeat: the platform refused the uploader token') -eq 1) 'the same refusal again is not logged again'
    $script:FakeHb.Enqueue('heartbeat', (New-Reply 401 $REJECTED)); Send-Heartbeat $cfg | Out-Null
    Check ((Get-LogCount 'heartbeat: the platform refused the uploader token') -eq 2 -and $script:HbProblem -eq 'token-rejected') 'a different refusal is'
    $script:FakeHb.Enqueue('heartbeat', (New-Reply 200 '{}')); $code = Send-Heartbeat $cfg
    Check ($code -eq 200 -and $script:HbProblem -eq '') 'accepted again: the problem is cleared'
    $script:FakeHb.Enqueue('heartbeat', (New-Reply 200 '{}')); Send-Heartbeat (New-Cfg $dir '') | Out-Null
    Check ($script:FakeHb.Seen[$script:FakeHb.Seen.Count - 1] -ceq 'heartbeat up=- old=-') 'no token set: no header'
    Assert-NoSecret 'heartbeat'
}

# ----------------------------------------------------------------------------
# the Optomed Bridge: real worklist fetches and upload rounds over a temporary Client folder
# ----------------------------------------------------------------------------
if ($Target -eq 'bridge') {
    function Read-DicomTags { param([string]$Path)          # stands in for the DICOM header reader
        $name = [IO.Path]::GetFileNameWithoutExtension($Path)
        [pscustomobject]@{ PatientID = $(if ($name -like 'nolabel*') { '' } else { 'L' + $name }); StudyDate = '20261012'
                           Laterality = 'R'; ImageLaterality = 'R'; SOPInstanceUID = '1.2.3'; TransferSyntax = '' }
    }
    function Reset-Bridge {
        Reset-Fakes
        $script:Problems = @{ worklist = @{}; upload = @{} }
        $script:TokenLogged = @{ worklist = ''; upload = '' }
    }
    function New-ClientRoot([string[]]$studyFiles) {
        $root = Join-Path $tmp ([guid]::NewGuid().ToString('N'))
        $study = Join-Path $root 'Studies/S1/DICOM'
        New-Item -ItemType Directory -Force $study | Out-Null
        New-Item -ItemType Directory -Force (Join-Path $root 'Worklist') | Out-Null
        foreach ($n in $studyFiles) { [IO.File]::WriteAllBytes((Join-Path $study $n), [byte[]](1..40)) }
        [IO.File]::WriteAllText($script:LastListPath, 'GOOD')      # what was served last
        $root
    }
    function New-BridgeCfg([string]$root, [string]$shared, [string]$uploader) {
        $c = Get-DefaultConfig
        $c.ClientRoot = $root; $c.BaseUrl = 'http://platform.test/LibreClinica'
        if ($shared) { $c.TokenProtected = Protect-Token $shared }
        if ($uploader) { $c.UploaderTokenProtected = Protect-Token $uploader }
        $c
    }
    function Get-FileCount([string]$dir, [string]$sub) {
        $p = Join-Path $dir $sub
        if (Test-Path -LiteralPath $p) { @(Get-ChildItem -LiteralPath $p -File).Count } else { 0 }
    }
    $DICOM = 'Studies/S1/DICOM'

    Section 'bridge: a 401 on the worklist keeps the last good worklist'
    foreach ($case in @(
        @{ Name = 'required (only the shared token set)'; Shared = $OLD; Up = '';   Body = $REQUIRED; Code = 'token-required'; Text = $TEXT_REQUIRED; Seen = "worklist.txt up=- old=$OLD" },
        @{ Name = 'rejected (both tokens set)';           Shared = $OLD; Up = $TOK; Body = $REJECTED; Code = 'token-rejected'; Text = $TEXT_REJECTED; Seen = "worklist.txt up=$TOK old=$OLD" },
        @{ Name = 'the old refusal of the shared token';  Shared = $OLD; Up = $TOK; Body = '{"message":"Invalid or missing X-MUW-Optomed-Token"}'; Code = 'token-rejected'; Text = $TEXT_REJECTED; Seen = "worklist.txt up=$TOK old=$OLD" },
        @{ Name = 'rejected (only the uploader token)';   Shared = '';   Up = $TOK; Body = $REJECTED; Code = 'token-rejected'; Text = $TEXT_REJECTED; Seen = "worklist.txt up=$TOK old=-" }
    )) {
        $n = $case.Name
        Reset-Bridge
        $root = New-ClientRoot @(); $cfg = New-BridgeCfg $root $case.Shared $case.Up
        $script:Fake.Enqueue('worklist.txt', (New-Reply 401 $case.Body))
        $result = Invoke-WorklistFetch $cfg
        Check ($result -ceq $case.Text) "$n : the result is the German text"
        Check ((Get-Content $script:LastListPath -Raw) -ceq 'GOOD') "$n : the last good worklist is kept"
        Check (@(Get-ChildItem -LiteralPath (Join-Path $root 'Worklist') -Force).Count -eq 0) "$n : nothing was dropped for the Client - no empty worklist, no .part file"
        Check ($script:Problems['worklist'].Count -eq 1 -and $script:Problems['worklist'].ContainsKey($case.Code)) "$n : exactly the coded problem $($case.Code)"
        Check ($script:Fake.Seen.Count -eq 1 -and $script:Fake.Seen[0] -ceq $case.Seen) "$n : one request, with the right headers"
        Check ((Get-LogCount 'refused the uploader token') -eq 1) "$n : one log line"
        Assert-NoSecret $n
    }

    Section 'bridge: the worklist when the platform takes the token'
    Reset-Bridge
    $root = New-ClientRoot @(); $cfg = New-BridgeCfg $root $OLD $TOK
    $list = "PID1`r`nA`r`n`r`nPID2`r`nB"
    $script:TokenLogged['worklist'] = 'token-rejected'
    $script:Fake.Enqueue('worklist.txt', (New-Reply 200 $list))
    $result = Invoke-WorklistFetch $cfg
    Check ($result -ceq 'dropped 2') 'the list is dropped'
    Check ((Get-Content (Join-Path $root 'Worklist/worklist_optomed_lumo.txt') -Raw) -ceq $list -and (Get-Content $script:LastListPath -Raw) -ceq $list) 'for the Client, and kept as the last one served'
    Check ($script:Fake.Seen[0] -ceq "worklist.txt up=$TOK old=$OLD") 'both headers are sent while both tokens are set'
    Check ($script:TokenLogged['worklist'] -eq '') 'a later refusal will be logged again'

    Section 'bridge: only one of the tokens is enough to ask for the worklist'
    foreach ($case in @(
        @{ Name = 'uploader token only'; Shared = '';   Up = $TOK; Seen = "worklist.txt up=$TOK old=-" },
        @{ Name = 'shared token only';   Shared = $OLD; Up = '';   Seen = "worklist.txt up=- old=$OLD" }
    )) {
        Reset-Bridge
        $root = New-ClientRoot @(); $cfg = New-BridgeCfg $root $case.Shared $case.Up
        $script:Fake.Enqueue('worklist.txt', (New-Reply 200 'X'))
        $result = Invoke-WorklistFetch $cfg
        Check ($result -ceq 'dropped 1' -and $script:Fake.Seen[0] -ceq $case.Seen) "$($case.Name) : fetched, and only the token that is set is sent"
    }
    Reset-Bridge
    $root = New-ClientRoot @(); $cfg = New-BridgeCfg $root '' ''
    $result = Invoke-WorklistFetch $cfg
    Check ($result -ceq 'no token' -and $script:Fake.Seen.Count -eq 0 -and $script:Problems['worklist'].ContainsKey('no-token')) 'no token at all: nothing is asked, as before'

    Section 'bridge: other worklist answers keep their codes (unchanged)'
    foreach ($case in @(@{ Status = 404; Code = 'worklist-off' }, @{ Status = 500; Code = 'worklist-failed' }, @{ Status = 403; Code = 'worklist-failed' })) {
        Reset-Bridge
        $root = New-ClientRoot @(); $cfg = New-BridgeCfg $root $OLD $TOK
        $script:Fake.Enqueue('worklist.txt', (New-Reply $case.Status 'x'))
        $result = Invoke-WorklistFetch $cfg
        Check ($result -ceq "HTTP $($case.Status)" -and $script:Problems['worklist'].Count -eq 1 -and $script:Problems['worklist'].ContainsKey($case.Code)) "HTTP $($case.Status) : $($case.Code)"
    }

    Section 'bridge: a 401 on the lookup ends the upload round with every study file in place'
    foreach ($case in @(
        @{ Name = 'required (no uploader token)'; Up = '';   Body = $REQUIRED; Code = 'token-required'; Text = $TEXT_REQUIRED; Seen = 'resolve up=- old=-' },
        @{ Name = 'rejected';                     Up = $TOK; Body = $REJECTED; Code = 'token-rejected'; Text = $TEXT_REJECTED; Seen = "resolve up=$TOK old=-" },
        @{ Name = 'other text (a proxy)';         Up = $TOK; Body = '<html>401</html>'; Code = 'token-rejected'; Text = $TEXT_REJECTED; Seen = "resolve up=$TOK old=-" }
    )) {
        $n = $case.Name
        Reset-Bridge
        $root = New-ClientRoot @('f1.dcm', 'f2.dcm', 'f3.dcm'); $cfg = New-BridgeCfg $root $OLD $case.Up
        $script:Fake.Enqueue('resolve', (New-Reply 401 $case.Body))
        $result = Invoke-StudyUpload $cfg
        Check ($result -ceq $case.Text) "$n : the result is the German text"
        Check ((Get-FileCount $root $DICOM) -eq 3 -and (Get-FileCount $root "$DICOM/_uploaded") -eq 0 -and -not (Test-Path (Join-Path $root "$DICOM/_uploaded"))) "$n : all three files are still in the Client folder, nothing moved to _uploaded"
        Check ($script:Fake.Seen.Count -eq 1 -and $script:Fake.Unexpected -eq 0 -and $script:Fake.Seen[0] -ceq $case.Seen) "$n : one request - the lookup - with the right header, no commit after it"
        Check ($script:Problems['upload'].Count -eq 1 -and $script:Problems['upload'].ContainsKey($case.Code)) "$n : exactly the coded problem $($case.Code)"
        Check ((Get-LogCount 'refused the uploader token') -eq 1) "$n : one log line"
        Assert-NoSecret $n
    }

    Section 'bridge: a 401 on a commit after a successful lookup'
    Reset-Bridge
    $root = New-ClientRoot @('f1.dcm', 'f2.dcm', 'f3.dcm'); $cfg = New-BridgeCfg $root '' $TOK
    $script:Fake.Enqueue('resolve', (New-ResolveReply 1)); $script:Fake.Enqueue('resolve', (New-ResolveReply 1))
    $script:Fake.Enqueue('commit', (New-Reply 201)); $script:Fake.Enqueue('commit', (New-Reply 401 $REJECTED))
    $result = Invoke-StudyUpload $cfg
    Check ($result -ceq $TEXT_REJECTED) 'the result is the German text'
    Check ((Get-FileCount $root "$DICOM/_uploaded") -eq 1 -and (Get-FileCount $root $DICOM) -eq 2) 'the file taken before the refusal is filed; the refused one and the third stay'
    Check ($script:Fake.Seen.Count -eq 4 -and $script:Fake.Unexpected -eq 0) 'lookup, commit, lookup, commit - nothing after the refusal'
    Check ((Get-LogCount 'then the platform refused') -eq 1) 'the log says how many went before'
    Assert-Header $script:Fake $TOK 'upload round'
    Assert-NoSecret 'upload round'

    Section 'bridge: the next round after a refusal'
    $script:Fake.Enqueue('resolve', (New-ResolveReply 1)); $script:Fake.Enqueue('resolve', (New-ResolveReply 1))
    $script:Fake.Enqueue('commit', (New-Reply 201)); $script:Fake.Enqueue('commit', (New-Reply 201))
    $result = Invoke-StudyUpload $cfg
    Check ($result -ceq 'uploaded 2 (2 bound), 0 already there, 0 failed' -and (Get-FileCount $root $DICOM) -eq 0 -and (Get-FileCount $root "$DICOM/_uploaded") -eq 3) 'the platform takes the token: the rest goes through'
    Check ($script:TokenLogged['upload'] -eq '' -and $script:Problems['upload'].Count -eq 0) 'the problem is gone, and a later refusal will be logged again'

    Section 'bridge: a 401 on a commit that needed no lookup'
    Reset-Bridge
    $root = New-ClientRoot @('nolabel1.dcm', 'nolabel2.dcm'); $cfg = New-BridgeCfg $root '' $TOK
    $script:Fake.Enqueue('commit', (New-Reply 401 $REJECTED))
    $result = Invoke-StudyUpload $cfg
    Check ((Get-FileCount $root $DICOM) -eq 2 -and $script:Fake.Seen.Count -eq 1 -and $script:Fake.Seen[0] -ceq "commit up=$TOK old=-" -and $result -ceq $TEXT_REJECTED) 'both files stay, one request, German text'

    Section 'bridge: a 429 is still not a refusal (unchanged)'
    Reset-Bridge
    $root = New-ClientRoot @('f1.dcm', 'f2.dcm'); $cfg = New-BridgeCfg $root '' $TOK
    $script:Fake.Enqueue('resolve', (New-Reply 429)); $script:Fake.Enqueue('resolve', (New-Reply 429))
    $script:Fake.Enqueue('commit', (New-Reply 201)); $script:Fake.Enqueue('commit', (New-Reply 201))
    $result = Invoke-StudyUpload $cfg
    Check ((Get-FileCount $root "$DICOM/_uploaded") -eq 2 -and $result -like 'uploaded 2 (0 bound)*') 'filed without a visit, as before'
    Check ($script:Problems['upload'].ContainsKey('rate-limited') -and (Get-TokenProblemIn $script:Problems['upload']) -eq '') 'coded problem rate-limited, no token problem'

    Section 'bridge: a failed commit is a failure of the file, not a refusal (unchanged)'
    Reset-Bridge
    $root = New-ClientRoot @('f1.dcm'); $cfg = New-BridgeCfg $root '' $TOK
    $script:Fake.Enqueue('resolve', (New-ResolveReply 1)); $script:Fake.Enqueue('commit', (New-Reply 500 'boom'))
    $result = Invoke-StudyUpload $cfg
    Check ((Get-FileCount $root $DICOM) -eq 1 -and $script:Problems['upload'].ContainsKey('upload-failed') -and $result -like '*1 failed') 'the file waits, coded problem upload-failed'

    Section 'bridge: no uploader token set - the requests are the anonymous ones of before'
    Reset-Bridge
    $root = New-ClientRoot @('f1.dcm'); $cfg = New-BridgeCfg $root '' ''
    $script:Fake.Enqueue('resolve', (New-ResolveReply 1)); $script:Fake.Enqueue('commit', (New-Reply 201))
    Invoke-StudyUpload $cfg | Out-Null
    Check ((Get-FileCount $root "$DICOM/_uploaded") -eq 1) 'uploaded as ever'
    Assert-Header $script:Fake '-' 'anonymous'

    Section 'bridge: the heartbeat'
    Reset-Bridge
    $root = New-ClientRoot @(); $cfg = New-BridgeCfg $root $OLD $TOK
    $script:FakeHb.Enqueue('heartbeat', (New-Reply 401 $REQUIRED))
    $code = Send-Heartbeat $cfg
    Check ($code -eq 401 -and $script:HbProblem -eq 'token-required') 'a 401 comes back with its code'
    Check ($script:FakeHb.Seen[0] -ceq "heartbeat up=$TOK old=-") 'the heartbeat carries the uploader header (and not the shared worklist token)'
    $script:FakeHb.Enqueue('heartbeat', (New-Reply 401 $REQUIRED)); Send-Heartbeat $cfg | Out-Null
    Check ((Get-LogCount 'heartbeat: the platform refused the uploader token') -eq 1) 'the same refusal again is not logged again'
    $script:FakeHb.Enqueue('heartbeat', (New-Reply 401 $REJECTED)); Send-Heartbeat $cfg | Out-Null
    Check ((Get-LogCount 'heartbeat: the platform refused the uploader token') -eq 2 -and $script:HbProblem -eq 'token-rejected') 'a different refusal is'
    $script:FakeHb.Enqueue('heartbeat', (New-Reply 200 '{}')); $code = Send-Heartbeat $cfg
    Check ($code -eq 200 -and $script:HbProblem -eq '') 'accepted again: the problem is cleared'
    $json = ConvertTo-Json (Get-HeartbeatBody $cfg $true '') -Depth 4 -Compress
    Check (-not $json.Contains($TOK) -and -not $json.Contains($OLD)) 'the heartbeat body holds no token'
    Assert-NoSecret 'heartbeat'
}
} finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host ("{0} check(s) passed, {1} failed" -f $script:Pass, $script:Fail)
exit $(if ($script:Fail -eq 0) { 0 } else { 1 })
