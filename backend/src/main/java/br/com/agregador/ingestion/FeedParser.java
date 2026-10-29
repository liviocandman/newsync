package br.com.agregador.ingestion;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.XMLOutputter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

@Component
public class FeedParser {
  private static final int MAX_BYTES = 5 * 1024 * 1024;
  private static final int MAX_ARTICLE_CHARACTERS = 256 * 1024;

  public record Item(
      String key,
      String externalId,
      String title,
      String url,
      String author,
      String html,
      String text,
      Instant publishedAt,
      boolean urlAmbiguous) {
    public Item(
        String key,
        String externalId,
        String title,
        String url,
        String author,
        String html,
        String text,
        Instant publishedAt) {
      this(key, externalId, title, url, author, html, text, publishedAt, false);
    }

    public String normalizedUrl() {
      return normalizeArticleUrl(url);
    }
  }

  public record Feed(List<Item> items, boolean partial, int ignoredItems) {
    public Feed {
      items = List.copyOf(items);
    }

    public Feed(List<Item> items, boolean partial) {
      this(items, partial, partial ? 1 : 0);
    }
  }

  public Feed parse(InputStream input) {
    return parse(input, null);
  }

  public Feed parse(InputStream input, String contentType) {
    try {
      byte[] xml = input.readNBytes(MAX_BYTES + 1);
      if (xml.length > MAX_BYTES) throw new IllegalArgumentException("Feed excede 5 MiB");
      SAXBuilder builder = new SAXBuilder();
      builder.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      builder.setFeature("http://xml.org/sax/features/external-general-entities", false);
      builder.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      builder.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      builder.setExpandEntities(false);
      builder.setProperty("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", 128);
      builder.setEntityResolver(
          (publicId, systemId) -> {
            throw new org.xml.sax.SAXException("Recurso externo proibido");
          });
      org.jdom2.Document document;
      // Without an explicit HTTP charset, XML declaration/BOM determine the encoding.
      // ROME otherwise defaults text/xml to US-ASCII, corrupting valid UTF-8 feeds.
      if (contentType == null
          || !java.util.regex.Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=")
              .matcher(contentType)
              .find()) document = builder.build(new ByteArrayInputStream(xml));
      else {
        try (var reader = new XmlReader(new ByteArrayInputStream(xml), contentType, false)) {
          document = builder.build(reader);
        }
      }
      var rawEntries = rawEntries(document.getRootElement());
      if (rawEntries.size() > 1000) throw new IllegalArgumentException("Feed excede 1000 itens");
      for (Element raw : rawEntries) {
        if (new XMLOutputter().outputString(raw).length() > MAX_ARTICLE_CHARACTERS)
          throw new IllegalArgumentException("Artigo excede limite de caracteres");
      }
      var feed = new SyndFeedInput().build(document);
      if (feed.getEntries().size() != rawEntries.size())
        throw new IllegalArgumentException("Estrutura de feed não suportada");
      List<Item> candidates = new ArrayList<>();
      Map<String, Long> urls = new HashMap<>();
      int ignored = 0;
      for (int index = 0; index < rawEntries.size(); index++) {
        SyndEntry entry = feed.getEntries().get(index);
        Element raw = rawEntries.get(index);
        boolean atom = "entry".equals(raw.getName());
        String id = optional(childText(raw, atom ? "id" : "guid"));
        if (id == null && "http://purl.org/rss/1.0/".equals(raw.getNamespaceURI()))
          id =
              optional(
                  raw.getAttributeValue(
                      "about",
                      Namespace.getNamespace("http://www.w3.org/1999/02/22-rdf-syntax-ns#")));
        String normalizedUrl = normalizeArticleUrl(entry.getLink());
        if (normalizedUrl != null) urls.merge(normalizedUrl, 1L, Long::sum);
        String title = optional(entry.getTitle());
        if (title == null) {
          ignored++;
          continue;
        }
        String body = firstBody(raw, atom);
        String html = clean(body);
        Instant published =
            entry.getPublishedDate() == null ? null : entry.getPublishedDate().toInstant();
        candidates.add(
            new Item(
                null,
                id,
                title,
                safeUrl(entry.getLink()),
                optional(entry.getAuthor()),
                html,
                html == null ? null : Jsoup.parse(html).text(),
                published));
      }
      List<Item> items = new ArrayList<>();
      Set<String> keys = new HashSet<>();
      for (Item item : candidates) {
        String key =
            item.externalId() != null
                ? key("id", item.externalId())
                : item.normalizedUrl() != null && urls.get(item.normalizedUrl()) == 1
                    ? key("url", item.normalizedUrl())
                    : fingerprint(item);
        if (!keys.add(key)) {
          ignored++;
          continue;
        }
        items.add(
            new Item(
                key,
                item.externalId(),
                item.title(),
                item.url(),
                item.author(),
                item.html(),
                item.text(),
                item.publishedAt(),
                item.normalizedUrl() != null && urls.get(item.normalizedUrl()) > 1));
      }
      if (items.isEmpty() && ignored > 0) throw new IllegalArgumentException("Nenhum item válido");
      return new Feed(List.copyOf(items), ignored > 0, ignored);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("XML de feed inválido ou proibido", e);
    }
  }

  private static List<Element> rawEntries(Element root) {
    if ("feed".equals(root.getName())
        && ("http://www.w3.org/2005/Atom".equals(root.getNamespaceURI())
            || "http://purl.org/atom/ns#".equals(root.getNamespaceURI())))
      return root.getChildren("entry", root.getNamespace());
    if ("rss".equals(root.getName())) {
      Element channel = root.getChild("channel", root.getNamespace());
      if (channel != null) return channel.getChildren("item", root.getNamespace());
    }
    if ("RDF".equals(root.getName()))
      return root.getChildren("item", Namespace.getNamespace("http://purl.org/rss/1.0/"));
    throw new IllegalArgumentException("Documento não é RSS/Atom");
  }

  private static String childText(Element element, String name) {
    return element.getChildText(name, element.getNamespace());
  }

  private static String firstBody(Element raw, boolean atom) {
    for (Element child : raw.getChildren()) {
      if ((atom
              && "content".equals(child.getName())
              && child.getNamespace().equals(raw.getNamespace()))
          || (!atom
              && "encoded".equals(child.getName())
              && "http://purl.org/rss/1.0/modules/content/".equals(child.getNamespaceURI()))) {
        String body =
            optional(
                "xhtml".equals(child.getAttributeValue("type"))
                    ? new XMLOutputter().outputString(child.getContent())
                    : child.getText());
        if (body != null) return body;
      }
    }
    return optional(childText(raw, atom ? "summary" : "description"));
  }

  static String key(String kind, String value) {
    try {
      return "v1:"
          + kind
          + ":"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String fingerprint(Item item) {
    // Length-prefixed fields avoid delimiter collisions; never include collection time.
    StringBuilder value = new StringBuilder();
    for (String field :
        Arrays.asList(
            item.externalId(),
            item.normalizedUrl(),
            normalizedTitle(item.title()),
            item.author(),
            item.text(),
            item.publishedAt() == null ? null : item.publishedAt().toString())) {
      value.append(field == null ? -1 : field.length()).append(':');
      if (field != null) value.append(field);
    }
    return key("fp", value.toString());
  }

  static String normalizedTitle(String title) {
    return title.replaceAll("(?U)\\s+", " ").strip().toLowerCase(Locale.ROOT);
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
    return normalizeArticleUrl(url) == null ? null : URI.create(url).toASCIIString();
  }

  static String normalizeArticleUrl(String url) {
    try {
      URI uri = URI.create(url);
      String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
      if (!("https".equals(scheme) || "http".equals(scheme))
          || uri.getHost() == null
          || uri.getUserInfo() != null) return null;
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      int port = uri.getPort();
      if (port == 80 && scheme.equals("http") || port == 443 && scheme.equals("https")) port = -1;
      if (port < -1 || port == 0 || port > 65535) return null;
      String path = uri.getRawPath();
      return scheme
          + "://"
          + host
          + (port == -1 ? "" : ":" + port)
          + (path == null || path.isEmpty() ? "/" : path)
          + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    } catch (Exception e) {
      return null;
    }
  }
}
