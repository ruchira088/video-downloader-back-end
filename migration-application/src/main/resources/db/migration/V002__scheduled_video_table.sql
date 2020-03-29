CREATE TABLE scheduled_video(
    index BIGSERIAL,
    scheduled_at TIMESTAMP NOT NULL,
    last_updated_at TIMESTAMP NOT NULL,
    in_progress BOOLEAN NOT NULL,
    url VARCHAR(2047) NOT NULL,
    downloaded_bytes BIGINT,
    completed_at TIMESTAMP NULL,

    PRIMARY KEY (url),

    CONSTRAINT fk_scheduled_video_uri FOREIGN KEY (url) REFERENCES video_metadata(url)
);