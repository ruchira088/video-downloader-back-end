CREATE TABLE worker(
    index BIGSERIAL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    id VARCHAR(64) NOT NULL,
    reserved_at TIMESTAMP NULL,
    task_assigned_at TIMESTAMP NULL,

    PRIMARY KEY (id)
);