package br.com.agregador.sources;

import br.com.agregador.auth.HttpParameters;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sources")
public class SourceController {
  private final SourceRepository sources;
  private final SourceService service;

  public SourceController(SourceRepository sources, SourceService service) {
    this.sources = sources;
    this.service = service;
  }

  @GetMapping
  public List<SourceRepository.Source> list(@RequestParam Map<String, String> params) {
    HttpParameters.noParameters(params);
    return sources.list();
  }

  @GetMapping("/{id}")
  public SourceRepository.Source detail(
      @PathVariable UUID id, @RequestParam Map<String, String> params) {
    HttpParameters.noParameters(params);
    return sources.view(sources.find(id));
  }

  @PostMapping
  public ResponseEntity<SourceRepository.Source> create(
      @RequestBody Map<String, Object> body, @RequestParam Map<String, String> params) {
    HttpParameters.noParameters(params);
    var source = service.create(body);
    return ResponseEntity.created(java.net.URI.create("/api/v1/sources/" + source.id()))
        .body(source);
  }

  @PatchMapping("/{id}")
  public SourceRepository.Source edit(
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
