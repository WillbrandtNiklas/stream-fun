# SheesheoHak auf dem Ziel-PC einrichten

Läuft beim Hochfahren automatisch und unsichtbar im Hintergrund, holt sich immer die
neueste Version von GitHub, erzeugt einen Cloudflare-Link und startet sich bei Absturz/Beenden neu.

## Einmal: GitHub einrichten (auf deinem Entwickler-PC)

1. Repo anlegen und hochladen (GitHub-Account nötig):
   ```bash
   git init
   git add .
   git commit -m "Stream-Fun"
   gh repo create stream-fun --public --source=. --push
   ```
   (oder Repo im Browser anlegen und mit `git remote add origin ...` + `git push -u origin main` hochladen)

2. GitHub baut automatisch (Tab **Actions**) und legt unter **Releases** die `remote-fun.jar` ab.
   Jeder weitere `git push` auf `main` erzeugt automatisch ein neues Release = neue Version.

## Einmal: Ziel-PC einrichten

1. Ordner anlegen, z. B. `C:\stream-fun`, und den Inhalt des `deploy`-Ordners dort hineinkopieren
   (den **gesamten** Inhalt: `agent.ps1`, `run.vbs`, `ensure.ps1`, `ensure.vbs`, `install.ps1`, `uninstall.ps1`).

2. `agent.ps1` öffnen und oben eintragen:
   - `$Repo` = dein `github-nutzer/stream-fun`
   - `$WebhookUrl` = optional eine Discord-Webhook-URL, dann bekommst du den Link bei jedem Start automatisch dorthin geschickt.
     (Discord → Servereinstellungen → Integrationen → Webhooks → Neuer Webhook → URL kopieren.)

3. PowerShell **als Administrator** öffnen und ausführen:
   ```powershell
   cd C:\stream-fun
   .\install.ps1
   ```

Fertig. Beim nächsten Login (und sofort jetzt) startet alles unsichtbar.
Den aktuellen Link findest du in `C:\stream-fun\link.txt`, im Discord-Webhook (falls gesetzt),
und unten auf der Weboberfläche selbst.

## Updaten

- **Automatisch**: Bei jedem Neustart holt der Watchdog die neueste Release-Version.
- **Sofort aus der Ferne**: Auf der Weboberfläche unten **„Auf neueste Version aktualisieren"** drücken –
  der Server beendet sich, der Watchdog lädt die neueste `remote-fun.jar` und startet neu (paar Sekunden offline).

## Wieder entfernen (nach der Challenge)

PowerShell **als Administrator**:
```powershell
cd C:\stream-fun
.\uninstall.ps1
```
Danach kannst du den Ordner löschen.

## Hinweise

- Java 21 muss auf dem Ziel-PC installiert sein (`javaw` im PATH). `cloudflared` lädt der Watchdog selbst herunter.
- Der Link gibt **vollen Zugriff** auf den PC – nicht im Stream zeigen.
- Kein bewusst „unzerstörbarer" Schutz: Der Watchdog startet sich nach Beenden neu, aber als Admin
  kannst du mit `uninstall.ps1` jederzeit alles sauber stoppen und entfernen.
