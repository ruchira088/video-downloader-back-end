DROP TABLE worker_task;

CREATE TABLE worker_task(
    index BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    worker_id VARCHAR(64) NOT NULL,
    scheduled_video_id VARCHAR(127),
    completed_at TIMESTAMP NULL,

    CONSTRAINT fk_worker_task_worker_id
        FOREIGN KEY (worker_id) REFERENCES worker(id),

    CONSTRAINT fk_worker_task_scheduled_video_id
        FOREIGN KEY (scheduled_video_id) REFERENCES scheduled_video(video_metadata_id)
);