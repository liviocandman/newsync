package br.com.agregador.articles;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/articles")
public class ArticleController {
  private final ArticleRepository articles;
  private final ArticleService service;

  public ArticleController(ArticleRepository articles, ArticleService service) {
    this.articles = articles;
    this.service = service;
  }

  public record Page(List<ArticleRepository.Summary> items, String nextCursor) {}

  @GetMapping
  public Page list(@RequestParam Map<String, String> params) {
    noParameters(params);
    return new Page(articles.list(), null);
  }

  @GetMapping("/{id}")
  public ArticleRepository.Article detail(
      @PathVariable UUID id, @RequestParam Map<String, String> params) {
    noParameters(params);
    return articles.find(id);
  }

  @PatchMapping("/{id}/state")
  public ArticleRepository.State state(
      @PathVariable UUID id,
      @RequestBody Map<String, Object> patch,
      @RequestParam Map<String, String> params) {
    noParameters(params);
    return service.setState(id, patch);
  }

  public static void noParameters(Map<String, String> params) {
    if (!params.isEmpty()) throw new UnsupportedParameters();
  }

  public static class UnsupportedParameters extends IllegalArgumentException {}
}
