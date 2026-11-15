CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE repository (
  id               BIGSERIAL PRIMARY KEY,
  owner            TEXT NOT NULL,
  name             TEXT NOT NULL,
  default_branch   TEXT,
  status           TEXT NOT NULL,
  last_ingested_at TIMESTAMPTZ,
  content_version  TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (owner, name)
);

CREATE TABLE document (
  id            BIGSERIAL PRIMARY KEY,
  repository_id BIGINT NOT NULL REFERENCES repository(id) ON DELETE CASCADE,
  kind          TEXT NOT NULL,
  path_or_ref   TEXT NOT NULL,
  url           TEXT NOT NULL,
  title         TEXT,
  content_hash  TEXT NOT NULL,
  etag          TEXT,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (repository_id, kind, path_or_ref)
);

CREATE TABLE chunk (
  id            BIGSERIAL PRIMARY KEY,
  document_id   BIGINT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
  repository_id BIGINT NOT NULL,
  ordinal       INT NOT NULL,
  heading_path  TEXT,
  content       TEXT NOT NULL,
  token_count   INT NOT NULL,
  embedding     vector(384) NOT NULL,
  fts           tsvector GENERATED ALWAYS AS (
    to_tsvector('english', coalesce(heading_path, '') || ' ' || content)
  ) STORED
);

CREATE INDEX chunk_repo_idx ON chunk(repository_id);
CREATE INDEX chunk_fts_idx ON chunk USING GIN (fts);
CREATE INDEX chunk_vec_idx ON chunk USING hnsw (embedding vector_cosine_ops);

CREATE TABLE ingestion_job (
  id             BIGSERIAL PRIMARY KEY,
  repository_id  BIGINT NOT NULL REFERENCES repository(id) ON DELETE CASCADE,
  status         TEXT NOT NULL,
  started_at     TIMESTAMPTZ,
  finished_at    TIMESTAMPTZ,
  docs_seen      INT DEFAULT 0,
  chunks_written INT DEFAULT 0,
  error          TEXT
);

CREATE TABLE query_log (
  id                  BIGSERIAL PRIMARY KEY,
  repository_id       BIGINT,
  question            TEXT,
  retrieved_chunk_ids BIGINT[],
  latency_ms          INT,
  cached              BOOLEAN,
  refused             BOOLEAN,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE api_key (
  id         BIGSERIAL PRIMARY KEY,
  key_hash   TEXT NOT NULL UNIQUE,
  label      TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked    BOOLEAN NOT NULL DEFAULT false
);
