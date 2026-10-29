ALTER TABLE articles ALTER COLUMN external_id DROP NOT NULL;
CREATE INDEX articles_source_url ON articles(source_id, normalized_article_url);
CREATE INDEX articles_source_feed_order ON articles(source_id, sort_at DESC, id DESC);

ALTER TABLE sources
    ADD COLUMN next_poll_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN retry_after_at timestamptz,
    ADD COLUMN manual_requested_at timestamptz,
    ADD COLUMN last_manual_refresh_at timestamptz,
    ADD COLUMN last_attempt_at timestamptz,
    ADD COLUMN consecutive_failures integer NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    ADD COLUMN etag text,
    ADD COLUMN last_modified text,
    ADD COLUMN diagnostic_code text,
    ADD COLUMN diagnostic_message text,
    ADD COLUMN ignored_items integer NOT NULL DEFAULT 0 CHECK (ignored_items >= 0);
CREATE INDEX sources_manual_pending ON sources(manual_requested_at) WHERE status = 'active' AND manual_requested_at IS NOT NULL;
CREATE INDEX sources_next_poll ON sources(next_poll_at) WHERE status = 'active';
