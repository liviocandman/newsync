package br.com.agregador;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

class DemoConfigurationIT {
  static final String DATABASE = "f02_config_" + UUID.randomUUID().toString().replace("-", "");
  static String url;

  @BeforeAll
  static void prepareReachableMigratedPostgres() throws Exception {
    var db = DatabaseIT.POSTGRES;
    try (var connection =
            DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + DATABASE);
    }
    url = "jdbc:postgresql://" + db.getHost() + ":" + db.getFirstMappedPort() + "/" + DATABASE;
    var flyway = Flyway.configure().dataSource(url, db.getUsername(), db.getPassword()).load();
    flyway.migrate();
    assertThat(flyway.info().current().getVersion().toString()).isEqualTo("001");
  }

  @AfterAll
  static void removeOwnDatabase() throws Exception {
    var db = DatabaseIT.POSTGRES;
    try (var connection =
            DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
    }
  }

  static class Boot implements AutoCloseable {
    final AtomicBoolean ready = new AtomicBoolean();
    final AtomicReference<ConfigurableApplicationContext> context = new AtomicReference<>();

    void run(String address, String origin) {
      var db = DatabaseIT.POSTGRES;
      new SpringApplicationBuilder(NewsApplication.class)
          .listeners(
              (ApplicationListener<ApplicationPreparedEvent>)
                  event -> context.set(event.getApplicationContext()),
              (ApplicationListener<ApplicationReadyEvent>) event -> ready.set(true))
          .run(
              "--spring.profiles.active=demo",
              "--server.port=0",
              "--server.address=" + address,
              "--app.base-url=" + origin,
              "--spring.datasource.url=" + url,
              "--spring.datasource.username=" + db.getUsername(),
              "--spring.datasource.password=" + db.getPassword());
    }

    @Override
    public void close() {
      if (context.get() != null) context.get().close();
    }
  }

  @Test
  void localHttpBootImportsAndBecomesReady() throws Exception {
    try (var boot = new Boot()) {
      boot.run("127.0.0.1", "http://127.0.0.1:5173");
      assertThat(boot.ready).isTrue();
      var context = boot.context.get();
      assertThat(
              context
                  .getBean(JdbcClient.class)
                  .sql("SELECT count(*) FROM articles")
                  .query(Long.class)
                  .single())
          .isEqualTo(3);
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      try (var client = HttpClient.newHttpClient()) {
        var response =
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health/ready"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
      }
    }
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource({
    "0.0.0.0, http://127.0.0.1:5173",
    "::, http://127.0.0.1:5173",
    "127.0.0.1, http://example.org:5173",
    "127.0.0.1, http://demo-user:demo-secret@127.0.0.1:5173",
    "127.0.0.1, http://127.0.0.1:5173/",
    "127.0.0.1, http://127.0.0.1:5173/path",
    "127.0.0.1, http://127.0.0.1:5173?query=demo-secret",
    "127.0.0.1, http://127.0.0.1:5173#demo-secret",
    "127.0.0.1, https://127.0.0.1:5173",
    "127.0.0.1, ftp://127.0.0.1:5173",
    "127.0.0.1, mailto:demo-secret@example.org",
    "127.0.0.1, http://127.0.0.1:5173/%demo-secret"
  })
  void invalidDemoConfigurationNeverBecomesReady(String address, String origin) {
    try (var boot = new Boot()) {
      var failure = catchThrowable(() -> boot.run(address, origin));
      assertThat(boot.ready).as("ApplicationReadyEvent deve estar ausente").isFalse();
      assertThat(failure)
          .isNotNull()
          .hasRootCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("Demo exige endereço e origem HTTP de loopback");
      assertThat(failure)
          .hasStackTraceContaining("SecurityConfiguration")
          .hasStackTraceContaining("Demo exige endereço e origem HTTP de loopback");
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        assertThat(cause.getMessage()).doesNotContain("demo-user", "demo-secret", origin);
      }
    }
  }
}
