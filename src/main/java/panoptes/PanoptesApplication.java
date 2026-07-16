package panoptes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * The main entry point for the Panoptes Spring Boot application.
 * <p>
 * Panoptes is a multi-agent, cross-disciplinary AI research engine. This class bootstraps the 
 * application context and explicitly enables asynchronous processing ({@code @EnableAsync}), 
 * which is fundamentally required to run long-lasting LLM research pipelines in the background 
 * without blocking HTTP worker threads.
 * </p>
 */
@SpringBootApplication
@EnableAsync 
public class PanoptesApplication {
    public static void main(String[] args) {
        SpringApplication.run(PanoptesApplication.class, args);
    }
}
