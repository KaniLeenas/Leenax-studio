-- Increment E2: course documents, read page by page.
--
-- A document belongs to one project and keeps every version that was uploaded.
-- Each version is read by the document service (embedded text, or OCR in English
-- and Tamil) into pages, and the pages are cut into passages for search. Search
-- covers the newest version of each document that was read successfully.
--
-- Access follows the project (row-level security, as in V9): every member can read;
-- owners and editors (and administrators) can upload, rename and delete.

CREATE TABLE documents (
    id         UUID         PRIMARY KEY,
    project_id UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    title      VARCHAR(200) NOT NULL,
    created_by UUID         REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_documents_project ON documents (project_id, updated_at DESC);

CREATE TABLE document_versions (
    id                UUID         PRIMARY KEY,
    document_id       UUID         NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    version_no        INT          NOT NULL CHECK (version_no > 0),
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(120) NOT NULL,
    size_bytes        BIGINT       NOT NULL CHECK (size_bytes > 0),
    sha256            CHAR(64)     NOT NULL,
    storage_key       VARCHAR(300) NOT NULL,
    status            VARCHAR(12)  NOT NULL DEFAULT 'QUEUED'
                      CHECK (status IN ('QUEUED', 'PROCESSING', 'READY', 'FAILED')),
    force_ocr         BOOLEAN      NOT NULL DEFAULT FALSE,
    error             VARCHAR(500),
    page_count        INT,
    pages_read        INT,
    ocr_pages         INT,
    ocr_confidence    NUMERIC(5, 1),
    language          VARCHAR(10)  CHECK (language IN ('en', 'ta', 'mixed', 'unknown')),
    module_codes      TEXT[]       NOT NULL DEFAULT '{}',
    detected_title    VARCHAR(200),
    attempts          INT          NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_until      TIMESTAMPTZ,
    processed_at      TIMESTAMPTZ,
    created_by        UUID         REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_document_version UNIQUE (document_id, version_no)
);
CREATE INDEX idx_document_versions_queue ON document_versions (next_attempt_at)
    WHERE status IN ('QUEUED', 'PROCESSING');
CREATE INDEX idx_document_versions_sha ON document_versions (sha256);

CREATE TABLE document_pages (
    version_id     UUID         NOT NULL REFERENCES document_versions (id) ON DELETE CASCADE,
    page_no        INT          NOT NULL CHECK (page_no > 0),
    method         VARCHAR(5)   NOT NULL CHECK (method IN ('TEXT', 'OCR')),
    ocr_confidence NUMERIC(5, 1),
    text           TEXT         NOT NULL,
    PRIMARY KEY (version_id, page_no)
);

-- Passages never cross a page, so every search hit has one page number.
-- terms: the passage's distinct words, lower-cased, in NFC, letters + combining
-- marks + digits (so Tamil vowel signs stay inside their word). Written by the app.
CREATE TABLE document_chunks (
    id         BIGSERIAL PRIMARY KEY,
    version_id UUID      NOT NULL REFERENCES document_versions (id) ON DELETE CASCADE,
    page_no    INT       NOT NULL,
    ordinal    INT       NOT NULL,
    text       TEXT      NOT NULL,
    terms      TEXT[]    NOT NULL,
    CONSTRAINT uq_document_chunk UNIQUE (version_id, ordinal)
);
CREATE INDEX idx_document_chunks_terms ON document_chunks USING gin (terms);

-- ---- row-level security ------------------------------------------------------------------------

ALTER TABLE documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON documents FOR SELECT USING (leenax_can_read(project_id, created_by));
CREATE POLICY add ON documents FOR INSERT WITH CHECK (leenax_can_write(project_id, created_by));
CREATE POLICY change ON documents FOR UPDATE USING (leenax_can_write(project_id, created_by))
    WITH CHECK (leenax_can_write(project_id, created_by));
CREATE POLICY remove ON documents FOR DELETE USING (leenax_can_write(project_id, created_by));

ALTER TABLE document_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_versions FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON document_versions FOR SELECT
    USING (EXISTS (SELECT 1 FROM documents d WHERE d.id = document_id));
CREATE POLICY write ON document_versions FOR ALL
    USING (EXISTS (SELECT 1 FROM documents d WHERE d.id = document_id AND leenax_can_write(d.project_id, d.created_by)))
    WITH CHECK (EXISTS (SELECT 1 FROM documents d WHERE d.id = document_id AND leenax_can_write(d.project_id, d.created_by)));

-- Pages and passages are written only by the document worker (or the system).
ALTER TABLE document_pages ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_pages FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON document_pages FOR SELECT
    USING (EXISTS (SELECT 1 FROM document_versions v WHERE v.id = version_id));
CREATE POLICY write ON document_pages FOR ALL
    USING (coalesce(leenax_mode() IN ('system', 'worker'), false))
    WITH CHECK (coalesce(leenax_mode() IN ('system', 'worker'), false));

ALTER TABLE document_chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_chunks FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON document_chunks FOR SELECT
    USING (EXISTS (SELECT 1 FROM document_versions v WHERE v.id = version_id));
CREATE POLICY write ON document_chunks FOR ALL
    USING (coalesce(leenax_mode() IN ('system', 'worker'), false))
    WITH CHECK (coalesce(leenax_mode() IN ('system', 'worker'), false));

GRANT SELECT, INSERT, UPDATE, DELETE ON documents, document_versions, document_pages, document_chunks TO leenax_app;
GRANT USAGE, SELECT ON SEQUENCE document_chunks_id_seq TO leenax_app;
