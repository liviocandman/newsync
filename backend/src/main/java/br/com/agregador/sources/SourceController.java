package br.com.agregador.sources;

import br.com.agregador.auth.HttpParameters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sources")
public class SourceController {
  private final SourceService service;

  public SourceController(SourceService service) {
    this.service = service;
  }

  @GetMapping
  public List<SourcePresentation.Source> list(@RequestParam Map<String, String> params) {
    HttpParameters.noParameters(params);
    return service.list();
  }

  @GetMapping("/{id}")
  public SourcePresentation.Source detail(
      @PathVariable UUID id, @RequestParam Map<String, String> params) {
    HttpParameters.noParameters(params);
    return service.detail(id);
  }

  @PostMapping
  public ResponseEntity<SourcePresentation.Source> create(
      @RequestBody Map<String, Object> body, @RequestParam Map<String, String> params) {
    HttpParameters.noParameters(params);
    var source = service.create(body);
    return ResponseEntity.created(java.net.URI.create("/api/v1/sources/" + source.id()))
        .body(source);
  }

  @PatchMapping("/{id}")
  public SourcePresentation.Source edit(
      @PathVariable UUID id,
      @RequestBody Map<String, Object> body,
      @RequestParam Map<String, String> params) {
    HttpParameters.noParameters(params);
    return service.edit(id, body);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> archive(
      @PathVariable UUID id, @RequestParam Map<String, String> params) {
    HttpParameters.noParameters(params);
    service.archive(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/refresh")
  public ResponseEntity<SourceService.Refresh> refresh(
      @PathVariable UUID id,
      @RequestParam Map<String, String> params,
      @RequestBody(required = false) Map<String, Object> body) {
    HttpParameters.noParameters(params);
    if (body != null && !body.isEmpty()) throw new IllegalArgumentException();
    return ResponseEntity.accepted().body(service.refresh(id));
  }
}
