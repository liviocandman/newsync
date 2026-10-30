package br.com.agregador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.agregador.ingestion.CollectionWriter;
import br.com.agregador.ingestion.FeedImporter;
import br.com.agregador.ingestion.FeedParser;
import br.com.agregador.ingestion.ManualFeedDispatcher;
import br.com.agregador.ingestion.PublicFeedClient;
import br.com.agregador.sources.CollectionActivity;
import br.com.agregador.sources.SourceRepository;
import br.com.agregador.sources.SourceService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** T-20: actual subprocesses, synthetic archive data and isolated resources only. */
class BackupRestoreIT {
  static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  static final Path JAR = ROOT.resolve("backend/target/news.jar");
  static final ObjectMapper JSON = new ObjectMapper();
  static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
  static final String ORIGIN = "http://127.0.0.1:5173";
  final List<Integer> exits = new ArrayList<>();
  final List<Integer> javaExits = new ArrayList<>();
  final Map<String, Integer> toolExits = new TreeMap<>();
  Path work;
  PostgreSQLContainer postgres;

  String url(String database) {
    return "jdbc:postgresql://"
        + postgres.getHost()
        + ":"
        + postgres.getFirstMappedPort()
        + "/"
        + database;
  }

  String user(String database) {
    return database.equals("source") ? postgres.getUsername() : "t20_restore";
  }

  JdbcClient jdbc(String database) {
    return JdbcClient.create(
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            url(database), user(database), postgres.getPassword()));
  }

  ConfigurableApplicationContext context(String database) {
    return new SpringApplicationBuilder(NewsApplication.class)
        .run(
            "--spring.profiles.active=normal",
            "--server.port=0",
            "--app.collection.enabled=false",
            "--spring.datasource.url=" + url(database),
            "--spring.datasource.username=" + user(database),
            "--spring.datasource.password=" + postgres.getPassword());
  }

  record Result(int exit, String output) {}

  Result command(List<String> command) throws Exception {
    Path log = Files.createTempFile(work, "command-", ".log");
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .directory(ROOT.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    builder
        .environment()
        .keySet()
        .removeIf(
            key -> key.startsWith("JDBC_") || key.startsWith("SPRING_") || key.startsWith("APP_"));
    Process process = builder.start();
    try {
      assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("subprocesso terminou").isTrue();
      exits.add(process.exitValue());
      String output = Files.readString(log);
      var matcher =
          java.util.regex.Pattern.compile("(?m)^([a-z-]+): exit=([0-9]+)$").matcher(output);
      while (matcher.find()) toolExits.merge(matcher.group(), 1, Integer::sum);
      return new Result(process.exitValue(), output);
    } finally {
      process.destroyForcibly();
    }
  }

  Result helper(String action, String database, String... extra) throws Exception {
    var args =
        new ArrayList<>(
            List.of(
                "python3",
                ROOT.resolve("scripts/backup.py").toString(),
                action,
                "--container",
                postgres.getContainerId(),
                "--database",
                database,
                "--user",
                user(database),
                "--jar",
                JAR.toString()));
    args.addAll(List.of(extra));
    return command(args);
  }

  void success(Result result) {
    assertThat(result.exit).as(result.output).isZero();
  }

  class App implements AutoCloseable {
    final Process process;
    final int port;

    App(String database) throws Exception {
      try (var socket = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
        port = socket.getLocalPort();
      }
      var builder =
          new ProcessBuilder(
              Path.of(System.getProperty("java.home"), "bin/java").toString(),
              "-jar",
              JAR.toString(),
              "--spring.profiles.active=normal",
              "--server.port=" + port,
              "--app.collection.enabled=false");
      var env = builder.environment();
      env.keySet()
          .removeIf(
              key ->
                  key.startsWith("SPRING_")
                      || key.startsWith("APP_")
                      || key.startsWith("JDBC_")
                      || key.equals("JAVA_TOOL_OPTIONS")
                      || key.equals("JDK_JAVA_OPTIONS"));
      env.put("JDBC_URL", url(database));
      env.put("JDBC_USERNAME", user(database));
      env.put("JDBC_PASSWORD", postgres.getPassword());
      env.put("APP_BASE_URL", ORIGIN);
      builder
          .redirectErrorStream(true)
          .redirectOutput(Files.createTempFile(work, "java-", ".log").toFile());
      process = builder.start();
      try (var client = HttpClient.newHttpClient()) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline && process.isAlive()) {
          try {
            if (http(client, port, "GET", "/health/ready", null, null).statusCode() == 200) return;
          } catch (IOException ignored) {
            // The process is still starting.
          }
          Thread.sleep(100);
        }
      }
      close();
      fail("JAR normal não ficou pronto");
    }

    @Override
    public void close() throws Exception {
      process.destroy();
      if (!process.waitFor(15, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
      }
      assertThat(process.isAlive()).isFalse();
      javaExits.add(process.exitValue());
    }
  }

  static HttpResponse<String> http(
      HttpClient client, int port, String method, String path, String body, JsonNode token)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5));
    if (body != null) request.header("Origin", ORIGIN).header("Content-Type", "application/json");
    if (token != null)
      request.header(token.get("headerName").asString(), token.get("token").asString());
    return client.send(
        request
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  static String feed(String guid) {
    return "<rss version='2.0'><channel><title>Sintético</title><link>https://synthetic.invalid</link><description>T20</description>"
        + "<item>"
        + (guid == null ? "" : "<guid>" + guid + "</guid>")
        + "<title>Restauração PostgreSQL</title><link>https://synthetic.invalid/story</link>"
        + "<description><![CDATA[<p>Conteúdo de teste</p><script>evil()</script>]]></description></item></channel></rss>";
  }

  UUID[] seed() {
    try (var context = context("source")) {
      var sources = context.getBean(SourceRepository.class);
      var importer = context.getBean(FeedImporter.class);
      UUID active = sources.register("https://active.invalid/feed", "Ativa");
      UUID paused = sources.register("https://paused.invalid/feed", "Pausada");
      UUID archived = sources.register("https://archived.invalid/feed", "Arquivada");
      UUID automatic = sources.register("https://automatic.invalid/feed", "Automática vencida");
      UUID cooldown = sources.register("https://cooldown.invalid/feed", "Retry-After");
      for (String guid : Arrays.asList(null, "reconciled")) {
        importer.importFeed(
            active, 0, new ByteArrayInputStream(feed(guid).getBytes(StandardCharsets.UTF_8)));
      }
      JdbcClient db = context.getBean(JdbcClient.class);
      assertThat(db.sql("SELECT count(*) FROM articles").query(Long.class).single()).isEqualTo(1);
      UUID article = db.sql("SELECT id FROM articles").query(UUID.class).single();
      db.sql("UPDATE articles SET read_at='2026-10-28T12:00:00Z',saved_at=NULL").update();
      db.sql(
              "INSERT INTO articles(id,source_id,dedup_key,title,sort_at,first_seen_at,last_seen_at,updated_at,saved_at,search_document) VALUES (:id,:source,'no-body','Chamada sem corpo',now(),now(),now(),now(),now(),to_tsvector('simple','Chamada sem corpo'))")
          .param("id", UUID.randomUUID())
          .param("source", paused)
          .update();
      db.sql(
              "UPDATE sources SET next_poll_at='2026-10-29T11:00:00Z',last_attempt_at='2026-10-28T12:00:00Z',last_manual_refresh_at='2026-10-28T12:00:00Z',etag='\"synthetic\"',last_modified='Wed, 28 Oct 2026 12:00:00 GMT',diagnostic_code='PARTIAL_FEED',diagnostic_message='Sintético',ignored_items=1,consecutive_failures=2")
          .update();
      db.sql(
              "UPDATE sources SET manual_requested_at='2026-10-29T11:58:00Z' WHERE id IN (:active,:cooldown)")
          .param("active", active)
          .param("cooldown", cooldown)
          .update();
      db.sql("UPDATE sources SET retry_after_at='2026-10-29T14:00:00Z' WHERE id=:id")
          .param("id", cooldown)
          .update();
      db.sql("UPDATE sources SET status='paused',lifecycle_version=3 WHERE id=:id")
          .param("id", paused)
          .update();
      db.sql("UPDATE sources SET status='archived',lifecycle_version=5 WHERE id=:id")
          .param("id", archived)
          .update();
      return new UUID[] {active, paused, archived, automatic, cooldown, article};
    }
  }

  Map<String, List<Map<String, Object>>> rows(String database) {
    var result = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (String table : List.of("sources", "articles", "flyway_schema_history")) {
      result.put(
          table,
          jdbc(database)
              .sql(
                  "SELECT row_to_json(t)::text AS row FROM "
                      + table
                      + " t ORDER BY row_to_json(t)::text COLLATE \"C\"")
              .query()
              .listOfRows());
    }
    return result;
  }

  void resume(UUID[] ids) {
    try (var context = context("destination")) {
      var sources = context.getBean(SourceRepository.class);
      var activity = context.getBean(CollectionActivity.class);
      var transport = mock(PublicFeedClient.class);
      when(transport.fetch(anyString(), any(), any()))
          .thenReturn(
              new PublicFeedClient.Response(
                  200,
                  feed("reconciled").getBytes(StandardCharsets.UTF_8),
                  "\"next\"",
                  null,
                  null,
                  "application/rss+xml"));
      var dispatcher =
          new ManualFeedDispatcher(
              sources,
              context.getBean(SourceService.class),
              activity,
              transport,
              context.getBean(FeedParser.class),
              context.getBean(CollectionWriter.class),
              Clock.fixed(NOW, ZoneOffset.UTC),
              true);
      dispatcher.ready();
      var personal =
          jdbc("destination")
              .sql("SELECT id,source_id,read_at,saved_at FROM articles WHERE id=:id")
              .param("id", ids[5])
              .query()
              .singleRow();
      dispatcher.tick();
      assertThat(sources.find(ids[0]).manualRequestedAt()).isNull();
      verify(transport)
          .fetch("https://active.invalid/feed", "\"synthetic\"", "Wed, 28 Oct 2026 12:00:00 GMT");
      dispatcher.tick();
      verify(transport)
          .fetch(
              "https://automatic.invalid/feed", "\"synthetic\"", "Wed, 28 Oct 2026 12:00:00 GMT");
      dispatcher.tick();
      verify(transport, times(2)).fetch(anyString(), any(), any());
      assertThat(sources.find(ids[4]).manualRequestedAt()).isNotNull();
      assertThat(sources.find(ids[4]).retryAfterAt().toInstant()).isEqualTo(NOW.plusSeconds(7200));
      assertThat(sources.find(ids[1]).status()).isEqualTo("paused");
      assertThat(sources.find(ids[2]).status()).isEqualTo("archived");
      assertThat(
              jdbc("destination")
                  .sql("SELECT id,source_id,read_at,saved_at FROM articles WHERE id=:id")
                  .param("id", ids[5])
                  .query()
                  .singleRow())
          .isEqualTo(personal);
      assertThat(
              jdbc("destination")
                  .sql("SELECT count(*) FROM articles WHERE source_id=:id")
                  .param("id", ids[0])
                  .query(Long.class)
                  .single())
          .isEqualTo(1);
      assertThat(activity.matches(ids[0], 0)).isFalse();
    }
  }

  @Test
  void t20RestoresCompleteArchiveRejectsUnsafeRecoveryAndResumesDurableCollection()
      throws Exception {
    work = Files.createTempDirectory("news-t20-");
    try (var container =
            new PostgreSQLContainer(
                    "postgres@sha256:7e32e9833a6fb1c92c32552794cb6ed569d51b445a54907d35fc112ef39684db")
                .withDatabaseName("source")
                .withUsername("t20")
                .withPassword(UUID.randomUUID().toString())
                .withLabel("news.t20.run", UUID.randomUUID().toString())
                .withLabel(
                    "news.f02.run",
                    System.getenv().getOrDefault("NEWS_E2E_RUN_ID", UUID.randomUUID().toString()));
        var client =
            HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build()) {
      postgres = container;
      postgres.start();
      jdbc("source")
          .sql("CREATE ROLE t20_restore LOGIN SUPERUSER PASSWORD '" + postgres.getPassword() + "'")
          .update();
      jdbc("source")
          .sql("CREATE DATABASE destination OWNER t20_restore TEMPLATE template0")
          .update();
      jdbc("source").sql("CREATE DATABASE negative OWNER t20_restore TEMPLATE template0").update();
      var ids = seed();
      JsonNode oldToken;
      long stoppedPid;
      try (var source = new App("source")) {
        oldToken =
            JSON.readTree(http(client, source.port, "GET", "/api/v1/auth/csrf", null, null).body());
        stoppedPid = source.process.pid();
        assertThat(
                helper(
                        "backup",
                        "source",
                        "--directory",
                        work.toString(),
                        "--stopped-pid",
                        Long.toString(stoppedPid))
                    .exit)
            .isNotZero();
      }
      var before = rows("source");
      success(
          helper(
              "backup",
              "source",
              "--directory",
              work.toString(),
              "--stopped-pid",
              Long.toString(stoppedPid)));
      Path pack;
      try (var files = Files.list(work)) {
        pack =
            files
                .filter(Files::isDirectory)
                .filter(p -> !p.toString().endsWith(".incomplete"))
                .findFirst()
                .orElseThrow();
      }
      Path receipt = work.resolve("restore.json");
      assertThat(
              helper(
                      "restore",
                      "source",
                      "--package",
                      pack.toString(),
                      "--receipt",
                      receipt.toString())
                  .exit)
          .isNotZero();
      assertThat(Files.exists(receipt)).isFalse();
      success(
          helper(
              "restore",
              "destination",
              "--package",
              pack.toString(),
              "--receipt",
              receipt.toString()));
      assertThat(rows("destination")).isEqualTo(before);
      assertThat(
              jdbc("destination")
                  .sql(
                      "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tableowner<>'t20_restore'")
                  .query(Long.class)
                  .single())
          .isZero();
      assertThat(rows("source")).isEqualTo(before);
      assertThat(
              helper(
                      "restore",
                      "destination",
                      "--package",
                      pack.toString(),
                      "--receipt",
                      work.resolve("occupied.json").toString())
                  .exit)
          .isNotZero();
      try (var target = new App("destination")) {
        assertThat(http(client, target.port, "GET", "/api/v1/articles", null, null).statusCode())
            .isEqualTo(200);
        var detail =
            JSON.readTree(
                http(client, target.port, "GET", "/api/v1/articles/" + ids[5], null, null).body());
        assertThat(detail.get("read").asBoolean()).isTrue();
        assertThat(detail.get("saved").asBoolean()).isFalse();
        assertThat(detail.get("contentHtml").asString()).doesNotContain("script");
        assertThat(
                http(client, target.port, "GET", "/api/v1/search?q=PostgreSQL", null, null).body())
            .contains(ids[5].toString());
        var states =
            JSON.readTree(http(client, target.port, "GET", "/api/v1/sources", null, null).body());
        for (var state : states) {
          assertThat(state.get("collecting").asBoolean()).isFalse();
          assertThat(state.get("collectionEnabled").asBoolean()).isFalse();
        }
        Thread.sleep(1200); // Allow at least one real cycle with collection disabled.
        assertThat(rows("destination")).isEqualTo(before);
        var absent =
            JSON.readTree(
                    http(
                            client,
                            target.port,
                            "GET",
                            "/api/v1/articles?sourceId=" + ids[1],
                            null,
                            null)
                        .body())
                .get("items")
                .get(0);
        var absentDetail =
            JSON.readTree(
                http(
                        client,
                        target.port,
                        "GET",
                        "/api/v1/articles/" + absent.get("id").asString(),
                        null,
                        null)
                    .body());
        assertThat(absentDetail.get("contentHtml").isNull()).isTrue();
        assertThat(absentDetail.get("read").asBoolean()).isFalse();
        assertThat(absentDetail.get("saved").asBoolean()).isTrue();
        String patch = "{\"saved\":true}";
        var stale =
            http(
                client,
                target.port,
                "PATCH",
                "/api/v1/articles/" + ids[5] + "/state",
                patch,
                oldToken);
        assertThat(stale.statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(stale.body()).get("code").asString()).isEqualTo("CSRF_INVALID");
        assertThat(rows("destination")).isEqualTo(before);
        var fresh =
            JSON.readTree(http(client, target.port, "GET", "/api/v1/auth/csrf", null, null).body());
        assertThat(fresh.get("token").asString()).isNotEqualTo(oldToken.get("token").asString());
        assertThat(
                http(
                        client,
                        target.port,
                        "PATCH",
                        "/api/v1/articles/" + ids[5] + "/state",
                        patch,
                        fresh)
                    .statusCode())
            .isEqualTo(200);
        assertThat(
                jdbc("destination")
                    .sql(
                        "SELECT saved_at IS NOT NULL AND read_at IS NOT NULL FROM articles WHERE id=:id")
                    .param("id", ids[5])
                    .query(Boolean.class)
                    .single())
            .isTrue();
      }
      negatives(pack, stoppedPid, before);
      resume(ids);
      assertThat(rows("source")).isEqualTo(before);
      String digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(Files.readAllBytes(pack.resolve("archive.dump"))));
      var manifest = JSON.readTree(Files.readString(pack.resolve("manifest.json")));
      var proof = new LinkedHashMap<String, Object>();
      proof.put("status", "passed");
      proof.put("versions", manifest.get("versions"));
      proof.put("jar", manifest.get("jar"));
      proof.put("dumpSha256", digest);
      proof.put("snapshot", manifest.get("snapshot"));
      proof.put("subprocessExits", exits);
      proof.put("javaExits", javaExits);
      proof.put("toolExits", toolExits);
      proof.put(
          "checks",
          List.of(
              "fullRowsBeforeBoot",
              "schemaConstraintsIndexesHistory",
              "destinationOwnership",
              "sourceUnchanged",
              "ready200",
              "listDetailSearch",
              "disabledCollection",
              "staleCsrf403",
              "renewedPatch200",
              "manualAndAutomaticResumed",
              "cooldownPreserved",
              "pausedArchivedUnchanged",
              "negativeRestoreRollback",
              "checksumTruncation",
              "sameSource",
              "occupiedTarget",
              "incompatibleVersion",
              "incompatibleBuild",
              "exportFailureIncomplete"));
      Files.writeString(
          ROOT.resolve("backend/target/t20-result.json"),
          JSON.writerWithDefaultPrettyPrinter().writeValueAsString(proof));
      System.out.println(
          "T20_VERIFIED postgres=18 sources=5 articles=2 fullRowsEqual=true constraintsIndexesHistoryEqual=true search=true staleCsrf=403 renewedPatch=200 manualAndAutomaticResumed=true cooldownPreserved=true negatives=passed dumpSha256="
              + digest
              + " subprocessExits="
              + exits);
    } finally {
      try (var paths = Files.walk(work)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
  }

  void negatives(Path pack, long pid, Object before) throws Exception {
    Path dump = pack.resolve("archive.dump"), manifest = pack.resolve("manifest.json");
    byte[] bytes = Files.readAllBytes(dump), metadata = Files.readAllBytes(manifest);
    Path rejected = work.resolve("rejected.json");
    Files.write(dump, Arrays.copyOf(bytes, bytes.length / 2));
    assertThat(
            helper(
                    "restore",
                    "negative",
                    "--package",
                    pack.toString(),
                    "--receipt",
                    rejected.toString())
                .exit)
        .isNotZero();
    Files.write(dump, bytes);
    Files.writeString(
        manifest,
        new String(metadata, StandardCharsets.UTF_8)
            .replace("\"server\": \"18.", "\"server\": \"17."));
    assertThat(
            helper(
                    "restore",
                    "negative",
                    "--package",
                    pack.toString(),
                    "--receipt",
                    rejected.toString())
                .exit)
        .isNotZero();
    Files.write(manifest, metadata);
    success(
        command(
            List.of(
                "python3",
                "-c",
                "import json,sys; from pathlib import Path; p=Path(sys.argv[1]); m=json.loads(p.read_text()); m['jar']['sha256']='0'*64; p.write_text(json.dumps(m))",
                manifest.toString())));
    var wrongBuild =
        helper(
            "restore", "negative", "--package", pack.toString(), "--receipt", rejected.toString());
    assertThat(wrongBuild.exit).isNotZero();
    assertThat(wrongBuild.output).contains("Build incompatível");
    Files.write(manifest, metadata);
    success(
        command(
            List.of(
                "python3",
                "-c",
                "import sys,json,hashlib; from pathlib import Path; p=Path(sys.argv[1]); d=p/'archive.dump'; b=d.read_bytes()[:-64]; d.write_bytes(b); m=json.loads((p/'manifest.json').read_text()); m['dump']['bytes']=len(b); m['dump']['sha256']=hashlib.sha256(b).hexdigest(); (p/'manifest.json').write_text(json.dumps(m))",
                pack.toString())));
    var failedRestore =
        helper(
            "restore", "negative", "--package", pack.toString(), "--receipt", rejected.toString());
    assertThat(failedRestore.exit).isNotZero();
    assertThat(failedRestore.output).contains("pg-restore: exit=1");
    assertThat(
            jdbc("negative")
                .sql("SELECT count(*) FROM pg_tables WHERE schemaname='public'")
                .query(Long.class)
                .single())
        .isZero();
    assertThat(Files.exists(rejected)).isFalse();
    Files.write(dump, bytes);
    Files.write(manifest, metadata);
    var failedExport =
        command(
            List.of(
                "python3",
                "-c",
                "import sys; from pathlib import Path; sys.path.insert(0,'scripts'); import backup; db=backup.Database(sys.argv[1],'source','t20'); original=db.command; db.command=lambda tool,*args: original(tool,*args,*(['--invalid-t20-option'] if tool=='pg_dump' and '--version' not in args else [])); backup.backup(db,Path(sys.argv[2]),Path(sys.argv[3]),int(sys.argv[4]))",
                postgres.getContainerId(),
                work.toString(),
                JAR.toString(),
                Long.toString(pid)));
    assertThat(failedExport.exit).isNotZero();
    assertThat(failedExport.output).contains("pg-dump: exit=1");
    try (var dirs = Files.list(work)) {
      assertThat(
              dirs.filter(Files::isDirectory)
                  .filter(p -> p.toString().endsWith(".incomplete"))
                  .count())
          .isEqualTo(1);
    }
    assertThat(Files.readAllBytes(dump)).isEqualTo(bytes);
    assertThat(rows("source")).isEqualTo(before);
  }
}
