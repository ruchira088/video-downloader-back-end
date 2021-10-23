CREATE TABLE credentials_reset_token(
    user_id VARCHAR(127) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    token VARCHAR(255) NOT NULL,

    PRIMARY KEY (user_id, token),
    CONSTRAINT fk_credentials_reset_token_user_id FOREIGN KEY (user_id) REFERENCES api_user(id)
);