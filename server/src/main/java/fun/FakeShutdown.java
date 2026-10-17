package fun;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.springframework.stereotype.Component;

/**
 * Streich: zeigt für ein paar Sekunden einen gefälschten "Wird heruntergefahren"-Vollbildschirm
 * (dunkel, mit drehendem Punkte-Spinner im Windows-Stil) über alle Monitore und blendet sich
 * danach selbst wieder aus. Alles läuft auf dem Swing-EDT.
 */
@Component
public class FakeShutdown {
    private JWindow window;
    private Timer anim;   // dreht den Spinner
    private Timer closer; // blendet das Overlay nach Ablauf wieder aus
    private double angle;

    /** Zeigt das Fake-Herunterfahren für die angegebene Dauer. Läuft schon eins, passiert nichts. */
    public synchronized void show(int seconds) {
        if (window != null) return;
        Rectangle all = new Rectangle();
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            all = all.union(d.getDefaultConfiguration().getBounds());
        }
        final Rectangle bounds = all;
        Runnable r = () -> {
            JWindow w = new JWindow();
            w.setAlwaysOnTop(true);
            w.setBounds(bounds);
            w.setContentPane(new ShutdownPanel());
            w.setVisible(true);
            window = w;
            angle = 0;
            anim = new Timer(40, e -> { angle += 0.12; if (window != null) window.getContentPane().repaint(); });
            anim.start();
            closer = new Timer(seconds * 1000, e -> close());
            closer.setRepeats(false);
            closer.start();
        };
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    /** Beendet das Overlay sofort. */
    public synchronized void close() {
        if (anim != null) { anim.stop(); anim = null; }
        if (closer != null) { closer.stop(); closer = null; }
        if (window != null) { window.dispose(); window = null; }
    }

    private final class ShutdownPanel extends JPanel {
        ShutdownPanel() { setBackground(new Color(0x1F1F1F)); } // dunkelgrau wie der echte Shutdown-Screen

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            double cx = w / 2.0, cy = h / 2.0 - 40, rad = 26;

            // Spinner: Punktekreis, die Helligkeit "läuft" rundherum
            int dots = 8;
            for (int i = 0; i < dots; i++) {
                double a = angle + i * (2 * Math.PI / dots);
                double dx = cx + Math.cos(a) * rad, dy = cy + Math.sin(a) * rad;
                float alpha = (float) (i + 1) / dots;
                g.setColor(new Color(1f, 1f, 1f, alpha));
                double s = 5;
                g.fill(new Ellipse2D.Double(dx - s, dy - s, 2 * s, 2 * s));
            }

            // Text darunter
            g.setColor(Color.WHITE);
            g.setFont(new Font("Segoe UI", Font.PLAIN, 26));
            String msg = "Wird heruntergefahren";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(msg, (int) (cx - fm.stringWidth(msg) / 2.0), (int) (cy + 72));
        }
    }
}
