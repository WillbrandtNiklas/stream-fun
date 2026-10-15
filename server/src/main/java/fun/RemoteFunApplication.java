package fun;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class RemoteFunApplication {
    public static void main(String[] args) {
        // headless(false) ist nötig, damit java.awt.Robot (Screenshot, Tasten, Maus) funktioniert
        new SpringApplicationBuilder(RemoteFunApplication.class).headless(false).run(args);
    }
}
