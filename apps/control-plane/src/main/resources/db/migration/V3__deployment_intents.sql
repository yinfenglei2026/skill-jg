CREATE TABLE deployment_intents (
    id VARCHAR(36) PRIMARY KEY,
    release_id VARCHAR(256) NOT NULL UNIQUE REFERENCES releases(id),
    department VARCHAR(128) NOT NULL,
    desired_digest VARCHAR(71) NOT NULL,
    observed_digest VARCHAR(71),
    status VARCHAR(32) NOT NULL,
    requested_actor VARCHAR(256) NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE,
    entity_version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX deployment_intents_department_idx ON deployment_intents (department);
CREATE INDEX deployment_intents_status_idx ON deployment_intents (status);
