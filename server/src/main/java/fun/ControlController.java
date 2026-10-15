package fun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ControlController {
    private final Robot robot;
    private final ScheduledExecutorService ex = Executors.newScheduledThreadPool(2);
    private ScheduledFuture<?> freeze;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper json = new ObjectMapper();

    @Value("${server.port:8080}")
    private int port;

    // Wiedergabe-Status. generation steigt bei jedem Start/Stop, damit alte Watchdogs erkennen,
    // dass sie zu einer abgelaufenen Wiedergabe gehören und nichts neu starten.
    private final List<Process> players = new CopyOnWriteArrayList<>();
    private volatile Process audioProc; // das versteckte Nur-Ton-Fenster
    private volatile int generation;
    private volatile boolean active;
    private volatile String curId;
    private volatile boolean curVideo;
    private volatile long lastLaunch;
    private volatile int rapidFails;

    public ControlController() throws AWTException {
        this.robot = new Robot();
    }

    /** Alle Monitore als ein PNG. */
    @GetMapping(value = "/screenshot", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] screenshot() throws IOException {
        Rectangle all = new Rectangle();
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            all = all.union(d.getDefaultConfiguration().getBounds());
        }
        BufferedImage img = robot.createScreenCapture(all);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Strg+W im aktiven Fenster. */
    @PostMapping("/close-tab")
    public synchronized void closeTab() {
        // kleine Pausen, sonst verschlucken Browser/Spiele die synthetischen Tasten
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.delay(50);
        robot.keyPress(KeyEvent.VK_W);
        robot.delay(50);
        robot.keyRelease(KeyEvent.VK_W);
        robot.delay(20);
        robot.keyRelease(KeyEvent.VK_CONTROL);
    }

    /** Maus festhalten; seconds=0 heißt bis zum Freigeben. */
    @PostMapping("/mouse/freeze")
    public synchronized void freeze(@RequestParam(defaultValue = "10") int seconds) {
        unfreeze();
        Point p = MouseInfo.getPointerInfo().getLocation();
        ScheduledFuture<?> f = ex.scheduleAtFixedRate(() -> robot.mouseMove(p.x, p.y), 0, 5, TimeUnit.MILLISECONDS);
        freeze = f;
        if (seconds > 0) {
            ex.schedule(() -> { synchronized (this) { if (freeze == f) unfreeze(); } }, seconds, TimeUnit.SECONDS);
        }
    }

    @PostMapping("/mouse/unfreeze")
    public synchronized void unfreeze() {
        if (freeze != null) { freeze.cancel(false); freeze = null; }
    }

    /** Aktuell installierte Version (vom Updater in version.txt geschrieben). */
    @GetMapping("/version")
    public String version() {
        return readOr("version.txt", "dev");
    }

    /** Aktueller Cloudflare-Link (vom Watchdog in link.txt geschrieben). */
    @GetMapping("/link")
    public String link() {
        return readOr("link.txt", "");
    }

    /**
     * Beendet den Server, damit der Watchdog die neueste Version holt und neu startet.
     * (Die laufende .jar kann sich unter Windows nicht selbst überschreiben.)
     */
    @PostMapping("/update")
    public void update() {
        ex.schedule(() -> System.exit(0), 600, TimeUnit.MILLISECONDS);
    }

    private static String readOr(String file, String fallback) {
        try {
            Path p = Path.of(file).toAbsolutePath();
            if (Files.isRegularFile(p)) return Files.readString(p).trim();
        } catch (Exception ignored) { }
        return fallback;
    }

    /** YouTube-Suche ohne API-Key: Ergebnisseite holen und ytInitialData auslesen. */
    @GetMapping("/youtube/search")
    public List<Map<String, String>> youtubeSearch(@RequestParam String q) throws Exception {
        String url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept-Language", "de,en;q=0.8")
                .header("Cookie", "CONSENT=YES+1") // überspringt die EU-Consent-Seite
                .build();
        String body = http.send(req, HttpResponse.BodyHandlers.ofString()).body();

        String marker = "ytInitialData = ";
        int i = body.indexOf("var " + marker);
        if (i >= 0) i += 4; else i = body.indexOf(marker);
        if (i < 0) return List.of();
        String objText = extractJsonObject(body, i + marker.length());
        if (objText == null) return List.of();

        List<Map<String, String>> results = new ArrayList<>();
        JsonNode root = json.readTree(objText);
        for (JsonNode vr : root.findValues("videoRenderer")) {
            String id = vr.path("videoId").asText(null);
            if (id == null) continue;
            String title = text(vr.path("title"));
            String channel = text(vr.path("ownerText"));
            if (channel.isEmpty()) channel = text(vr.path("longBylineText"));
            String length = vr.path("lengthText").path("simpleText").asText("");
            results.add(Map.of(
                    "id", id,
                    "title", title,
                    "channel", channel,
                    "length", length,
                    "thumb", "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg"));
            if (results.size() >= 18) break;
        }
        return results;
    }

    /**
     * Spielt das Video auf dem Server-PC ab.
     * video=true: Vollbild auf allen Monitoren, ohne Bedienelemente; Klicks/Tasten in der Seite werden
     *             abgefangen, bei Pause wird sofort weitergespielt, und ein geschlossenes Fenster startet neu.
     * video=false: komplett verstecktes Fenster (kein Taskleisten-Eintrag), nur Ton im Hintergrund.
     */
    @PostMapping("/youtube/play")
    public synchronized void youtubePlay(@RequestParam String id,
                                         @RequestParam(defaultValue = "true") boolean video) throws Exception {
        if (!id.matches("[A-Za-z0-9_-]{5,20}")) return; // nur echte Video-IDs zulassen
        stopPlayerInternal();
        curId = id;
        curVideo = video;
        active = true;
        rapidFails = 0;
        launchSet();
    }

    /** Öffnet die Fenster für die aktuelle Wiedergabe (ein Vollbild-Fenster pro Monitor, bzw. eines für nur Ton). */
    private void launchSet() throws Exception {
        String exe = findBrowser();
        if (exe == null) { active = false; return; }
        lastLaunch = System.currentTimeMillis();
        int gen = generation;
        if (curVideo) {
            var genv = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice primary = genv.getDefaultScreenDevice();
            for (GraphicsDevice d : genv.getScreenDevices()) {
                Rectangle b = d.getDefaultConfiguration().getBounds();
                launchWindow(exe, b, true, d != primary, gen); // Ton nur auf dem Hauptmonitor, sonst Echo
            }
        } else {
            // off-screen, aber sichtbar starten; verstecken erst, wenn die Wiedergabe läuft
            // (Chrome blockt Autoplay in einem versteckten Fenster -> sonst kein Ton)
            audioProc = launchWindow(exe, new Rectangle(-32000, -32000, 320, 240), false, false, gen);
        }
    }

    private Process launchWindow(String exe, Rectangle b, boolean video, boolean mute, int gen) throws Exception {
        Path profile = Files.createTempDirectory("yt-player");
        String url = "http://localhost:" + port + "/api/player?id=" + curId
                + "&video=" + (video ? 1 : 0) + "&mute=" + (mute ? 1 : 0);
        List<String> cmd = new ArrayList<>(List.of(
                exe,
                "--user-data-dir=" + profile,
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-session-crashed-bubble",
                "--autoplay-policy=no-user-gesture-required",
                // ohne diese Flags drosselt Chrome off-screen/verdeckte Fenster -> Video hängt im Buffering, kein Ton
                "--disable-backgrounding-occluded-windows",
                "--disable-renderer-backgrounding",
                "--disable-background-timer-throttling",
                "--disable-features=CalculateNativeWinOcclusion",
                "--app=" + url,
                "--window-position=" + b.x + "," + b.y,
                "--window-size=" + b.width + "," + b.height));
        if (video) cmd.add("--start-fullscreen");
        Process p = new ProcessBuilder(cmd).start();
        players.add(p);
        watch(p, gen);
        return p;
    }

    /** Watchdog: wird ein Fenster vor dem Videoende geschlossen, startet die ganze Wiedergabe neu. */
    private void watch(Process p, int gen) {
        p.onExit().thenRun(() -> {
            synchronized (this) {
                players.remove(p);
                if (!active || gen != generation) return; // alte/abgelaufene Wiedergabe -> ignorieren
                if (System.currentTimeMillis() - lastLaunch < 3000) {
                    if (++rapidFails >= 6) { active = false; return; } // Startfehler -> keine Endlosschleife
                } else {
                    rapidFails = 0;
                }
                generation++; // alte Fenster/Watchdogs entwerten
                killAll();
                try { launchSet(); } catch (Exception ignored) { active = false; }
            }
        });
    }

    /** Stoppt die laufende Wiedergabe (schließt alle Fenster). */
    @PostMapping("/youtube/stop")
    public synchronized void youtubeStop() {
        stopPlayerInternal();
    }

    /** Vom Player gemeldet, wenn das Video zu Ende ist: Fenster schließen, nicht neu starten. */
    @PostMapping("/youtube/ended")
    public synchronized void youtubeEnded() {
        stopPlayerInternal();
    }

    /** Vom Nur-Ton-Player gemeldet, sobald er wirklich spielt: jetzt verstecken (Taskleiste + unsichtbar). */
    @PostMapping("/youtube/hide")
    public void youtubeHide() {
        Process p = audioProc;
        if (p != null && p.isAlive()) hideWindowsOf(p);
    }

    private void stopPlayerInternal() {
        active = false;
        audioProc = null;
        generation++;
        killAll();
    }

    private void killAll() {
        for (Process p : players) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
        players.clear();
    }

    /** Versteckt (SW_HIDE) alle Fenster des Prozesses, damit nur Ton läuft und nichts in der Taskleiste steht. */
    private void hideWindowsOf(Process p) {
        Thread t = new Thread(() -> {
            long end = System.currentTimeMillis() + 2000; // kurz nachfassen, falls Fenster später erscheinen
            while (System.currentTimeMillis() < end && p == audioProc && p.isAlive()) {
                java.util.Set<Long> pids = new java.util.HashSet<>();
                pids.add(p.pid());
                p.descendants().forEach(h -> pids.add(h.pid()));
                WinApi.hideProcessWindows(pids);
                try { Thread.sleep(150); } catch (InterruptedException e) { return; }
            }
        }, "yt-hide");
        t.setDaemon(true);
        t.start();
    }

    /** Minimaler Zugriff auf user32.dll über JNA, um Fenster per SW_HIDE auszublenden. */
    static final class WinApi {
        static void hideProcessWindows(java.util.Set<Long> pids) {
            try {
                com.sun.jna.platform.win32.User32 u = com.sun.jna.platform.win32.User32.INSTANCE;
                u.EnumWindows((hWnd, data) -> {
                    com.sun.jna.ptr.IntByReference pid = new com.sun.jna.ptr.IntByReference();
                    u.GetWindowThreadProcessId(hWnd, pid);
                    if (pids.contains((long) pid.getValue()) && u.IsWindowVisible(hWnd)) {
                        u.ShowWindow(hWnd, com.sun.jna.platform.win32.WinUser.SW_HIDE);
                    }
                    return true;
                }, null);
            } catch (Throwable ignored) { /* JNA/user32 nicht verfügbar -> Fenster bleibt eben sichtbar */ }
        }
    }

    private static String findBrowser() {
        String[] candidates = {
            env("ProgramFiles") + "\\Google\\Chrome\\Application\\chrome.exe",
            env("ProgramFiles(x86)") + "\\Google\\Chrome\\Application\\chrome.exe",
            env("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe",
            env("ProgramFiles(x86)") + "\\Microsoft\\Edge\\Application\\msedge.exe",
            env("ProgramFiles") + "\\Microsoft\\Edge\\Application\\msedge.exe",
        };
        for (String c : candidates) {
            if (!c.startsWith("null") && Files.isRegularFile(Path.of(c))) return c;
        }
        return null;
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "null" : v;
    }

    /** Eigene Player-Seite: spielt per YouTube-IFrame-API ab, fängt Eingaben ab und spielt bei Pause sofort weiter. */
    @GetMapping(value = "/player", produces = MediaType.TEXT_HTML_VALUE)
    public String player() {
        return """
            <!doctype html><html><head><meta charset="utf-8">
            <style>
              *{cursor:none !important} /* Mauszeiger ausblenden */
              html,body{margin:0;height:100%;background:#000;overflow:hidden}
              #p{position:fixed;inset:0;width:100%;height:100%;border:0}
              #block{position:fixed;inset:0;z-index:2} /* schluckt Klicks -> kein Pausieren */
            </style></head><body>
            <div id="p"></div><div id="block"></div>
            <script>
              const q = new URLSearchParams(location.search);
              const id = q.get('id');
              const mute = q.get('mute') === '1';
              const video = q.get('video') === '1';
              let hidden = false;
              let unmuted = false;
              // Eingaben abfangen, soweit die Seite das kann (OS-Kombis wie Alt+F4 kann der Browser nicht blocken)
              addEventListener('contextmenu', e => e.preventDefault());
              addEventListener('keydown', e => { e.preventDefault(); e.stopPropagation(); }, true);
              let player;
              function onYouTubeIframeAPIReady() {
                player = new YT.Player('p', {
                  videoId: id,
                  playerVars: { autoplay:1, controls:0, disablekb:1, fs:0, rel:0, modestbranding:1, playsinline:1 },
                  events: {
                    onReady: e => {
                      // immer stumm starten: stummes Autoplay ist im frischen Browser-Profil immer erlaubt
                      e.target.mute();
                      e.target.playVideo();
                    },
                    onStateChange: e => {
                      if (e.data === YT.PlayerState.PLAYING && !mute && !unmuted) {
                        // läuft -> jetzt entstummen -> Ton (bleibt ohne Geste erlaubt)
                        unmuted = true;
                        e.target.unMute();
                        e.target.setVolume(100);
                      }
                      if (e.data === YT.PlayerState.PAUSED) e.target.playVideo(); // nicht pausierbar
                      if (e.data === YT.PlayerState.ENDED && !mute) {
                        // nur das Tonfenster meldet das Ende; der Server schließt dann alle Fenster
                        fetch('/api/youtube/ended', { method: 'POST' }).finally(() => { try { window.close(); } catch (x) {} });
                      }
                    }
                  }
                });
              }
              const s = document.createElement('script');
              s.src = 'https://www.youtube.com/iframe_api';
              document.head.appendChild(s);
            </script></body></html>
            """;
    }

    /** Liest "runs[0].text" oder "simpleText" aus einem YouTube-Textknoten. */
    private static String text(JsonNode node) {
        JsonNode runs = node.path("runs");
        if (runs.isArray() && !runs.isEmpty()) return runs.get(0).path("text").asText("");
        return node.path("simpleText").asText("");
    }

    /** Schneidet ab Position 'start' das erste vollständige, balancierte JSON-Objekt {...} heraus. */
    private static String extractJsonObject(String s, int start) {
        int i = s.indexOf('{', start);
        if (i < 0) return null;
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int p = i; p < s.length(); p++) {
            char c = s.charAt(p);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(i, p + 1);
        }
        return null;
    }
}
