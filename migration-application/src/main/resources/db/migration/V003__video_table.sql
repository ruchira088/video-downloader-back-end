CREATE TABLE video(
    index BIGSERIAL,
    key VARCHAR(127),
    downloaded_at TIMESTAMP NOT NULL,
    path VARCHAR(2047) NOT NULL,

    PRIMARY KEY (key),

    CONSTRAINT fk_video_video_key FOREIGN KEY (key) REFERENCES video_metadata(key)
);