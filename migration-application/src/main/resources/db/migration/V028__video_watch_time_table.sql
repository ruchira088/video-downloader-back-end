CREATE TABLE video_watch_time (
    video_id VARCHAR(127) NOT NULL,
    watch_time_in_ms BIGINT DEFAULT 0 NOT NULL,

    PRIMARY KEY (video_id),
    CONSTRAINT fk_video_watch_time_video_id FOREIGN KEY (video_id) REFERENCES video (video_metadata_id)
);

INSERT INTO video_watch_time (video_id, watch_time_in_ms)
    SELECT video_metadata_id, watch_time FROM video;

ALTER TABLE video DROP COLUMN watch_time;