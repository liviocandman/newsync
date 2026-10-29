package br.com.agregador.ingestion;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.io.SyndFeedInput;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.jdom2.input.SAXBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

@Component
public class FeedParser {
  private static final int MAX_BYTES = 5 * 1024 * 1024;

  public record Item(
      String key,
      String externalId,
      String title,
      String url,
      String author,
      String html,
      String text,
      Instant publishedAt) {}

  public record Feed(List<Item> items, boolean partial) {}

  public Feed parse(InputStream input) {
    try {
      byte[] xml = input.readNBytes(MAX_BYTES + 1);
      if (xml.length > MAX_BYTES) throw new IllegalArgumentException("Feed excede 5 MiB");
      SAXBuilder builder = new SAXBuilder();
      builder.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      builder.setFeature("http://xml.org/sax/features/external-general-entities", false);
      builder.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      builder.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      builder.setExpandEntities(false);
      builder.setEntityResolver(
          (publicId, systemId) -> {
            throw new org.xml.sax.SAXException("Recurso externo proibido");
          });
      var feed = new SyndFeedInput().build(builder.build(new ByteArrayInputStream(xml)));
      if (feed.getEntries().size() > 1000)
        throw new IllegalArgumentException("Feed excede 1000 itens");
      List<Item> items = new ArrayList<>();
      boolean partial = false;
      Set<String> keys = new HashSet<>();
      for (SyndEntry entry : feed.getEntries()) {
        String id = optional(entry.getUri());
        String title = optional(entry.getTitle());
        if (id == null || title == null) {
          partial = true;
          continue;
        }
        String body =
            entry.getContents().stream()
                .map(c -> optional(c.getValue()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (body == null && entry.getDescription() != null)
          body = optional(entry.getDescription().getValue());
        if (title.length() > 262144 || (body != null && body.length() > 262144))
          throw new IllegalArgumentException("Artigo excede limite de caracteres");
        String html = clean(body);
        String key =
            "v1:id:"
                + HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(id.getBytes(StandardCharsets.UTF_8)));
        if (!keys.add(key)) {
          partial = true;
          continue;
        }
        items.add(
            new Item(
                key,
                id,
                title,
                safeUrl(entry.getLink()),
                optional(entry.getAuthor()),
                html,
                html == null ? null : Jsoup.parse(html).text(),
                entry.getPublishedDate() == null ? null : entry.getPublishedDate().toInstant()));
      }
      if (items.isEmpty() && partial) throw new IllegalArgumentException("Nenhum item válido");
      return new Feed(List.copyOf(items), partial);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("XML de feed inválido ou proibido", e);
    }
  }

  static String optional(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  static String clean(String body) {
    if (body == null) return null;
    Safelist allowed =
        Safelist.basic()
            .removeProtocols("a", "href", "ftp", "mailto")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer");
    String html =
        optional(Jsoup.clean(body, "", allowed, new Document.OutputSettings().prettyPrint(false)));
    return html == null || Jsoup.parse(html).text().isBlank() ? null : html;
  }

  static String safeUrl(String url) {
    try {
      URI uri = URI.create(url);
      return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
              && uri.getHost() != null
              && uri.getUserInfo() == null
          ? uri.toASCIIString()
          : null;
    } catch (Exception e) {
      return null;
    }
  }
}
