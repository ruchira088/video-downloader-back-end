
CREATE TABLE file_sync (
    locked_at TIMESTAMP NOT NULL,
    path VARCHAR(2047) UNIQUE NOT NULL,
    synced_at TIMESTAMP NULL,

    PRIMARY KEY (path)
);