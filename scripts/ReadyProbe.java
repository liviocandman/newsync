import java.net.HttpURLConnection;
import java.net.URI;

/** Probe presente no JRE distribuído; não imprime corpo, cookies ou configuração. */
public final class ReadyProbe {
  public static void main(String[] args) {
    try {
      String path = args.length == 0 ? "/health/ready" : args[0];
      var connection =
          (HttpURLConnection)
              URI.create("http://127.0.0.1:8080" + path).toURL().openConnection();
      connection.setConnectTimeout(1500);
      connection.setReadTimeout(2000);
      int status = connection.getResponseCode();
      boolean content =
          args.length < 2
              || new String(
                      connection.getInputStream().readAllBytes(),
                      java.nio.charset.StandardCharsets.UTF_8)
                  .contains(args[1]);
      connection.disconnect();
      System.exit(status == 200 && content ? 0 : 1);
    } catch (Exception unavailable) {
      System.exit(1);
    }
  }
}
