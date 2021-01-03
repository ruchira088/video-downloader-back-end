ALTER TABLE worker_task DROP CONSTRAINT fk_worker_task_scheduled_video_id;

ALTER TABLE worker_task ADD CONSTRAINT fk_worker_task_scheduled_video_id
    FOREIGN KEY (scheduled_video_id) REFERENCES scheduled_video(video_metadata_id) ON DELETE CASCADE;