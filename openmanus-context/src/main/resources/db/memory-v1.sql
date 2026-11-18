CREATE TABLE IF NOT EXISTS la4j_mem_project (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(200) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS la4j_mem_task (
 id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NULL, title VARCHAR(200) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'OPEN', progress MEDIUMTEXT NOT NULL, last_result MEDIUMTEXT NOT NULL,
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY (project_id) REFERENCES la4j_mem_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS la4j_mem_conversation (
 id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NULL, task_id VARCHAR(36) NULL,
 title VARCHAR(200) NOT NULL, summary MEDIUMTEXT NOT NULL, summary_through BIGINT NOT NULL DEFAULT 0,
 lease_owner VARCHAR(36) NULL, lease_until TIMESTAMP(3) NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY (project_id) REFERENCES la4j_mem_project(id),
 FOREIGN KEY (task_id) REFERENCES la4j_mem_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS la4j_mem_message (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, conversation_id VARCHAR(36) NOT NULL,
 role VARCHAR(20) NOT NULL, content MEDIUMTEXT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX ix_mem_message_conversation(conversation_id,id),
 FOREIGN KEY (conversation_id) REFERENCES la4j_mem_conversation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS la4j_mem_entry (
 id VARCHAR(36) PRIMARY KEY, scope VARCHAR(20) NOT NULL, scope_id VARCHAR(36) NOT NULL,
 content TEXT NOT NULL, source VARCHAR(500) NOT NULL, revision INT NOT NULL DEFAULT 1,
 deleted BOOLEAN NOT NULL DEFAULT FALSE, index_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 embedding_version VARCHAR(128) NULL, collection_name VARCHAR(200) NULL,
 lease_owner VARCHAR(36) NULL, lease_until TIMESTAMP(3) NULL, retry_at TIMESTAMP(3) NULL,
 index_error VARCHAR(300) NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX ix_mem_scope(scope,scope_id,deleted), INDEX ix_mem_jobs(index_status,retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS la4j_mem_conversation_scope (
 conversation_id VARCHAR(36) PRIMARY KEY, evidence_key CHAR(64) NOT NULL,
 FOREIGN KEY (conversation_id) REFERENCES la4j_mem_conversation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS la4j_mem_task_result_scope (
 task_id VARCHAR(36) PRIMARY KEY, evidence_key CHAR(64) NOT NULL,
 FOREIGN KEY (task_id) REFERENCES la4j_mem_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS la4j_mem_task_result_origin (
 task_id VARCHAR(36) PRIMARY KEY, conversation_id VARCHAR(36) NOT NULL,
 FOREIGN KEY (task_id) REFERENCES la4j_mem_task(id) ON DELETE CASCADE,
 FOREIGN KEY (conversation_id) REFERENCES la4j_mem_conversation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS la4j_mem_rewrite_workflow (
 id VARCHAR(36) PRIMARY KEY, conversation_id VARCHAR(36) NOT NULL,
 preview VARCHAR(80) NOT NULL, snapshot LONGTEXT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX ix_mem_rewrite_conversation(conversation_id,created_at,id),
 FOREIGN KEY (conversation_id) REFERENCES la4j_mem_conversation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
