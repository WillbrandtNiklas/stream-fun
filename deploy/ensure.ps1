# Prueft, ob der Watchdog (agent.ps1) laeuft, und startet ihn sonst.
# Wird vom geplanten Task beim Login und danach jede Minute aufgerufen ->
# wird der Watchdog im Task-Manager beendet, ist er spaetestens nach ~1 Minute wieder da.
$ErrorActionPreference = 'SilentlyContinue'
$Dir = Split-Path -Parent $MyInvocation.MyCommand.Path

$running = Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
    Where-Object { $_.CommandLine -like '*agent.ps1*' }

if (-not $running) {
    $vbs = Join-Path $Dir 'run.vbs'
    Start-Process wscript.exe -ArgumentList ('"' + $vbs + '"') -WorkingDirectory $Dir
}
