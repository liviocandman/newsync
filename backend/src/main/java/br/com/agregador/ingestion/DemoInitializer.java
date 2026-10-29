package br.com.agregador.ingestion;

import br.com.agregador.sources.SourceRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
public class DemoInitializer implements ApplicationRunner {
  private final SourceRepository sources;
  private final FeedImporter importer;

  public DemoInitializer(SourceRepository sources, FeedImporter importer) {
    this.sources = sources;
    this.importer = importer;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    // Associação fixa. URL de metadados nunca é resolvida/conectada.
    var id = sources.register("https://example.invalid/b00/feed.xml", "Caderno de demonstração");
    try (var input = new ClassPathResource("demo/feed.xml").getInputStream()) {
      importer.importFeed(id, 0, input);
    }
  }
}
