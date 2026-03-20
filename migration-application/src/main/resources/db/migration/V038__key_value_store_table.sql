CREATE TABLE key_value_store (
    store_key VARCHAR(512) PRIMARY KEY,
    store_value TEXT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_key_value_store_expires_at ON key_value_store (expires_at);
