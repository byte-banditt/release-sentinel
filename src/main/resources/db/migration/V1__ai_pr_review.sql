CREATE TABLE pr_reviews (
    review_id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    repository VARCHAR(255) NOT NULL,
    pull_request_number INTEGER NOT NULL CHECK (pull_request_number > 0),
    base_sha VARCHAR(64) NOT NULL,
    head_sha VARCHAR(64) NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    review_status VARCHAR(40) NOT NULL CHECK (review_status IN ('REVIEW_CLEAN', 'REVIEW_FINDINGS', 'REVIEW_INSUFFICIENT_CONTEXT', 'REVIEW_FAILED')),
    summary TEXT NOT NULL,
    overall_risk VARCHAR(16) NOT NULL CHECK (overall_risk IN ('PASS', 'WARN')),
    findings_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX pr_reviews_tenant_created_idx ON pr_reviews (tenant_id, created_at DESC);
CREATE INDEX pr_reviews_head_idx ON pr_reviews (tenant_id, repository, pull_request_number, head_sha, created_at DESC);

CREATE TABLE test_suggestions (
    suggestion_id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    review_id UUID NOT NULL REFERENCES pr_reviews(review_id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL CHECK (status IN ('GENERATED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'EXPORTED', 'VALIDATION_PENDING', 'VALIDATED', 'VALIDATION_FAILED')),
    title TEXT NOT NULL,
    target TEXT NOT NULL,
    scenario TEXT NOT NULL,
    rationale TEXT NOT NULL,
    expected_behavior TEXT NOT NULL,
    source_path TEXT NOT NULL,
    source_line INTEGER NOT NULL CHECK (source_line > 0),
    framework VARCHAR(32) NOT NULL,
    artifact_path TEXT NOT NULL,
    artifact_text TEXT NOT NULL,
    artifact_sha256 CHAR(64) NOT NULL,
    assumptions_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    decision_at TIMESTAMPTZ,
    decision_by VARCHAR(128) NOT NULL DEFAULT '',
    decision_reason TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX test_suggestions_review_idx ON test_suggestions (tenant_id, review_id, created_at);

CREATE TABLE validation_bindings (
    review_id UUID PRIMARY KEY REFERENCES pr_reviews(review_id),
    tenant_id VARCHAR(128) NOT NULL,
    repository VARCHAR(255) NOT NULL,
    pull_request_number INTEGER NOT NULL,
    original_head_sha VARCHAR(64) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    suggestion_ids JSONB NOT NULL,
    artifact_hash CHAR(64) NOT NULL,
    bound_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE ci_results (
    tenant_id VARCHAR(128) NOT NULL,
    workflow_run_id BIGINT NOT NULL,
    review_id UUID NOT NULL REFERENCES validation_bindings(review_id),
    repository VARCHAR(255) NOT NULL,
    pull_request_number INTEGER NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    workflow_url TEXT NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('COMPLETED')),
    conclusion VARCHAR(32) NOT NULL,
    tests_passed INTEGER CHECK (tests_passed >= 0),
    tests_failed INTEGER CHECK (tests_failed >= 0),
    tests_skipped INTEGER CHECK (tests_skipped >= 0),
    test_suite VARCHAR(255) NOT NULL,
    workflow_name VARCHAR(255) NOT NULL,
    workflow_path TEXT NOT NULL,
    test_step_conclusion VARCHAR(32) NOT NULL,
    reported_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, workflow_run_id)
);

CREATE TABLE github_deliveries (
    tenant_id VARCHAR(128) NOT NULL,
    delivery_id VARCHAR(128) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'DELIVERED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    last_error VARCHAR(120),
    received_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, delivery_id)
);

CREATE TABLE ci_event_outbox (
    tenant_id VARCHAR(128) NOT NULL,
    delivery_id VARCHAR(128) NOT NULL,
    event_json JSONB NOT NULL,
    PRIMARY KEY (tenant_id, delivery_id),
    FOREIGN KEY (tenant_id, delivery_id) REFERENCES github_deliveries(tenant_id, delivery_id) ON DELETE CASCADE
);

CREATE INDEX github_deliveries_pending_idx ON github_deliveries (available_at, lease_until) WHERE state='PENDING';

CREATE TABLE pr_review_jobs (
    tenant_id VARCHAR(128) NOT NULL,
    delivery_id VARCHAR(128) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    repository VARCHAR(255) NOT NULL,
    pull_request_number INTEGER NOT NULL CHECK (pull_request_number > 0),
    head_sha VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'COMPLETE', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    last_error VARCHAR(120),
    PRIMARY KEY (tenant_id, delivery_id)
);

CREATE INDEX pr_review_jobs_pending_idx ON pr_review_jobs (available_at, lease_until) WHERE state='PENDING';
