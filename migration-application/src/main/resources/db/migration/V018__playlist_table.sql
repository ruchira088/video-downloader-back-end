CREATE TABLE playlist (
    id VARCHAR(127) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(2047) NULL,
    album_art_id VARCHAR(127) NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_playlist_album_art FOREIGN KEY (album_art_id) REFERENCES file_resource(id)
);

CREATE TABLE playlist_video (
    playlist_id VARCHAR(127) NOT NULL,
    video_id VARCHAR(127) NOT NULL,

    CONSTRAINT fk_playlist_video_playlist_id FOREIGN KEY (playlist_id) REFERENCES playlist(id),
    CONSTRAINT fk_playlist_video_video_id FOREIGN KEY (video_id) REFERENCES video(video_metadata_id)
);