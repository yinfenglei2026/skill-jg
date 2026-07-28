ALTER TABLE releases ADD COLUMN canonical_manifest TEXT;
ALTER TABLE releases ADD COLUMN manifest_digest VARCHAR(71);

UPDATE releases
SET canonical_manifest = '{}', manifest_digest = digest;

ALTER TABLE releases ALTER COLUMN canonical_manifest SET NOT NULL;
ALTER TABLE releases ALTER COLUMN manifest_digest SET NOT NULL;

CREATE TABLE release_dependencies (
    release_id VARCHAR(256) NOT NULL REFERENCES releases(id),
    dependency_order INTEGER NOT NULL,
    dependency_capability_id VARCHAR(128) NOT NULL,
    dependency_type VARCHAR(16) NOT NULL,
    dependency_version VARCHAR(64) NOT NULL,
    dependency_digest VARCHAR(71) NOT NULL,
    PRIMARY KEY (release_id, dependency_order)
);

CREATE TABLE release_evidence (
    release_id VARCHAR(256) NOT NULL REFERENCES releases(id),
    evidence_order INTEGER NOT NULL,
    evidence_type VARCHAR(64) NOT NULL,
    evidence_subject VARCHAR(512) NOT NULL,
    evidence_digest VARCHAR(71) NOT NULL,
    PRIMARY KEY (release_id, evidence_order)
);
