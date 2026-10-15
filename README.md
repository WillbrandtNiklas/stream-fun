# Stream-Fernbedienung

## Starten (Server-PC, Windows, Java 21 + Maven)
    cd server
    mvn spring-boot:run
Oberfläche lokal: http://localhost:8080 (das Angular-Frontend ist schon gebaut und liegt in server/src/main/resources/static).

## Für den Freund übers Internet (mit HTTPS, nötig fürs Mikro)
    winget install Cloudflare.cloudflared
    cloudflared tunnel --url http://localhost:8080
Die ausgegebene https://....trycloudflare.com-Adresse an den Freund schicken. Kein Port-Forwarding nötig.
Hinweis: Wer die Adresse kennt, hat vollen Zugriff, also nicht im Stream zeigen.

## YouTube
Über das Suchfeld Videos suchen (kein API-Key nötig, die Suche liest die YouTube-Ergebnisseite aus). Ein Treffer lässt sich auswählen und dann abspielen:
- **Mit Bild**: Vollbild auf **allen Monitoren** (Ton nur auf dem Hauptmonitor), ohne Bedienelemente, Mauszeiger ausgeblendet. Klicks und Tasten in der Seite werden abgefangen, bei Pause wird sofort weitergespielt, und ein geschlossenes Fenster startet sofort neu.
- **Nur Ton**: komplett verstecktes Fenster (per Windows-API ausgeblendet) – unsichtbar und **kein Taskleisten-Eintrag**, nur Audio.
- **Stop**: beendet die Wiedergabe von der Fernbedienung aus.
- Ist das Video zu Ende, schließen sich die Fenster automatisch.

Braucht Chrome oder Edge auf dem Server-PC (Edge ist auf Windows 11 vorinstalliert). Nicht einbettbare Videos lassen sich so nicht abspielen. OS-Tastenkombinationen (Alt+F4, Win-Taste, Strg+Alt+Entf, Task-Manager) kann der Server nicht blockieren; der automatische Neustart des Fensters umgeht das weitgehend, bis „Stop" gedrückt wird.

## Frontend ändern
    cd web
    npm install
    npm run build   # schreibt direkt nach server/src/main/resources/static
