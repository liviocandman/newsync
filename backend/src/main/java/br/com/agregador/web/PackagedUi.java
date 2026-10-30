package br.com.agregador.web;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** Closed build inventory; never resolve a path supplied by a visitor. */
@Component
public final class PackagedUi {
  public static final String DOCUMENT_CSP =
      "default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self';"
          + " connect-src 'self'; img-src 'none'; object-src 'none'; frame-src 'none';"
          + " base-uri 'none'; frame-ancestors 'none'; form-action 'self'";
  public static final String API_CSP =
      "default-src 'none'; frame-ancestors 'none'; base-uri 'none'";
  public static final String UUID_PATH =
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
  private static final Pattern DETAIL = Pattern.compile("/(?:search/)?articles/" + UUID_PATH);
  private final Map<String, Resource> assets;
  private final String mode;
  private final Resource index = new ClassPathResource("web-ui/index.html");

  public PackagedUi() throws IOException {
    Properties bundle = new Properties();
    try (var input = new ClassPathResource("web-ui/bundle.properties").getInputStream()) {
      bundle.load(input);
    }
    mode = bundle.getProperty("mode", "");
    if (!Set.of("normal", "demo").contains(mode) || !index.exists())
      throw new IllegalStateException("UI empacotada ausente ou incompatível");
    Map<String, Resource> files = new HashMap<>();
    for (String name : bundle.getProperty("assets", "").split(",")) {
      if (!name.matches("[a-zA-Z0-9_-]+-[a-zA-Z0-9_-]{8,}\\.(js|css)"))
        throw new IllegalStateException("Inventário UI inválido");
      var file = new ClassPathResource("web-ui/assets/" + name);
      if (!file.exists()) throw new IllegalStateException("Asset UI ausente");
      files.put("/assets/" + name, file);
    }
    assets = Map.copyOf(files);
  }

  public String mode() {
    return mode;
  }

  public boolean navigation(String path, boolean demo) {
    return path.equals("/")
        || path.equals("/search")
        || (!demo && path.equals("/sources"))
        || DETAIL.matcher(path).matches();
  }

  public Resource index() {
    return index;
  }

  public Resource asset(String path) {
    return assets.get(path);
  }
}
