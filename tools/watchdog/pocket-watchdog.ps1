# Pocket Cloud watchdog: checks the phone's gateway and shows a Windows notification when it has been
# down for more than 10 minutes (then once an hour while it stays down, and once when it is back).
#
# Run every 5 minutes by a Task Scheduler job (see register-watchdog.ps1), or by hand:
#     powershell -ExecutionPolicy Bypass -File pocket-watchdog.ps1
param(
    [string]$Url = "http://pocket:9000/_pocket/health",
    [int]$DownAfterMinutes = 10,
    [int]$RepeatEveryMinutes = 60
)

$stateDir = Join-Path $env:LOCALAPPDATA "PocketCloud"
$stateFile = Join-Path $stateDir "watchdog.json"
New-Item -ItemType Directory -Force $stateDir | Out-Null
$state = @{ firstFailure = $null; lastNotified = $null; wasDown = $false }
if (Test-Path $stateFile) {
    try { $saved = Get-Content $stateFile -Raw | ConvertFrom-Json; foreach ($p in $saved.PSObject.Properties) { $state[$p.Name] = $p.Value } } catch {}
}

function Show-Toast([string]$title, [string]$text) {
    try {
        [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
        [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
        $xml = New-Object Windows.Data.Xml.Dom.XmlDocument
        $esc = [System.Security.SecurityElement]::Escape
        $xml.LoadXml("<toast><visual><binding template='ToastGeneric'><text>$($esc.Invoke($title))</text><text>$($esc.Invoke($text))</text></binding></visual></toast>")
        $appId = "{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\WindowsPowerShell\v1.0\powershell.exe"
        [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($appId).Show((New-Object Windows.UI.Notifications.ToastNotification $xml))
    } catch {
        # no toast support (e.g. an old PowerShell host): fall back to a message box
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.MessageBox]::Show($text, $title) | Out-Null
    }
}

$now = Get-Date
$ok = $false
try {
    $r = Invoke-WebRequest -Uri $Url -TimeoutSec 10 -UseBasicParsing
    $ok = ($r.StatusCode -eq 200) -and (($r.Content | ConvertFrom-Json).ok -eq $true)
} catch { $ok = $false }

if ($ok) {
    if ($state.wasDown) { Show-Toast "Pocket Cloud is back" "The phone answers again at $Url." }
    $state = @{ firstFailure = $null; lastNotified = $null; wasDown = $false }
} else {
    if (-not $state.firstFailure) { $state.firstFailure = $now.ToString("o") }
    $downFor = $now - [datetime]::Parse($state.firstFailure)
    if ($downFor.TotalMinutes -ge $DownAfterMinutes) {
        $due = (-not $state.lastNotified) -or (($now - [datetime]::Parse($state.lastNotified)).TotalMinutes -ge $RepeatEveryMinutes)
        if ($due) {
            Show-Toast "Pocket Cloud is down" ("No answer from the phone for {0} minutes ({1}). Is it on, charged and on Tailscale?" -f [int]$downFor.TotalMinutes, $Url)
            $state.lastNotified = $now.ToString("o")
            $state.wasDown = $true
        }
    }
}
$state | ConvertTo-Json | Set-Content -Encoding utf8 $stateFile
