package fun;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import javax.swing.*;
import org.springframework.stereotype.Component;

/**
 * Spielerei: transparentes Vollbild-Overlay (Hauptmonitor) mit Cartoon-Fliegen, die über den Bildschirm krabbeln/fliegen.
 * Fliegen legen ab und zu Eier; ein Ei muss 5-mal angeklickt werden, sonst schlüpft eine neue Fliege.
 * Ein Klick auf eine Fliege entfernt sie. Der Rest des Bildschirms bleibt klickbar (WS_EX_TRANSPARENT,
 * solange die Maus nicht über einer Fliege/einem Ei ist). Alles läuft auf dem Swing-EDT.
 */
@Component
public class FlyOverlay {
    static final int MAX_OBJECTS = 40;
    private static final int EGG_HITS = 5;
    private static final double HATCH_SECS = 14;
    private static final double FLY_SIZE = 38;

    private final List<Fly> flies = new ArrayList<>();
    private final List<Egg> eggs = new ArrayList<>();
    private final List<Splat> splats = new ArrayList<>();
    private JWindow window;
    private Timer timer;
    private long lastNanos;
    private double time;
    private Boolean clickThrough; // zuletzt gesetzter Zustand von WS_EX_TRANSPARENT

    /** Spawnt eine Fliege an einem zufälligen Rand. Liefert die aktuellen Zahlen. */
    public Map<String, Integer> spawn() {
        return onEdt(() -> {
            ensureWindow();
            if (total() < MAX_OBJECTS) {
                Rectangle b = window.getBounds();
                var r = ThreadLocalRandom.current();
                double x = r.nextDouble(40, b.width - 40), y = r.nextDouble(40, b.height - 40);
                switch (r.nextInt(4)) { // vom Bildschirmrand hereinfliegen
                    case 0 -> x = 5;
                    case 1 -> x = b.width - 5;
                    case 2 -> y = 5;
                    default -> y = b.height - 5;
                }
                Fly f = new Fly(x, y);
                f.heading = Math.atan2(b.height / 2.0 - y, b.width / 2.0 - x);
                f.flying = true;
                f.modeLeft = 1.5;
                flies.add(f);
            }
            start();
            return counts();
        });
    }

    /** Entfernt sofort alle Fliegen und Eier und blendet das Overlay aus. */
    public Map<String, Integer> killAll() {
        return onEdt(() -> {
            flies.clear();
            eggs.clear();
            splats.clear();
            stop();
            return counts();
        });
    }

    private int total() { return flies.size() + eggs.size(); }

    private Map<String, Integer> counts() { return Map.of("flies", flies.size(), "eggs", eggs.size(), "max", MAX_OBJECTS); }

    private static <T> T onEdt(java.util.concurrent.Callable<T> c) {
        try {
            if (SwingUtilities.isEventDispatchThread()) return c.call();
            Object[] res = new Object[1];
            SwingUtilities.invokeAndWait(() -> {
                try { res[0] = c.call(); } catch (Exception e) { throw new RuntimeException(e); }
            });
            @SuppressWarnings("unchecked") T t = (T) res[0];
            return t;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------------------------------------------------------- Fenster

    private void ensureWindow() {
        if (window != null) return;
        // nur der Hauptmonitor: ein riesiges Fenster über alle Monitore wäre pro Bild zu teuer
        Rectangle all = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getBounds();
        JWindow w = new JWindow();
        w.setAlwaysOnTop(true);
        w.setFocusableWindowState(false); // stiehlt dem aktiven Programm nicht den Fokus
        w.setBackground(new Color(0, 0, 0, 0)); // per-pixel-Transparenz
        w.setBounds(all);
        JPanel canvas = new JPanel() {
            @Override protected void paintComponent(Graphics g) { paintScene((Graphics2D) g); }
        };
        canvas.setOpaque(false);
        canvas.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { click(e.getX(), e.getY()); }
        });
        w.setContentPane(canvas);
        window = w;
    }

    private void start() {
        if (!window.isVisible()) {
            window.setVisible(true);
            clickThrough = null;
            setClickThrough(true);
        }
        if (timer == null) {
            lastNanos = System.nanoTime();
            timer = new Timer(33, e -> tick());
            timer.start();
        }
    }

    private void stop() {
        if (timer != null) { timer.stop(); timer = null; }
        if (window != null) window.setVisible(false);
    }

    /** Schaltet per JNA WS_EX_TRANSPARENT um: an = alle Klicks gehen durch das Overlay hindurch. */
    private void setClickThrough(boolean on) {
        if (clickThrough != null && clickThrough == on) return;
        clickThrough = on;
        try {
            HWND hwnd = new HWND(Native.getWindowPointer(window));
            User32 u = User32.INSTANCE;
            int ex = u.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
            ex |= WinUser.WS_EX_LAYERED | 0x80 /* WS_EX_TOOLWINDOW */ | 0x08000000 /* WS_EX_NOACTIVATE */;
            ex = on ? ex | WinUser.WS_EX_TRANSPARENT : ex & ~WinUser.WS_EX_TRANSPARENT;
            u.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, ex);
        } catch (Throwable ignored) {
            // ohne JNA: völlig transparente Pixel sind unter Windows ohnehin durchklickbar
        }
    }

    // ---------------------------------------------------------------- Logik

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        time += dt;
        int w = window.getWidth(), h = window.getHeight();
        var r = ThreadLocalRandom.current();

        for (Fly f : flies) {
            f.update(dt, w, h);
            f.eggIn -= dt;
            if (f.eggIn <= 0) {
                f.eggIn = r.nextDouble(8, 16);
                if (total() < MAX_OBJECTS) eggs.add(new Egg(f.x - Math.cos(f.heading) * 14, f.y - Math.sin(f.heading) * 14));
            }
        }
        for (int i = eggs.size() - 1; i >= 0; i--) {
            Egg e = eggs.get(i);
            e.age += dt;
            e.shake = Math.max(0, e.shake - dt * 4);
            if (e.age >= HATCH_SECS) { // geschlüpft: Ei wird zur Fliege (Gesamtzahl bleibt gleich)
                eggs.remove(i);
                Fly f = new Fly(e.x, e.y);
                f.flying = true;
                f.modeLeft = 1.2;
                flies.add(f);
            }
        }
        splats.removeIf(s -> (s.age += dt) > 1.2);

        if (flies.isEmpty() && eggs.isEmpty() && splats.isEmpty()) {
            stop();
            return;
        }
        // Klicks nur abfangen, wenn die Maus über einer Fliege/einem Ei ist
        Point m = MouseInfo.getPointerInfo().getLocation();
        Point o = window.getLocation();
        setClickThrough(hit(m.x - o.x, m.y - o.y) == null);
        window.getContentPane().repaint();
    }

    /** Oberstes Objekt unter (x, y) – Fliegen liegen über Eiern. */
    private Object hit(double x, double y) {
        for (int i = flies.size() - 1; i >= 0; i--) {
            Fly f = flies.get(i);
            if (Point2D.distance(x, y, f.x, f.y) < FLY_SIZE * 0.75) return f;
        }
        for (int i = eggs.size() - 1; i >= 0; i--) {
            Egg e = eggs.get(i);
            if (Point2D.distance(x, y, e.x, e.y) < 22) return e;
        }
        return null;
    }

    private void click(int x, int y) {
        Object o = hit(x, y);
        if (o instanceof Fly f) {
            flies.remove(f);
            splats.add(new Splat(f.x, f.y, false));
        } else if (o instanceof Egg e) {
            e.hits++;
            e.shake = 1;
            if (e.hits >= EGG_HITS) {
                eggs.remove(e);
                splats.add(new Splat(e.x, e.y, true));
            }
        }
        window.getContentPane().repaint();
    }

    // ---------------------------------------------------------------- Zeichnen

    private void paintScene(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        for (Splat s : splats) drawSplat(g, s);
        for (Egg e : eggs) drawEgg(g, e);
        for (Fly f : flies) drawFly(g, f);
    }

    /** Fast unsichtbarer Hof um ein Objekt (Alpha 1), damit Klicks knapp daneben trotzdem ankommen. */
    private static void halo(Graphics2D g, double x, double y, double r) {
        g.setColor(new Color(0, 0, 0, 1));
        g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
    }

    private void drawFly(Graphics2D g, Fly f) {
        halo(g, f.x, f.y, FLY_SIZE * 0.75);
        AffineTransform old = g.getTransform();
        g.translate(f.x, f.y);
        g.rotate(f.heading);
        double s = FLY_SIZE / 38.0;
        g.scale(s, s);
        // Fliege schaut nach +x

        // Beine (beim Krabbeln zappelnd)
        g.setColor(new Color(25, 25, 25));
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double wig = f.flying ? 0 : Math.sin(f.legPhase) * 3;
        for (int i = -1; i <= 1; i++) {
            double lx = i * 6, w2 = (i % 2 == 0 ? wig : -wig);
            g.draw(new Line2D.Double(lx, -4, lx + 3 + w2, -13));
            g.draw(new Line2D.Double(lx, 4, lx + 3 - w2, 13));
        }

        // Körper
        g.setPaint(new RadialGradientPaint(new Point2D.Double(-4, -3), 14, new float[]{0f, 1f},
                new Color[]{new Color(95, 105, 110), new Color(20, 22, 26)}));
        g.fill(new Ellipse2D.Double(-15, -8, 24, 16));
        g.setColor(new Color(60, 140, 90, 120)); // grünlicher Schimmer
        g.fill(new Ellipse2D.Double(-12, -5, 14, 5));
        // Streifen
        g.setColor(new Color(10, 10, 10, 160));
        g.setStroke(new BasicStroke(1.5f));
        for (int i = 0; i < 3; i++) g.draw(new Arc2D.Double(-14 + i * 5, -7, 6, 14, 60, 240, Arc2D.OPEN));

        // Kopf + große rote Augen
        g.setColor(new Color(30, 30, 34));
        g.fill(new Ellipse2D.Double(6, -6, 11, 12));
        g.setColor(new Color(190, 30, 30));
        g.fill(new Ellipse2D.Double(10, -9, 9, 9));
        g.fill(new Ellipse2D.Double(10, 0, 9, 9));
        g.setColor(new Color(255, 255, 255, 200));
        g.fill(new Ellipse2D.Double(14, -7, 3, 3));
        g.fill(new Ellipse2D.Double(14, 2, 3, 3));

        // Flügel: beim Fliegen schnell flatternd, sonst angelegt
        double spread = f.flying ? 0.35 + 0.55 * Math.abs(Math.sin(time * 45)) : 0.25;
        g.setStroke(new BasicStroke(1.2f));
        for (int side = -1; side <= 1; side += 2) {
            AffineTransform t = g.getTransform();
            g.rotate(Math.PI - side * spread);
            Shape wing = new Ellipse2D.Double(0, -5, 22, 10);
            g.setColor(new Color(200, 225, 255, 120));
            g.fill(wing);
            g.setColor(new Color(90, 110, 140, 170));
            g.draw(wing);
            g.draw(new Line2D.Double(2, 0, 20, 0));
            g.setTransform(t);
        }
        g.setTransform(old);
    }

    private void drawEgg(Graphics2D g, Egg e) {
        halo(g, e.x, e.y, 22);
        AffineTransform old = g.getTransform();
        double hatching = Math.max(0, (e.age - (HATCH_SECS - 4)) / 4); // die letzten 4 s wackeln
        double wobble = Math.sin(time * 30) * (0.08 * e.shake + 0.18 * hatching);
        g.translate(e.x, e.y);
        g.rotate(wobble);
        Shape egg = new Ellipse2D.Double(-10, -13, 20, 26);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(-3, -6), 16, new float[]{0f, 1f},
                new Color[]{Color.WHITE, new Color(225, 220, 195)}));
        g.fill(egg);
        g.setColor(new Color(150, 140, 110));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(egg);
        // Risse je nach Treffern
        g.setColor(new Color(70, 60, 40));
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double[][] cracks = {
            {-6, -4, -2, -1, -5, 3}, {4, -9, 1, -5, 5, -2}, {-2, 6, 2, 3, 6, 7},
            {-9, 1, -4, 2, -1, -3}, {2, 9, 6, 4, 9, 2}};
        for (int i = 0; i < Math.min(e.hits, cracks.length); i++) {
            double[] c = cracks[i];
            Path2D p = new Path2D.Double();
            p.moveTo(c[0], c[1]);
            p.lineTo(c[2], c[3]);
            p.lineTo(c[4], c[5]);
            g.draw(p);
        }
        g.setTransform(old);
    }

    private void drawSplat(Graphics2D g, Splat s) {
        float a = (float) Math.max(0, 1 - s.age / 1.2);
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(a));
        Color c = s.egg ? new Color(240, 200, 60) : new Color(70, 90, 40);
        g.setColor(c);
        double r = 10 + s.age * 10;
        g.fill(new Ellipse2D.Double(s.x - r, s.y - r * 0.8, 2 * r, 1.6 * r));
        for (int i = 0; i < 6; i++) {
            double ang = s.seed + i * Math.PI / 3, d = r + 6;
            g.fill(new Ellipse2D.Double(s.x + Math.cos(ang) * d - 3, s.y + Math.sin(ang) * d - 3, 6, 6));
        }
        g.setComposite(old);
    }

    // ---------------------------------------------------------------- Objekte

    private static final class Fly {
        double x, y, heading, turn;
        boolean flying;
        boolean resting;
        double modeLeft;
        double legPhase;
        double eggIn = ThreadLocalRandom.current().nextDouble(5, 12);

        Fly(double x, double y) { this.x = x; this.y = y; heading = ThreadLocalRandom.current().nextDouble(Math.PI * 2); }

        void update(double dt, int w, int h) {
            var r = ThreadLocalRandom.current();
            modeLeft -= dt;
            if (modeLeft <= 0) { // neues Verhalten: krabbeln, fliegen oder kurz stillsitzen
                double p = r.nextDouble();
                flying = p < 0.35;
                resting = !flying && p < 0.55;
                modeLeft = flying ? r.nextDouble(0.8, 2.5) : r.nextDouble(1.0, 4.0);
                turn = r.nextDouble(-1, 1);
            }
            if (resting) return;
            double speed = flying ? 260 : 45;
            // zufälliges Schlängeln; Fliegen kurven weicher, Krabbeln zackiger
            turn += r.nextGaussian() * (flying ? 2.0 : 6.0) * dt;
            turn = Math.max(-2.5, Math.min(2.5, turn));
            heading += turn * dt * (flying ? 1.5 : 2.5);
            legPhase += dt * 30;

            double m = 20;
            if (x < m || x > w - m || y < m || y > h - m) { // am Rand zurück Richtung Mitte drehen
                double toCenter = Math.atan2(h / 2.0 - y, w / 2.0 - x);
                double diff = Math.atan2(Math.sin(toCenter - heading), Math.cos(toCenter - heading));
                heading += diff * Math.min(1, dt * 6);
            }
            x = Math.max(0, Math.min(w, x + Math.cos(heading) * speed * dt));
            y = Math.max(0, Math.min(h, y + Math.sin(heading) * speed * dt));
        }
    }

    private static final class Egg {
        final double x, y;
        double age, shake;
        int hits;
        Egg(double x, double y) { this.x = x; this.y = y; }
    }

    private static final class Splat {
        final double x, y, seed = ThreadLocalRandom.current().nextDouble(Math.PI);
        final boolean egg;
        double age;
        Splat(double x, double y, boolean egg) { this.x = x; this.y = y; this.egg = egg; }
    }
}
