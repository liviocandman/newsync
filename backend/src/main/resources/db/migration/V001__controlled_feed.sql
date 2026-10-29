CREATE TABLE sources (
    id uuid PRIMARY KEY,
    feed_url text NOT NULL,
    normalized_feed_url text NOT NULL UNIQUE,
    title text NOT NULL,
    status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'paused', 'archived')),
    lifecycle_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    last_result text NOT NULL DEFAULT 'never' CHECK (last_result IN ('never','success','not_modified','partial','failed')),
    last_success_at timestamptz
);

CREATE TABLE articles (
    id uuid PRIMARY KEY,
    source_id uuid NOT NULL REFERENCES sources(id),
    dedup_key text NOT NULL,
    external_id text NOT NULL,
    original_url text,
    normalized_article_url text,
    title text NOT NULL,
    author text,
    content_html text,
    content_text text,
    content_origin text NOT NULL DEFAULT 'feed' CHECK (content_origin = 'feed'),
    completeness text NOT NULL DEFAULT 'unknown' CHECK (completeness = 'unknown'),
    published_at timestamptz,
    sort_at timestamptz NOT NULL,
    first_seen_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    read_at timestamptz,
    saved_at timestamptz,
    UNIQUE (source_id, dedup_key),
    CHECK ((content_html IS NULL) = (content_text IS NULL))
);
CREATE INDEX articles_feed_order ON articles(sort_at DESC, id DESC);
