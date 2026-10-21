-- Rebuilds playlist_video so that a playlist keeps its videos in order, holds each video at most once, and is cleaned
-- up when either its playlist or one of its videos is deleted.
--
-- Existing rows have no recorded order: duplicates are collapsed and the remaining videos are numbered by video id.
CREATE TABLE playlist_video_migration AS
    SELECT
        playlist_id,
        video_id,
        ROW_NUMBER() OVER (PARTITION BY playlist_id ORDER BY video_id) - 1 AS position_index
    FROM (SELECT DISTINCT playlist_id, video_id FROM playlist_video) AS distinct_playlist_video;

DROP TABLE playlist_video;

CREATE TABLE playlist_video (
    playlist_id VARCHAR(127) NOT NULL,
    video_id VARCHAR(127) NOT NULL,
    position_index INT NOT NULL,

    PRIMARY KEY (playlist_id, video_id),
    CONSTRAINT uq_playlist_video_position UNIQUE (playlist_id, position_index),
    CONSTRAINT fk_playlist_video_playlist_id
        FOREIGN KEY (playlist_id) REFERENCES playlist(id) ON DELETE CASCADE,
    CONSTRAINT fk_playlist_video_video_id
        FOREIGN KEY (video_id) REFERENCES video(video_metadata_id) ON DELETE CASCADE
);

INSERT INTO playlist_video (playlist_id, video_id, position_index)
    SELECT playlist_id, video_id, position_index FROM playlist_video_migration;

DROP TABLE playlist_video_migration;
