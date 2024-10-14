CREATE TABLE video_watch_history(
    id              VARCHAR(127) NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    last_updated_at TIMESTAMP    NOT NULL,
    user_id         VARCHAR(127) NOT NULL,
    video_id        VARCHAR(127) NOT NULL,
    duration_in_ms  BIGINT       NOT NULL,

    CONSTRAINT fk_video_watch_history_user_id FOREIGN KEY (user_id) REFERENCES api_user (id),
    CONSTRAINT fk_video_watch_history_video_id FOREIGN KEY (video_id) REFERENCES video (video_metadata_id),
    PRIMARY KEY (id)
);

CREATE INDEX video_watch_history_index on video_watch_history (user_id, video_id, last_updated_at);