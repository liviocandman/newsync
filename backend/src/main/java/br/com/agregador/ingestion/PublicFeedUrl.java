package br.com.agregador.ingestion;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/** Syntax-only validation at registration; DNS is checked again immediately before connection. */
public final class PublicFeedUrl {
  private PublicFeedUrl() {}

  public static String normalize(String value) {
    try {
      if (value == null || value.isBlank() || value.length() > 4096)
        throw new IllegalArgumentException();
      URI uri = new URI(value.strip());
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      String host = uri.getHost();
      if ((!scheme.equals("http") && !scheme.equals("https"))
          || host == null
          || uri.getRawUserInfo() != null
          || uri.getRawAuthority().endsWith(":")
          || uri.getRawFragment() != null
          || uri.getPort() == 0
          || uri.getPort() > 65535) throw new IllegalArgumentException();
      host = host.toLowerCase(Locale.ROOT);
      if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
      if (host.contains("%")) throw new IllegalArgumentException();
      if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
      if (host.contains(":")) {
        InetAddress address = InetAddress.getByName(host);
        if (address.getAddress().length != 16 || !isPublicAddress(address))
          throw new IllegalArgumentException();
        host = "[" + host + "]";
      } else if (host.matches("[0-9.]+")) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException();
        byte[] address = new byte[4];
        for (int i = 0; i < parts.length; i++) {
          if (parts[i].isEmpty() || (parts[i].length() > 1 && parts[i].startsWith("0")))
            throw new IllegalArgumentException();
          int octet = Integer.parseInt(parts[i]);
          if (octet > 255) throw new IllegalArgumentException();
          address[i] = (byte) octet;
        }
        if (!isPublicAddress(InetAddress.getByAddress(address)))
          throw new IllegalArgumentException();
      } else {
        if (!host.contains(".")
            || host.length() > 253
            || host.matches(".*\\.(localhost|local|internal|home|lan)$"))
          throw new IllegalArgumentException();
        boolean numericAlias = true;
        for (String label : host.split("\\.", -1)) {
          if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
            throw new IllegalArgumentException();
          String digits = label.startsWith("0x") ? label.substring(2) : label;
          numericAlias &=
              !digits.isEmpty()
                  && (label.startsWith("0x")
                      ? digits.chars().allMatch(c -> c >= '0' && c <= '9' || c >= 'a' && c <= 'f')
                      : digits.chars().allMatch(c -> c >= '0' && c <= '9'));
        }
        // Numeric/hex aliases must not be interpreted as IPv4 by a later resolver.
        if (numericAlias) throw new IllegalArgumentException();
      }
      int port = uri.getPort();
      String authority =
          host
              + (port == -1
                      || (scheme.equals("http") && port == 80)
                      || (scheme.equals("https") && port == 443)
                  ? ""
                  : ":" + port);
      String path = uri.getRawPath();
      return scheme
          + "://"
          + authority
          + (path == null || path.isEmpty() ? "/" : path)
          + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    } catch (java.net.URISyntaxException
        | java.net.UnknownHostException
        | IllegalArgumentException error) {
      throw new IllegalArgumentException("Informe uma URL HTTP ou HTTPS pública válida");
    }
  }

  static boolean isPublicAddress(InetAddress address) {
    byte[] bytes = address.getAddress();
    if (bytes.length == 4) {
      int a = bytes[0] & 255;
      int b = bytes[1] & 255;
      int c = bytes[2] & 255;
      return !(a == 0
          || a == 10
          || a == 127
          || a >= 224
          || (a == 100 && b >= 64 && b <= 127)
          || (a == 169 && b == 254)
          || (a == 172 && b >= 16 && b <= 31)
          || (a == 192 && b == 168)
          || (a == 192 && b == 0 && (c == 0 || c == 2))
          || (a == 192 && b == 88 && c == 99)
          || (a == 192 && b == 31 && c == 196)
          || (a == 192 && b == 52 && c == 193)
          || (a == 192 && b == 175 && c == 48)
          || (a == 198 && (b == 18 || b == 19))
          || (a == 198 && b == 51 && c == 100)
          || (a == 203 && b == 0 && c == 113));
    }
    // Only global unicast, excluding special-use, documentation and IPv4 tunnelling ranges.
    return bytes.length == 16
        && (bytes[0] & 0xe0) == 0x20
        && !((bytes[0] & 255) == 0x20
            && (bytes[1] & 255) == 1
            && (((bytes[2] & 255) < 2) || ((bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)))
        && !((bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 2)
        && !((bytes[0] & 255) == 0x3f && (bytes[1] & 255) == 0xff && (bytes[2] & 0xf0) == 0);
  }
}
