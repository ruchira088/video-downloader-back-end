CREATE TABLE scheduled_video(
    index BIGSERIAL,
    scheduled_at TIMESTAMP NOT NULL,
    url VARCHAR(2047) NOT NULL,

    CONSTRAINT fk_video_metadata_uri FOREIGN KEY (url) REFERENCES video_metadata(url)
);