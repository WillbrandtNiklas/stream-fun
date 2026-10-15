# SheesheoHak Watchdog: holt die neueste Version, startet den Server (unsichtbar),
# startet den Cloudflare-Tunnel, meldet den Link und startet alles neu, wenn etwas beendet wird.
# Laeuft dauerhaft im Hintergrund (ueber run.vbs + geplanten Task beim Login).

$ErrorActionPreference = 'SilentlyContinue'

# ============== KONFIGURATION (anpassen) ==============
$Repo       = 'WillbrandtNiklas/stream-fun'     # owner/repo deines GitHub-Repos
$WebhookUrl = ''                                # optional: Discord-Webhook-URL, dann bekommst du den Link automatisch
$Port       = 8080
# ======================================================

$Dir      = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Dir
$Jar      = Join-Path $Dir 'remote-fun.jar'
$VerFile  = Join-Path $Dir 'version.txt'
$LinkFile = Join-Path $Dir 'link.txt'
$Cf       = Join-Path $Dir 'cloudflared.exe'
$Sounds   = Join-Path $Dir 'sounds'

function Log($m) {
    try { Add-Content (Join-Path $Dir 'agent.log') ((Get-Date -Format s) + ' ' + $m) } catch {}
}

function Update-Jar {
    try {
        $rel = Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest" -Headers @{ 'User-Agent' = 'stream-fun' }
        $tag = $rel.tag_name
        $cur = ''
        if (Test-Path $VerFile) { $cur = (Get-Content $VerFile -Raw).Trim() }
        if ($tag -and $tag -ne $cur) {
            $asset = $rel.assets | Where-Object { $_.name -eq 'remote-fun.jar' } | Select-Object -First 1
            if ($asset) {
                Log "Lade $tag ..."
                $tmp = "$Jar.new"
                Invoke-WebRequest $asset.browser_download_url -OutFile $tmp -Headers @{ 'User-Agent' = 'stream-fun' }
                Move-Item $tmp $Jar -Force
                Set-Content $VerFile $tag
                Log "Aktualisiert auf $tag"
            }
        }
    } catch { Log "Update-Fehler: $_" }
}

function Start-Tunnel {
    $errLog = Join-Path $Dir 'cf.err'
    $outLog = Join-Path $Dir 'cf.out'
    Remove-Item $errLog, $outLog -ErrorAction SilentlyContinue
    $p = Start-Process -FilePath $Cf `
        -ArgumentList @('tunnel', '--url', "http://localhost:$Port", '--no-autoupdate') `
        -WindowStyle Hidden -PassThru -RedirectStandardError $errLog -RedirectStandardOutput $outLog
    $url = $null
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 1000
        $txt = Get-Content $errLog, $outLog -Raw -ErrorAction SilentlyContinue
        if ($txt -match 'https://[a-z0-9-]+\.trycloudflare\.com') { $url = $Matches[0]; break }
    }
    if ($url) {
        Set-Content $LinkFile $url
        Log "Link: $url"
        if ($WebhookUrl) {
            try {
                $body = @{ content = "SheesheoHak ist online: $url" } | ConvertTo-Json
                Invoke-RestMethod $WebhookUrl -Method Post -ContentType 'application/json' -Body $body | Out-Null
            } catch { Log "Webhook-Fehler: $_" }
        }
    } else {
        Log "Kein Cloudflare-Link gefunden (cf.err pruefen)"
    }
    return $p
}

# cloudflared bei Bedarf herunterladen
if (-not (Test-Path $Cf)) {
    try {
        Log "Lade cloudflared ..."
        Invoke-WebRequest 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe' -OutFile $Cf
    } catch { Log "cloudflared-Download fehlgeschlagen: $_" }
}

Log "Watchdog gestartet"
while ($true) {
    Update-Jar
    if (-not (Test-Path $Jar)) { Log "Noch keine remote-fun.jar vorhanden, warte 20s"; Start-Sleep 20; continue }

    $cf = Start-Tunnel
    $java = Start-Process -FilePath 'javaw' `
        -ArgumentList @("-Dsounds.dir=$Sounds", '-jar', $Jar) `
        -WorkingDirectory $Dir -WindowStyle Hidden -PassThru
    Log "Server gestartet (PID $($java.Id))"

    # Ueberwachen: Tunnel neu, falls er stirbt; Schleife verlassen, wenn der Server endet
    while (-not $java.HasExited) {
        Start-Sleep 3
        if ($cf -and $cf.HasExited) { Log "Tunnel weg -> neu"; $cf = Start-Tunnel }
    }

    Log "Server beendet -> Neustart in 2s"
    try { if ($cf) { Stop-Process -Id $cf.Id -Force } } catch {}
    Start-Sleep 2
}
