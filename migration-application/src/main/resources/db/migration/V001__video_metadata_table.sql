CREATE TABLE video_metadata(
    index BIGSERIAL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    url VARCHAR(2047),
    video_site VARCHAR(127) NOT NULL,
    title VARCHAR(255) NOT NULL,
    duration BIGINT NOT NULL,
    size BIGINT NOT NULL,
    thumbnail VARCHAR(2047) NOT NULL,

    PRIMARY KEY (url)
);