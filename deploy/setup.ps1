# Ein-Befehl-Einrichtung fuer SheesheoHak auf dem Ziel-PC.
# Laedt alle Deploy-Skripte frisch von GitHub, richtet den Autostart ein
# (beim Login + Minuten-Check + Auto-Update, wie install.ps1) und startet sofort
# die gebrandete SheesheoHak.exe. In einer Administrator-PowerShell ausfuehren.
param([string]$Dir = 'C:\stream-fun')

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

# Der geplante Task braucht Adminrechte -> hier klar abbrechen statt still zu scheitern
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
         ).IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)
if (-not $admin) {
    Write-Warning 'Bitte diese PowerShell ALS ADMINISTRATOR oeffnen und den Befehl erneut ausfuehren.'
    return
}

$base = 'https://raw.githubusercontent.com/WillbrandtNiklas/stream-fun/master/deploy'
New-Item -ItemType Directory -Force -Path $Dir | Out-Null

Write-Host 'Lade Deploy-Skripte ...'
foreach ($f in 'agent.ps1','run.vbs','ensure.ps1','ensure.vbs','install.ps1','uninstall.ps1') {
    Invoke-WebRequest "$base/$f" -OutFile (Join-Path $Dir $f) -Headers @{ 'User-Agent' = 'sf' }
}
Get-ChildItem $Dir -File | Unblock-File

# Alten Watchdog/Pruefer/Server/Tunnel beenden, damit der neue uebernimmt
Write-Host 'Beende evtl. alte Instanzen ...'
Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'agent\.ps1|ensure\.ps1|remote-fun\.jar' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Get-Process cloudflared, SheesheoHak -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

# Version zuruecksetzen -> die neue SheesheoHak.zip wird beim Start geladen
Remove-Item (Join-Path $Dir 'version.txt') -Force -ErrorAction SilentlyContinue

# Autostart einrichten + sofort starten (geplanter Task 'SheesheoHak')
Write-Host 'Richte Autostart ein und starte ...'
& (Join-Path $Dir 'install.ps1')

Write-Host ''
Write-Host 'Fertig. In etwa 1 Minute laeuft SheesheoHak (Task-Manager zeigt SheesheoHak statt OpenJDK).'
Write-Host "Der Cloudflare-Link steht dann in $Dir\link.txt und unten im Web-UI."
