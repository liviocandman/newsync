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

  private FeedParser.Feed parse(String xml) {
    return parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  private String rss(String items) {
    return "<rss version='2.0' xmlns:content='http://purl.org/rss/1.0/modules/content/'><channel>"
        + "<title>Teste</title><link>https://example.invalid/</link><description>Teste</description>"
        + items
        + "</channel></rss>";
  }

  @Test
  void acceptsMissingGuidAndKeepsEditorialLinkSeparateFromComments() {
    var item =
        parse(
                rss(
                    "<item><title>Artigo</title><link>https://EXAMPLE.invalid/a?q=1#trecho</link>"
                        + "<comments>https://example.invalid/a/comments</comments></item>"))
            .items()
            .getFirst();
    assertThat(item.externalId()).isNull();
    assertThat(item.url()).isEqualTo("https://EXAMPLE.invalid/a?q=1#trecho");
    assertThat(item.normalizedUrl()).isEqualTo("https://example.invalid/a?q=1");
    assertThat(item.key()).isEqualTo(FeedParser.key("url", "https://example.invalid/a?q=1"));
  }

  @Test
  void preservesOpaqueGuidAndChoosesFirstNonemptyEncodedBodyInDocumentOrder() {
    var item =
        parse(
                rss(
                    "<item><guid isPermaLink='false'>opaque:ID?signature=A%2fb&amp;x=1</guid><title>Artigo</title>"
                        + "<content:encoded> </content:encoded><content:encoded><![CDATA[<!DOCTYPE html><p>Primeiro corpo</p>]]></content:encoded>"
                        + "<content:encoded><![CDATA[<p>Resumo posterior</p>]]></content:encoded><description>Fallback</description></item>"))
            .items()
            .getFirst();
    assertThat(item.externalId()).isEqualTo("opaque:ID?signature=A%2fb&x=1");
    assertThat(item.html()).isEqualTo("<p>Primeiro corpo</p>");
    assertThat(item.text()).isEqualTo("Primeiro corpo");
  }

  @Test
  void distinguishesEmptySuccessPartialAndEntirelyInvalidDocuments() {
    assertThat(parse(rss("")).items()).isEmpty();
    assertThat(parse(rss("")).partial()).isFalse();
    var partial =
        parse(rss("<item><title>Válido</title></item><item><guid>sem-titulo</guid></item>"));
    assertThat(partial.items()).hasSize(1);
    assertThat(partial.partial()).isTrue();
    assertThat(partial.ignoredItems()).isEqualTo(1);
    assertThatThrownBy(() -> parse(rss("<item><guid>sem-titulo</guid></item>")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Nenhum item válido");
    assertThatThrownBy(() -> parse("<html><body>Erro</body></html>"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void producesDeterministicFingerprintsForAmbiguousUrlsAndMissingIdentity() {
    String xml =
        rss(
            "<item><title>Um</title><link>https://example.invalid/same</link></item>"
                + "<item><title>Dois</title><link>https://example.invalid/same</link></item><item><title>Três</title></item>");
    var first = parse(xml);
    assertThat(first.items())
        .hasSize(3)
        .allSatisfy(item -> assertThat(item.key()).startsWith("v1:fp:"));
    assertThat(parse(xml)).isEqualTo(first);
    assertThat(first.items().stream().map(FeedParser.Item::key).distinct()).hasSize(3);
  }

  @Test
  void enforcesXmlItemAndArticleLimitsWithoutTruncation() {
    assertThatThrownBy(() -> parse(rss("<item><title>" + "x".repeat(262145) + "</title></item>")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Artigo excede");
    assertThatThrownBy(() -> parse(rss("<item><title>A</title></item>".repeat(1001))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1000 itens");
    assertThatThrownBy(() -> parse(" ".repeat(5 * 1024 * 1024 + 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("5 MiB");
    assertThatThrownBy(() -> parse(rss("<item><title>Malformado</item>")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsXmlDeclaredCharsetAndBom() {
    String xml =
        "<?xml version='1.0' encoding='ISO-8859-1'?>" + rss("<item><title>Ação</title></item>");
    var bytes = xml.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    assertThat(parser.parse(new ByteArrayInputStream(bytes)).items().getFirst().title())
        .isEqualTo("Ação");
    assertThat(parse("\ufeff" + rss("<item><title>Ação</title></item>")).items().getFirst().title())
        .isEqualTo("Ação");
  }

  @Test
  void rejectsOversizedAttributesAndExcessivelyNestedXml() {
    assertThatThrownBy(
            () -> parse(rss("<item secret='" + "x".repeat(262145) + "'><title>A</title></item>")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Artigo excede");
    assertThatThrownBy(
            () ->
                parse(
                    rss(
                        "<item><title>A</title>"
                            + "<nested>".repeat(150)
                            + "x"
                            + "</nested>".repeat(150)
                            + "</item>")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void honorsHttpCharsetWithoutXmlDeclarationAndRejectsConflictingBom() {
    String xml = rss("<item><title>Ação</title></item>");
    byte[] latin = xml.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    assertThat(
            parser
                .parse(new ByteArrayInputStream(latin), "application/rss+xml; charset=ISO-8859-1")
                .items()
                .getFirst()
                .title())
        .isEqualTo("Ação");
    assertThat(
            parser
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "text/xml")
                .items()
                .getFirst()
                .title())
        .isEqualTo("Ação");
    byte[] utf16 = xml.getBytes(java.nio.charset.StandardCharsets.UTF_16);
    assertThat(
            parser
                .parse(new ByteArrayInputStream(utf16), "application/rss+xml; charset=UTF-16")
                .items()
                .getFirst()
                .title())
        .isEqualTo("Ação");
    assertThatThrownBy(
            () ->
                parser.parse(new ByteArrayInputStream(utf16), "application/rss+xml; charset=UTF-8"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
