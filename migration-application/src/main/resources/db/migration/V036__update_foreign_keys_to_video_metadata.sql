ALTER TABLE video_perceptual_hash DROP CONSTRAINT fk_video_perceptual_hash_video;
ALTER TABLE video_perceptual_hash
    ADD CONSTRAINT fk_video_perceptual_hash_video FOREIGN KEY (video_id) REFERENCES video_metadata (id);

ALTER TABLE duplicate_video DROP CONSTRAINT fk_duplicate_video_video;
ALTER TABLE duplicate_video
    ADD CONSTRAINT fk_duplicate_video_video FOREIGN KEY (video_id) REFERENCES video_metadata (id);
