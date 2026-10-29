package br.com.agregador.articles;

import static br.com.agregador.auth.HttpParameters.noParameters;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/articles")
public class ArticleController {
  private final ArticleRepository articles;
  private final ArticleService service;

  private final ArticlePagination pagination;

  public ArticleController(
      ArticleRepository articles, ArticleService service, ArticlePagination pagination) {
    this.pagination = pagination;
    this.articles = articles;
    this.service = service;
  }

  public record Page(List<ArticleRepository.Summary> items, String nextCursor) {
    public Page {
      items = List.copyOf(items);
    }
  }

  @GetMapping
  public Page list(@RequestParam org.springframework.util.MultiValueMap<String, String> params) {
    var request = pagination.parse(params);
    var rows = articles.page(request);
    boolean more = rows.size() > request.limit();
    var visible = rows.subList(0, Math.min(rows.size(), request.limit()));
    String cursor =
        more
            ? pagination.cursor(request, visible.getLast().sortAt(), visible.getLast().id())
            : null;
    return new Page(visible.stream().map(ArticleRepository.FeedRow::summary).toList(), cursor);
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
}
