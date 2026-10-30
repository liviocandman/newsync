package br.com.agregador;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReadinessController {
  private volatile boolean started;
  private final JdbcClient jdbc;

  public ReadinessController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void ready() {
    started = true;
  }

  @GetMapping("/health/ready")
  public ResponseEntity<Void> readiness() {
    if (!started) return ResponseEntity.status(503).build();
    try {
      jdbc.sql("SELECT 1").query(Integer.class).single();
      return ResponseEntity.ok().build();
    } catch (RuntimeException e) {
      return ResponseEntity.status(503).build();
    }
  }
}
