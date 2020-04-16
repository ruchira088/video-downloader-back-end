CREATE TABLE video_metadata(
    index BIGSERIAL,
    key VARCHAR(127),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    url VARCHAR(2047) NOT NULL UNIQUE,
    video_site VARCHAR(127) NOT NULL,
    title VARCHAR(255) NOT NULL,
    duration BIGINT NOT NULL,
    size BIGINT NOT NULL,
    media_type VARCHAR(64) NOT NULL,
    thumbnail VARCHAR(2047) NOT NULL,

    PRIMARY KEY (key)
);