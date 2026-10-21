-- Holds the playlist memberships of a video that has been re-queued for download. The video row is deleted while it
-- downloads again, which removes it from its playlists, and the memberships are restored once the video is saved again.
-- There is no foreign key to the video table, since the video does not exist while it is re-queued.
CREATE TABLE requeued_playlist_video (
    playlist_id VARCHAR(127) NOT NULL,
    video_id VARCHAR(127) NOT NULL,
    position_index INT NOT NULL,

    PRIMARY KEY (playlist_id, video_id),
    CONSTRAINT fk_requeued_playlist_video_playlist_id
        FOREIGN KEY (playlist_id) REFERENCES playlist(id) ON DELETE CASCADE,
    CONSTRAINT fk_requeued_playlist_video_video_id
        FOREIGN KEY (video_id) REFERENCES video_metadata(id) ON DELETE CASCADE
);
