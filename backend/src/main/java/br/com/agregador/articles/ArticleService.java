package br.com.agregador.articles;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArticleService {
  private final ArticleRepository articles;
  private final JdbcClient jdbc;

  public ArticleService(ArticleRepository articles, JdbcClient jdbc) {
    this.articles = articles;
    this.jdbc = jdbc;
  }

  @Transactional(timeout = 10)
  public ArticleRepository.State setState(UUID id, Map<String, Object> patch) {
    if (patch == null
        || patch.isEmpty()
        || !Set.of("read", "saved").containsAll(patch.keySet())
        || patch.values().stream().anyMatch(v -> !(v instanceof Boolean)))
      throw new IllegalArgumentException("Estado inválido");
    jdbc.sql("SET LOCAL statement_timeout='5s'").update();
    jdbc.sql("SET LOCAL lock_timeout='2s'").update();
    return articles.setState(id, patch);
  }
}
