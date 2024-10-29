ALTER TABLE worker
    ADD COLUMN scheduled_video_id VARCHAR(127);

ALTER TABLE worker
    ADD CONSTRAINT fk_worker_scheduled_video_id
        FOREIGN KEY (scheduled_video_id) REFERENCES scheduled_video(video_metadata_id);

ALTER TABLE worker
    ADD CONSTRAINT uc_worker_scheduled_video_id UNIQUE (scheduled_video_id);