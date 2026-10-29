ALTER TABLE articles ADD COLUMN search_document tsvector NOT NULL DEFAULT ''::tsvector;

UPDATE articles SET search_document =
    setweight(to_tsvector('simple', title), 'A') ||
    setweight(to_tsvector('simple', coalesce(content_text, '')), 'B');

CREATE INDEX articles_search_document ON articles USING GIN (search_document);
