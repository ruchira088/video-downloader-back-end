CREATE TABLE api_user(
    id VARCHAR(127) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(63),

    PRIMARY KEY (id)
);

CREATE TABLE credentials(
    user_id VARCHAR(127) NOT NULL,
    last_updated_at TIMESTAMP NOT NULL,
    hashed_password VARCHAR(127) NOT NULL,

    PRIMARY KEY (user_id),
    CONSTRAINT fk_credentials_user_id FOREIGN KEY (user_id) REFERENCES api_user(id)
);