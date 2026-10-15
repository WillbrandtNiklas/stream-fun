# Einmalige Einrichtung auf dem Ziel-PC. ALS ADMINISTRATOR ausfuehren.
# Legt einen geplanten Task an, der beim Login unsichtbar den Watchdog startet,
# ihn bei Absturz neu startet, und startet ihn sofort.

$ErrorActionPreference = 'Stop'
$Dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$vbs = Join-Path $Dir 'run.vbs'
if (-not (Test-Path $vbs)) { throw "run.vbs nicht gefunden in $Dir" }

$action    = New-ScheduledTaskAction -Execute 'wscript.exe' -Argument ('"' + $vbs + '"') -WorkingDirectory $Dir
$trigger   = New-ScheduledTaskTrigger -AtLogOn
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Highest
$settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
                -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
                -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew

# alten Task-Namen (falls aus früherer Version vorhanden) entfernen
Unregister-ScheduledTask -TaskName 'StreamFun' -Confirm:$false -ErrorAction SilentlyContinue

Register-ScheduledTask -TaskName 'SheesheoHak' -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName 'SheesheoHak'

Write-Host "SheesheoHak installiert und gestartet."
Write-Host "Der aktuelle Link steht nach ein paar Sekunden in link.txt (und im Discord-Webhook, falls gesetzt)."
