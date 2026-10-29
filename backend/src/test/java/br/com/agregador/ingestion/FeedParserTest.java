package br.com.agregador.ingestion;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FeedParserTest {
  final FeedParser parser = new FeedParser();

  @Test
  void treatsEmptyMarkupAndRemovedActiveContentAsAbsentBody() {
    assertThat(
            FeedParser.clean(
                "<p> </p><script>alert(1)</script><img src='https://example.invalid/tracker'>"))
        .isNull();
  }

  @Test
  void sanitizesHtmlThatWillBeStored() throws Exception {
    try (var input = getClass().getResourceAsStream("/fixtures/hostile.xml")) {
      var item = parser.parse(input).items().getFirst();
      assertThat(item.html())
          .doesNotContain(
              "script", "onclick", "javascript:", "img", "src=", "srcset", "iframe", "form");
      assertThat(item.text())
          .isEqualTo(
              "Texto seguro. Link perigoso Link de dados Link FTP Link de email Link seguro Link HTTP");
      assertThat(item.html()).contains("https://example.invalid/seguro", "noopener noreferrer");
    }
  }

  @Test
  void rejectsExternalEntitiesWithoutMakingARequest() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/secret",
        exchange -> {
          requests.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    try {
      String xml =
          new String(
                  getClass().getResourceAsStream("/fixtures/forbidden.xml").readAllBytes(),
                  StandardCharsets.UTF_8)
              .replace(
                  "http://127.0.0.1:1/secret",
                  "http://127.0.0.1:" + server.getAddress().getPort() + "/secret");
      assertThatThrownBy(
              () -> parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(requests).hasValue(0);
    } finally {
      server.stop(0);
    }
  }
}
