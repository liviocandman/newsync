package br.com.agregador;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

class MigrationFailureIT {
  @Test
  void migrationFailurePreventsDemoReadiness() throws Exception {
    var db = DatabaseIT.POSTGRES;
    try (var connection =
            DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE b00_bad_migration");
    }
    AtomicBoolean ready = new AtomicBoolean();
    var application =
        new SpringApplicationBuilder(NewsApplication.class)
            .listeners(
                new ApplicationListener<ApplicationReadyEvent>() {
                  @Override
                  public void onApplicationEvent(ApplicationReadyEvent event) {
                    ready.set(true);
                  }
                });
    assertThatThrownBy(
            () ->
                application.run(
                    "--spring.profiles.active=demo",
                    "--server.port=0",
                    "--spring.datasource.url="
                        + db.getJdbcUrl().replace("/test?", "/b00_bad_migration?"),
                    "--spring.datasource.username=" + db.getUsername(),
                    "--spring.datasource.password=" + db.getPassword(),
                    "--spring.flyway.locations=classpath:broken-migration"))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("b00_missing_function");
    assertThat(ready).isFalse();
  }
}
