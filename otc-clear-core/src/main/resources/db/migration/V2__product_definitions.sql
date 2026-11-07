-- Product definitions: platform -> base product -> product, plus field meta information.

CREATE TABLE platform (
    code        VARCHAR(16)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(500),
    sort_order  INT          NOT NULL DEFAULT 0,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_platform PRIMARY KEY (code)
);

CREATE TABLE base_product (
    id            VARCHAR(36)  NOT NULL,
    tenant_id     VARCHAR(64)  NOT NULL,
    platform_code VARCHAR(16)  NOT NULL,
    code          VARCHAR(32)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(500),
    status        VARCHAR(16)  NOT NULL,
    sort_order    INT          NOT NULL DEFAULT 0,
    schema_hash   VARCHAR(64),
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL,
    CONSTRAINT pk_base_product PRIMARY KEY (id),
    CONSTRAINT uq_base_product UNIQUE (tenant_id, platform_code, code)
);

CREATE INDEX ix_base_product_platform ON base_product (tenant_id, platform_code, sort_order);

CREATE TABLE base_product_field (
    id              VARCHAR(36)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    base_product_id VARCHAR(36)  NOT NULL,
    field_key       VARCHAR(64)  NOT NULL,
    header          VARCHAR(200) NOT NULL,
    display_name    VARCHAR(200),
    aliases         VARCHAR(500),
    value_type_code VARCHAR(64)  NOT NULL,
    role_code       VARCHAR(64)  NOT NULL,
    tags            VARCHAR(500),
    required_flag   BOOLEAN      NOT NULL DEFAULT FALSE,
    business_key    BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order      INT          NOT NULL DEFAULT 0,
    remark          VARCHAR(500),
    format_hint     VARCHAR(64),
    clone_pattern   VARCHAR(200),
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT pk_base_product_field PRIMARY KEY (id),
    CONSTRAINT uq_base_product_field UNIQUE (base_product_id, field_key)
);

CREATE INDEX ix_base_field_product ON base_product_field (tenant_id, base_product_id, sort_order);

CREATE TABLE product (
    id                VARCHAR(36)  NOT NULL,
    tenant_id         VARCHAR(64)  NOT NULL,
    platform_code     VARCHAR(16)  NOT NULL,
    base_product_id   VARCHAR(36)  NOT NULL,
    base_product_code VARCHAR(32)  NOT NULL,
    code              VARCHAR(64)  NOT NULL,
    name              VARCHAR(128) NOT NULL,
    description       VARCHAR(500),
    status            VARCHAR(16)  NOT NULL,
    schema_hash       VARCHAR(64),
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    CONSTRAINT pk_product PRIMARY KEY (id),
    CONSTRAINT uq_product_code UNIQUE (tenant_id, code)
);

CREATE INDEX ix_product_base ON product (tenant_id, base_product_id, status);
CREATE INDEX ix_product_tenant_status ON product (tenant_id, status);

CREATE TABLE product_field (
    id              VARCHAR(36)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    product_id      VARCHAR(36)  NOT NULL,
    base_field_id   VARCHAR(36)  NOT NULL,
    field_key       VARCHAR(64)  NOT NULL,
    header          VARCHAR(200) NOT NULL,
    display_name    VARCHAR(200),
    aliases         VARCHAR(500),
    value_type_code VARCHAR(64)  NOT NULL,
    role_code       VARCHAR(64)  NOT NULL,
    tags            VARCHAR(500),
    required_flag   BOOLEAN      NOT NULL DEFAULT FALSE,
    business_key    BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order      INT          NOT NULL DEFAULT 0,
    remark          VARCHAR(500),
    format_hint     VARCHAR(64),
    clone_pattern   VARCHAR(200),
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT pk_product_field PRIMARY KEY (id),
    CONSTRAINT uq_product_field UNIQUE (product_id, field_key)
);

CREATE INDEX ix_product_field_product ON product_field (tenant_id, product_id, sort_order);

CREATE TABLE meta_set (
    tenant_id   VARCHAR(64)  NOT NULL,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(500),
    built_in    BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    CONSTRAINT pk_meta_set PRIMARY KEY (tenant_id, code)
);

CREATE TABLE meta_item (
    tenant_id    VARCHAR(64)  NOT NULL,
    set_code     VARCHAR(64)  NOT NULL,
    code         VARCHAR(64)  NOT NULL,
    label        VARCHAR(200) NOT NULL,
    ref_set_code VARCHAR(64),
    sort_order   INT          NOT NULL DEFAULT 0,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    remark       VARCHAR(500),
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    CONSTRAINT pk_meta_item PRIMARY KEY (tenant_id, set_code, code)
);

CREATE INDEX ix_meta_item_set ON meta_item (tenant_id, set_code, sort_order);
