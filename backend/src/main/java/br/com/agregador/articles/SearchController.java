package br.com.agregador.articles;

import java.util.List;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {
  private final ArticleRepository articles;

  public SearchController(ArticleRepository articles) {
    this.articles = articles;
  }

  public record Page(List<ArticleRepository.Summary> items, boolean hasMore) {
    public Page {
      items = List.copyOf(items);
    }
  }

  @GetMapping("/api/v1/search")
  public Page search(@RequestParam MultiValueMap<String, String> params) {
    var request = SearchRequest.parse(params);
    var rows = articles.search(request);
    return new Page(
        rows.subList(0, Math.min(rows.size(), request.limit())), rows.size() > request.limit());
  }
}
