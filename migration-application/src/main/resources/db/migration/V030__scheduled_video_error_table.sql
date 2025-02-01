CREATE TABLE scheduled_video_error
(
    index         BIGSERIAL,
    id            VARCHAR(127),
    created_at    TIMESTAMP    NOT NULL,
    video_id      VARCHAR(127) NOT NULL,
    error_message TEXT         NOT NULL,
    error_details TEXT,

    PRIMARY KEY (id),
    CONSTRAINT fk_scheduled_video_error_video_id FOREIGN KEY (video_id) REFERENCES scheduled_video (video_metadata_id)
);

ALTER TABLE scheduled_video ADD COLUMN error_id VARCHAR(127);

ALTER TABLE scheduled_video
    ADD CONSTRAINT fk_scheduled_video_error_id
    FOREIGN KEY (error_id) REFERENCES scheduled_video_error(id);