CREATE TABLE worker_lock(
    index BIGSERIAL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    id VARCHAR(64) NOT NULL,
    lock_acquired_at TIMESTAMP NULL,
    scheduled_video_id VARCHAR(127) NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_worker_lock_scheduled_video_id FOREIGN KEY (scheduled_video_id) REFERENCES scheduled_video(video_metadata_id)
);

INSERT INTO worker_lock(id) VALUES('f547bfcd-21f7-4897-a779-2c6817657d43');
INSERT INTO worker_lock(id) VALUES('2151fb19-8419-49a4-9025-bfecf7734133');