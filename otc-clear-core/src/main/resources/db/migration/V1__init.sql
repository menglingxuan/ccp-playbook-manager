CREATE TABLE import_task (
    id            VARCHAR(36)  NOT NULL,
    tenant_id     VARCHAR(64)  NOT NULL,
    kind          VARCHAR(24)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    source_name   VARCHAR(255) NOT NULL,
    source_sha256 VARCHAR(64),
    sheet_count   INT          NOT NULL DEFAULT 0,
    batch_count   INT          NOT NULL DEFAULT 0,
    total_rows    INT          NOT NULL DEFAULT 0,
    invalid_rows  INT          NOT NULL DEFAULT 0,
    params_json   CLOB,
    issues_json   CLOB,
    message       VARCHAR(2000),
    created_by    VARCHAR(64),
    created_at    TIMESTAMP    NOT NULL,
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP,
    CONSTRAINT pk_import_task PRIMARY KEY (id)
);

CREATE INDEX ix_task_tenant_created ON import_task (tenant_id, created_at);
CREATE INDEX ix_task_tenant_status  ON import_task (tenant_id, status);

CREATE TABLE import_batch (
    id                VARCHAR(36)  NOT NULL,
    tenant_id         VARCHAR(64)  NOT NULL,
    task_id           VARCHAR(36),
    kind              VARCHAR(24)  NOT NULL,
    parent_batch_id   VARCHAR(36),
    product_code      VARCHAR(32)  NOT NULL,
    source_filename   VARCHAR(255) NOT NULL,
    source_sha256     VARCHAR(64),
    source_type       VARCHAR(8)   NOT NULL,
    sheet_name        VARCHAR(128),
    header_row_index  INT          NOT NULL DEFAULT 0,
    trade_start_col   INT          NOT NULL DEFAULT 0,
    trade_end_col     INT          NOT NULL DEFAULT 0,
    layout_json       CLOB,
    column_mapping    CLOB,
    schema_hash       VARCHAR(64),
    status            VARCHAR(16)  NOT NULL,
    total_rows        INT          NOT NULL DEFAULT 0,
    invalid_rows      INT          NOT NULL DEFAULT 0,
    note              VARCHAR(500),
    created_by        VARCHAR(64),
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    deleted_at        TIMESTAMP,
    CONSTRAINT pk_import_batch PRIMARY KEY (id)
);

CREATE INDEX ix_batch_tenant_created ON import_batch (tenant_id, created_at);
CREATE INDEX ix_batch_tenant_task    ON import_batch (tenant_id, task_id);
CREATE INDEX ix_batch_tenant_product ON import_batch (tenant_id, product_code, status);

CREATE TABLE test_case (
    id                  VARCHAR(36)  NOT NULL,
    tenant_id           VARCHAR(64)  NOT NULL,
    batch_id            VARCHAR(36)  NOT NULL,
    product_code        VARCHAR(32)  NOT NULL,
    origin              VARCHAR(24)  NOT NULL,
    row_no              INT          NOT NULL,
    source_row          INT,
    test_case_id        VARCHAR(64),
    test_case_id_source VARCHAR(16),
    original_record_id  VARCHAR(64)  NOT NULL,
    business_record_id  VARCHAR(64)  NOT NULL,
    key_value           VARCHAR(200),
    data_json           CLOB         NOT NULL,
    managed_json        CLOB,
    formula_json        CLOB,
    extras_json         CLOB,
    formula_count       INT          NOT NULL DEFAULT 0,
    search_text         VARCHAR(4000),
    status              VARCHAR(16)  NOT NULL,
    version             INT          NOT NULL DEFAULT 0,
    cloned_from         VARCHAR(36),
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    CONSTRAINT pk_test_case PRIMARY KEY (id)
);

CREATE INDEX ix_case_tenant_batch   ON test_case (tenant_id, batch_id, row_no);
CREATE INDEX ix_case_tenant_product ON test_case (tenant_id, product_code, status);
CREATE INDEX ix_case_tenant_orig    ON test_case (tenant_id, original_record_id);
CREATE INDEX ix_case_tenant_biz     ON test_case (tenant_id, business_record_id);
CREATE INDEX ix_case_tenant_key     ON test_case (tenant_id, product_code, key_value);

CREATE TABLE external_job (
    id               VARCHAR(36)  NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL,
    product_code     VARCHAR(32)  NOT NULL,
    case_count       INT          NOT NULL,
    folder_path      VARCHAR(500) NOT NULL,
    input_csv_path   VARCHAR(500) NOT NULL,
    request_id       VARCHAR(64)  NOT NULL,
    external_job_id  VARCHAR(128),
    status           VARCHAR(16)  NOT NULL,
    dispatch_payload CLOB,
    last_response    CLOB,
    message          VARCHAR(2000),
    created_by       VARCHAR(64),
    created_at       TIMESTAMP    NOT NULL,
    dispatched_at    TIMESTAMP,
    finished_at      TIMESTAMP,
    last_polled_at   TIMESTAMP,
    CONSTRAINT pk_external_job PRIMARY KEY (id)
);

CREATE INDEX ix_extjob_tenant_created ON external_job (tenant_id, created_at);

CREATE TABLE external_job_case (
    tenant_id  VARCHAR(64) NOT NULL,
    job_id     VARCHAR(36) NOT NULL,
    case_id    VARCHAR(36) NOT NULL,
    row_no     INT         NOT NULL,
    CONSTRAINT pk_external_job_case PRIMARY KEY (job_id, case_id)
);

CREATE TABLE external_output (
    id                 VARCHAR(36)  NOT NULL,
    tenant_id          VARCHAR(64)  NOT NULL,
    job_id             VARCHAR(36)  NOT NULL,
    file_name          VARCHAR(255) NOT NULL,
    relative_path      VARCHAR(500) NOT NULL,
    size_bytes         BIGINT       NOT NULL,
    sha256             VARCHAR(64)  NOT NULL,
    imported_batch_id  VARCHAR(36),
    status             VARCHAR(16)  NOT NULL,
    message            VARCHAR(1000),
    discovered_at      TIMESTAMP    NOT NULL,
    imported_at        TIMESTAMP,
    CONSTRAINT pk_external_output PRIMARY KEY (id)
);

CREATE INDEX ix_output_tenant_job ON external_output (tenant_id, job_id);

CREATE TABLE export_job (
    id           VARCHAR(36)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    product_code VARCHAR(32)  NOT NULL,
    scope_type   VARCHAR(16)  NOT NULL,
    batch_id     VARCHAR(36),
    row_count    INT          NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    file_size    BIGINT       NOT NULL,
    options_json CLOB,
    status       VARCHAR(16)  NOT NULL,
    message      VARCHAR(1000),
    created_by   VARCHAR(64),
    created_at   TIMESTAMP    NOT NULL,
    CONSTRAINT pk_export_job PRIMARY KEY (id)
);

CREATE TABLE audit_log (
    id          VARCHAR(36) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id   VARCHAR(36),
    action      VARCHAR(32) NOT NULL,
    detail      CLOB,
    actor       VARCHAR(64),
    created_at  TIMESTAMP   NOT NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (id)
);

CREATE INDEX ix_audit_tenant_created ON audit_log (tenant_id, created_at);

CREATE TABLE app_setting (
    tenant_id     VARCHAR(64)  NOT NULL,
    setting_key   VARCHAR(64)  NOT NULL,
    setting_value VARCHAR(1000),
    updated_at    TIMESTAMP    NOT NULL,
    CONSTRAINT pk_app_setting PRIMARY KEY (tenant_id, setting_key)
);
