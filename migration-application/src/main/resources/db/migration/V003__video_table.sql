CREATE TABLE video(
    index BIGSERIAL,
    url VARCHAR(2047) NOT NULL,
    downloaded_at TIMESTAMP NOT NULL,
    path VARCHAR(2047) NOT NULL,

    PRIMARY KEY (url),

    CONSTRAINT fk_video_video_url FOREIGN KEY (url) REFERENCES video_metadata(url)
);