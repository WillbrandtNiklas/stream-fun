package fun;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/** Nimmt rohes PCM16-Mono vom Browser entgegen und spielt es live auf dem Standard-Audioausgang ab. */
@Configuration
@EnableWebSocket
public class AudioWs implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new Handler(), "/ws/audio").setAllowedOrigins("*");
        registry.addHandler(new MicHandler(), "/ws/mic").setAllowedOrigins("*");
    }

    /** Nimmt das Standard-Mikro des Server-PCs auf und streamt es als PCM16-Mono an den Browser. */
    static class MicHandler extends AbstractWebSocketHandler {
        private static final float RATE = 44100f;
        private final Map<String, TargetDataLine> lines = new ConcurrentHashMap<>();

        @Override
        public void afterConnectionEstablished(WebSocketSession s) throws Exception {
            AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
            TargetDataLine line = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, fmt));
            line.open(fmt);
            line.start();
            lines.put(s.getId(), line);
            // der Browser braucht die Samplerate, um den Stream richtig abzuspielen
            s.sendMessage(new TextMessage("rate:" + (int) RATE));
            Thread t = new Thread(() -> pump(s, line), "mic-" + s.getId());
            t.setDaemon(true);
            t.start();
        }

        private void pump(WebSocketSession s, TargetDataLine line) {
            byte[] buf = new byte[4096];
            try {
                while (s.isOpen() && lines.containsKey(s.getId())) {
                    int n = line.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    synchronized (s) {
                        s.sendMessage(new BinaryMessage(java.nio.ByteBuffer.wrap(buf, 0, n)));
                    }
                }
            } catch (Exception ignored) {
                // Verbindung weg oder Leitung geschlossen -> einfach aufhören
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus st) {
            TargetDataLine l = lines.remove(s.getId());
            if (l != null) { l.stop(); l.close(); }
        }
    }

    static class Handler extends AbstractWebSocketHandler {
        private final Map<String, SourceDataLine> lines = new ConcurrentHashMap<>();

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
            String p = m.getPayload();
            if (p.startsWith("rate:")) {
                float rate = Float.parseFloat(p.substring(5));
                SourceDataLine l = AudioSystem.getSourceDataLine(new AudioFormat(rate, 16, 1, true, false));
                l.open();
                l.start();
                lines.put(s.getId(), l);
            }
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession s, BinaryMessage m) {
            SourceDataLine l = lines.get(s.getId());
            if (l == null) return;
            byte[] b = new byte[m.getPayloadLength()];
            m.getPayload().get(b);
            l.write(b, 0, b.length);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus st) {
            SourceDataLine l = lines.remove(s.getId());
            if (l != null) { l.drain(); l.close(); }
        }
    }
}
