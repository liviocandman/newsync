package br.com.agregador;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.agregador.web.PackagedUi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.collection.enabled=false")
class UiHttpIT {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    DatabaseIT.database(properties);
  }

  @LocalServerPort int port;
  @Autowired PackagedUi ui;

  HttpResponse<String> request(HttpClient client, String method, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void packagedNavigationAssetsAndApiKeepDistinctSecurityAndCachePolicies() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var id = "10000000-0000-0000-0000-000000000001";
      String html = "";
      for (String path :
          new String[] {
            "/",
            "/search?q=termo&offset=10",
            "/articles/" + id,
            "/search/articles/" + id + "?q=termo&offset=10",
            "/sources"
          }) {
        for (String method : new String[] {"GET", "HEAD"}) {
          var response = request(client, method, path);
          assertThat(response.statusCode()).isEqualTo(200);
          assertThat(response.headers().firstValue("Content-Type").orElse(""))
              .startsWith("text/html");
          assertThat(response.headers().allValues("Content-Security-Policy"))
              .containsExactly(PackagedUi.DOCUMENT_CSP);
          assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
          assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
          if (method.equals("GET")) html = response.body();
          else assertThat(response.body()).isEmpty();
        }
      }
      assertThat(html)
          .contains("name=\"news-ui-mode\" content=\"" + ui.mode() + "\"")
          .doesNotContain("nonce=", "@vite", "<style", "localhost:");
      var matcher = Pattern.compile("(?:src|href)=\"(/assets/[^\"]+)\"").matcher(html);
      int count = 0;
      while (matcher.find()) {
        String path = matcher.group(1);
        for (String method : new String[] {"GET", "HEAD"}) {
          var response = request(client, method, path);
          assertThat(response.statusCode()).isEqualTo(200);
          assertThat(response.headers().firstValue("Content-Type").orElse(""))
              .startsWith(path.endsWith(".css") ? "text/css" : "text/javascript");
          assertThat(response.headers().firstValue("Cache-Control").orElse(""))
              .contains("immutable", "31536000");
          assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
          assertThat(response.body()).doesNotContain("<!doctype");
        }
        count++;
      }
      assertThat(count).isGreaterThanOrEqualTo(2);
      for (String path : new String[] {"/assets/missing.js", "/assets/nested/missing.css"}) {
        var response = request(client, "GET", path);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("<!doctype");
        assertThat(response.headers().allValues("Content-Security-Policy"))
            .containsExactly(PackagedUi.API_CSP);
      }
      for (String path :
          new String[] {
            "/api/nope",
            "/health/nope",
            "/unknown",
            "/index.html",
            "/application.yaml",
            "/web-ui/bundle.properties",
            "/articles/invalid",
            "/search/nope"
          }) {
        var response = request(client, "GET", path);
        assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(response.body()).doesNotContain("<!doctype");
      }
      assertThat(request(client, "POST", "/search").statusCode()).isEqualTo(403);
      var api = request(client, "GET", "/api/v1/auth/csrf");
      assertThat(api.headers().allValues("Content-Security-Policy"))
          .containsExactly(PackagedUi.API_CSP);
      assertThat(api.headers().firstValue("Cache-Control")).contains("no-store");
    }
  }
}
