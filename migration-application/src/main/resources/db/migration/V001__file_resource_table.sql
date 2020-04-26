CREATE TABLE file_resource (
    index BIGSERIAL,
    id VARCHAR(127),
    created_at TIMESTAMP NOT NULL,
    path VARCHAR(2047) UNIQUE NOT NULL,
    media_type VARCHAR(63) NOT NULL,
    size BIGINT NOT NULL,

    PRIMARY KEY (id)
);