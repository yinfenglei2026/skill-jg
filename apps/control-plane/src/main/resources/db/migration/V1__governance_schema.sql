CREATE TABLE capabilities (
    id VARCHAR(128) PRIMARY KEY,
    department VARCHAR(128) NOT NULL,
    type VARCHAR(16) NOT NULL
);

CREATE TABLE releases (
    id VARCHAR(256) PRIMARY KEY,
    capability_id VARCHAR(128) NOT NULL REFERENCES capabilities(id),
    version VARCHAR(64) NOT NULL,
    artifact_reference VARCHAR(512) NOT NULL,
    digest VARCHAR(71) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state VARCHAR(32) NOT NULL,
    approval_digest VARCHAR(71),
    approval_actor VARCHAR(256),
    approval_decided_at TIMESTAMP WITH TIME ZONE,
    entity_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT releases_capability_version_unique UNIQUE (capability_id, version),
    CONSTRAINT releases_digest_format CHECK (digest LIKE 'sha256:%')
);

CREATE TABLE release_transitions (
    release_id VARCHAR(256) NOT NULL REFERENCES releases(id),
    transition_order INTEGER NOT NULL,
    from_state VARCHAR(32) NOT NULL,
    to_state VARCHAR(32) NOT NULL,
    actor VARCHAR(256) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (release_id, transition_order)
);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    actor VARCHAR(256) NOT NULL,
    department VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    subject VARCHAR(256) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    digest VARCHAR(71),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX audit_events_department_occurred_at_idx ON audit_events (department, occurred_at);
