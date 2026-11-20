-- Tenants are the unit of rate limiting, budgeting and cost reporting (a team, an app, a customer).
CREATE TABLE tenants (
    id                  UUID PRIMARY KEY,
    name                TEXT        NOT NULL UNIQUE,
    requests_per_minute INT         NOT NULL DEFAULT 60 CHECK (requests_per_minute > 0),
    daily_token_budget  BIGINT      CHECK (daily_token_budget IS NULL OR daily_token_budget > 0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Only a SHA-256 hash of each key is stored; the plaintext key is shown once at creation time.
CREATE TABLE api_keys (
    id         UUID PRIMARY KEY,
    tenant_id  UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    key_prefix TEXT        NOT NULL,
    key_hash   TEXT        NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);

CREATE INDEX idx_api_keys_tenant ON api_keys (tenant_id);

-- One row per gateway request: the source of truth for usage and cost reports.
CREATE TABLE usage_events (
    id                BIGSERIAL PRIMARY KEY,
    request_id        TEXT          NOT NULL,
    tenant_id         UUID          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    api_key_id        UUID,
    requested_model   TEXT          NOT NULL,
    provider          TEXT          NOT NULL,
    upstream_model    TEXT,
    status            TEXT          NOT NULL CHECK (status IN ('success', 'error', 'cancelled')),
    streamed          BOOLEAN       NOT NULL,
    prompt_tokens     INT           NOT NULL,
    completion_tokens INT           NOT NULL,
    tokens_estimated  BOOLEAN       NOT NULL,
    cost_usd          NUMERIC(14, 6) NOT NULL,
    latency_ms        INT           NOT NULL,
    ttft_ms           INT,
    fallback_count    INT           NOT NULL DEFAULT 0,
    error             TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_usage_events_created ON usage_events (created_at DESC);
CREATE INDEX idx_usage_events_tenant_created ON usage_events (tenant_id, created_at DESC);
CREATE INDEX idx_usage_events_request ON usage_events (request_id);
