
ALTER TABLE scheduled_video
    ADD COLUMN last_updated_at TIMESTAMP NULL;

ALTER TABLE scheduled_video
    ADD COLUMN status VARCHAR(16) DEFAULT 'Completed' NOT NULL;

UPDATE scheduled_video
    SET last_updated_at = completed_at
    WHERE last_updated_at IS NULL AND completed_at IS NOT NULL;

UPDATE scheduled_video
    SET last_updated_at = scheduled_at
    WHERE last_updated_at IS NULL;

ALTER TABLE scheduled_video
    ALTER COLUMN last_updated_at SET NOT NULL;