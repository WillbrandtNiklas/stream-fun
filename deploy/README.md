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

2. GitHub baut automatisch (Tab **Actions**) und legt unter **Releases** die `SheesheoHak.zip`
   (gebrandete `SheesheoHak.exe` mit eigenem Icon, inkl. mitgeliefertem Java) sowie die `remote-fun.jar` ab.
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
  der Server beendet sich, der Watchdog lädt die neueste `SheesheoHak.zip`, entpackt sie und startet neu (paar Sekunden offline).
- **Hinweis für bestehende Installationen**: Da der Watchdog jetzt die `.exe` statt der `.jar` startet, einmalig
  die neue `agent.ps1` in den Ziel-Ordner kopieren (die Skripte aktualisieren sich nicht von selbst).

## Wieder entfernen (nach der Challenge)

PowerShell **als Administrator**:
```powershell
cd C:\stream-fun
.\uninstall.ps1
```
Danach kannst du den Ordner löschen.

## Hinweise

- Java muss auf dem Ziel-PC **nicht** mehr installiert sein – die `SheesheoHak.exe` bringt ihre eigene Java-Laufzeit mit.
  (Für das lokale Entwickeln mit `mvn spring-boot:run` wird weiterhin Java 21 benötigt.) `cloudflared` lädt der Watchdog selbst herunter.
- Im Task-Manager erscheint der Server als **SheesheoHak** (eigenes Icon), nicht als generisches `javaw`/OpenJDK.
- Der Link gibt **vollen Zugriff** auf den PC – nicht im Stream zeigen.
- Kein bewusst „unzerstörbarer" Schutz: Der Watchdog startet sich nach Beenden neu, aber als Admin
  kannst du mit `uninstall.ps1` jederzeit alles sauber stoppen und entfernen.
