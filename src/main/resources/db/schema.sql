-- =====================================================================
-- Settlement Monitoring API — PostgreSQL schema (production)
-- In dev/H2 the schema is auto-generated; in prod it is migration-managed
-- (Flyway/Liquibase) and Hibernate runs with ddl-auto=validate.
-- =====================================================================

CREATE TABLE IF NOT EXISTS payments (
    payment_id      VARCHAR(64)     PRIMARY KEY,
    payment_method  VARCHAR(32)     NOT NULL,
    country         VARCHAR(8)      NOT NULL,
    processor       VARCHAR(64),
    currency        CHAR(3)         NOT NULL,
    amount          NUMERIC(19, 2)  NOT NULL CHECK (amount > 0),
    captured_at     TIMESTAMPTZ     NOT NULL,
    settled_at      TIMESTAMPTZ,
    cross_border    BOOLEAN         NOT NULL DEFAULT FALSE,
    failed          BOOLEAN         NOT NULL DEFAULT FALSE,
    ingested_at     TIMESTAMPTZ     NOT NULL DEFAULT now(),
    version         BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT chk_settled_after_captured
        CHECK (settled_at IS NULL OR settled_at >= captured_at)
);

-- Filter/aggregation paths used by the analytics + query endpoints.
CREATE INDEX IF NOT EXISTS idx_payment_method      ON payments (payment_method);
CREATE INDEX IF NOT EXISTS idx_payment_country      ON payments (country);
CREATE INDEX IF NOT EXISTS idx_payment_processor    ON payments (processor);
CREATE INDEX IF NOT EXISTS idx_payment_captured_at  ON payments (captured_at);

-- Composite index for the most common analytics grouping (method, country).
CREATE INDEX IF NOT EXISTS idx_payment_method_country ON payments (payment_method, country);

-- Partial index: "what is still in transit?" is the hottest operational query;
-- indexing only unsettled rows keeps it tiny and fast as settled volume grows.
CREATE INDEX IF NOT EXISTS idx_payment_unsettled
    ON payments (captured_at)
    WHERE settled_at IS NULL AND failed = FALSE;
