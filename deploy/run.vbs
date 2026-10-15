' Startet den Watchdog komplett unsichtbar (kein PowerShell-Fenster blitzt auf).
Set sh = CreateObject("WScript.Shell")
dir = Left(WScript.ScriptFullName, InStrRev(WScript.ScriptFullName, "\"))
sh.Run "powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & dir & "agent.ps1""", 0, False
