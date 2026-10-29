package br.com.agregador.sources;

import br.com.agregador.articles.ArticleController;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sources")
public class SourceController {
  private final SourceRepository sources;

  public SourceController(SourceRepository sources) {
    this.sources = sources;
  }

  @GetMapping
  public List<SourceRepository.Source> list(@RequestParam Map<String, String> params) {
    ArticleController.noParameters(params);
    return sources.list();
  }
}
