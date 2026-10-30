package br.com.agregador.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.NoSuchElementException;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UiController {
  private final PackagedUi ui;

  public UiController(PackagedUi ui) {
    this.ui = ui;
  }

  @GetMapping({
    "/",
    "/search",
    "/sources",
    "/articles/{id:" + PackagedUi.UUID_PATH + "}",
    "/search/articles/{id:" + PackagedUi.UUID_PATH + "}"
  })
  public ResponseEntity<Resource> shell() {
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(ui.index());
  }

  @GetMapping("/assets/**")
  public ResponseEntity<Resource> asset(HttpServletRequest request) {
    Resource file = ui.asset(request.getServletPath());
    if (file == null) throw new NoSuchElementException();
    return ResponseEntity.ok()
        .contentType(
            MediaType.parseMediaType(
                request.getServletPath().endsWith(".css") ? "text/css" : "text/javascript"))
        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
        .body(file);
  }
}
