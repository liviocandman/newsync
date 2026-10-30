package br.com.agregador.ingestion;

import java.io.InputStream;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FeedImporter {
  private final FeedParser parser;
  private final CollectionWriter writer;

  public FeedImporter(FeedParser parser, CollectionWriter writer) {
    this.parser = parser;
    this.writer = writer;
  }

  // Parse outside the transaction; confirmation goes through another bean proxy.
  public void importFeed(UUID sourceId, long version, InputStream input) {
    var feed = parser.parse(input);
    writer.confirm(sourceId, version, feed);
  }
}
