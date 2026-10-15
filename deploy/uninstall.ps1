# Entfernt StreamFun wieder. ALS ADMINISTRATOR ausfuehren.
$ErrorActionPreference = 'SilentlyContinue'

Unregister-ScheduledTask -TaskName 'StreamFun' -Confirm:$false

# laufenden Watchdog + Server + Tunnel beenden
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
    Where-Object { $_.CommandLine -like '*agent.ps1*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" |
    Where-Object { $_.CommandLine -like '*remote-fun.jar*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Get-Process cloudflared -ErrorAction SilentlyContinue | Stop-Process -Force

Write-Host "StreamFun entfernt (Dateien im Ordner bleiben; Ordner kannst du jetzt loeschen)."
