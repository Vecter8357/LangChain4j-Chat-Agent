CREATE TABLE IF NOT EXISTS la4j_kb_base (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(200) NOT NULL, description TEXT,
 status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE', created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS la4j_kb_document (
 id VARCHAR(36) PRIMARY KEY, base_id VARCHAR(36) NOT NULL, name VARCHAR(255) NOT NULL,
 status VARCHAR(24) NOT NULL, document_version INT NOT NULL DEFAULT 1,
 active_index_id VARCHAR(36), pending_index_id VARCHAR(36), error TEXT,
 lease_owner VARCHAR(36), lease_until DATETIME(3), attempts INT NOT NULL DEFAULT 0,
 next_attempt DATETIME(3), updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY idx_base (base_id, status), KEY idx_jobs (status, lease_until, next_attempt),
 CONSTRAINT fk_kb_document_base FOREIGN KEY (base_id) REFERENCES la4j_kb_base(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS la4j_kb_document_version (
 document_id VARCHAR(36) NOT NULL, version INT NOT NULL, name VARCHAR(255) NOT NULL,
 bucket VARCHAR(100) NOT NULL, object_key VARCHAR(500) NOT NULL, content_type VARCHAR(150) NOT NULL,
 size BIGINT NOT NULL, sha256 CHAR(64) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY (document_id, version),
 CONSTRAINT fk_kb_version_doc FOREIGN KEY (document_id) REFERENCES la4j_kb_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS la4j_kb_index_version (
 id VARCHAR(36) PRIMARY KEY, document_id VARCHAR(36) NOT NULL, base_id VARCHAR(36) NOT NULL,
 document_version INT NOT NULL, status VARCHAR(24) NOT NULL, chunk_version VARCHAR(100) NOT NULL,
 chunk_size INT NOT NULL, chunk_overlap INT NOT NULL,
 embedding_model VARCHAR(255) NOT NULL, embedding_version CHAR(64) NOT NULL,
 dimension INT NOT NULL, collection_name VARCHAR(150) NOT NULL, error TEXT,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), KEY idx_doc (document_id),
 CONSTRAINT fk_kb_index_doc FOREIGN KEY (document_id) REFERENCES la4j_kb_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS la4j_kb_chunk (
 id VARCHAR(36) PRIMARY KEY, index_id VARCHAR(36) NOT NULL, document_id VARCHAR(36) NOT NULL,
 base_id VARCHAR(36) NOT NULL, document_version INT NOT NULL, ordinal INT NOT NULL,
 content MEDIUMTEXT NOT NULL, KEY idx_active_index (index_id), KEY idx_chunk_doc (document_id),
 FULLTEXT KEY ft_kb_content (content) WITH PARSER ngram,
 CONSTRAINT fk_kb_chunk_index FOREIGN KEY (index_id) REFERENCES la4j_kb_index_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
