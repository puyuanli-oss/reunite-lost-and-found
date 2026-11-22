-- Run once against an empty MySQL 8+ database created with utf8mb4.
-- CREATE DATABASE reunite CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
-- USE reunite;
-- REUNITE relational schema, shared by H2 MySQL mode and MySQL 8+.
-- Database.java substitutes LONGBLOB with BLOB (H2) or LONGBLOB (MySQL).
CREATE TABLE IF NOT EXISTS users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(40) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  email VARCHAR(254) NOT NULL UNIQUE,
  password_hash VARCHAR(512) NOT NULL,
  role VARCHAR(10) NOT NULL DEFAULT 'USER',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT chk_user_role CHECK (role IN ('USER','ADMIN'))
);
CREATE TABLE IF NOT EXISTS reports (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  type VARCHAR(5) NOT NULL,
  visibility VARCHAR(7) NOT NULL,
  title VARCHAR(120) NOT NULL,
  description VARCHAR(4000) NOT NULL,
  category VARCHAR(30) NOT NULL,
  subcategory VARCHAR(40) NOT NULL DEFAULT '',
  color VARCHAR(60) NOT NULL DEFAULT '',
  brand VARCHAR(80) NOT NULL DEFAULT '',
  event_date DATE NOT NULL,
  location VARCHAR(200) NOT NULL,
  status VARCHAR(10) NOT NULL DEFAULT 'OPEN',
  version INTEGER NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_report_owner FOREIGN KEY(owner_id) REFERENCES users(id),
  CONSTRAINT chk_report_type CHECK (type IN ('LOST','FOUND')),
  CONSTRAINT chk_report_visibility CHECK (visibility IN ('PUBLIC','PRIVATE')),
  CONSTRAINT chk_report_status CHECK (status IN ('OPEN','RESOLVED','REMOVED','CLOSED')),
  CONSTRAINT chk_report_version CHECK (version > 0)
);
CREATE TABLE IF NOT EXISTS report_secrets (
  report_id BIGINT PRIMARY KEY,
  private_details VARCHAR(4000) NOT NULL DEFAULT '',
  verification_question VARCHAR(300) NOT NULL DEFAULT '',
  answer_hash VARCHAR(512) NOT NULL DEFAULT '',
  CONSTRAINT fk_secrets_report FOREIGN KEY(report_id) REFERENCES reports(id)
);
CREATE TABLE IF NOT EXISTS photos (
  report_id BIGINT PRIMARY KEY,
  mime_type VARCHAR(20) NOT NULL,
  content LONGBLOB NOT NULL,
  size_bytes INTEGER NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_photo_report FOREIGN KEY(report_id) REFERENCES reports(id),
  CONSTRAINT chk_photo_type CHECK (mime_type IN ('image/jpeg','image/png','image/webp')),
  CONSTRAINT chk_photo_size CHECK (size_bytes > 0 AND size_bytes <= 10485760)
);
CREATE TABLE IF NOT EXISTS matches (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  lost_report_id BIGINT NOT NULL,
  found_report_id BIGINT NOT NULL,
  score DECIMAL(5,2) NOT NULL,
  status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_match_lost FOREIGN KEY(lost_report_id) REFERENCES reports(id),
  CONSTRAINT fk_match_found FOREIGN KEY(found_report_id) REFERENCES reports(id),
  CONSTRAINT uq_match_pair UNIQUE(lost_report_id,found_report_id),
  CONSTRAINT chk_match_distinct CHECK (lost_report_id <> found_report_id),
  CONSTRAINT chk_match_score CHECK (score >= 0 AND score <= 100),
  CONSTRAINT chk_match_status CHECK (status IN ('ACTIVE','WITHDRAWN'))
);
CREATE TABLE IF NOT EXISTS notifications (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  message VARCHAR(500) NOT NULL,
  event_key VARCHAR(150) UNIQUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_notification_user FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE IF NOT EXISTS claims (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  found_report_id BIGINT NOT NULL,
  lost_report_id BIGINT,
  claimant_id BIGINT NOT NULL,
  answer_hash VARCHAR(512) NOT NULL,
  answer_matches BOOLEAN NOT NULL DEFAULT FALSE,
  explanation VARCHAR(2000) NOT NULL DEFAULT '',
  status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
  approved_report_id BIGINT UNIQUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_at TIMESTAMP NULL,
  decided_by BIGINT,
  reason VARCHAR(1000),
  CONSTRAINT fk_claim_found FOREIGN KEY(found_report_id) REFERENCES reports(id),
  CONSTRAINT fk_claim_lost FOREIGN KEY(lost_report_id) REFERENCES reports(id),
  CONSTRAINT fk_claim_user FOREIGN KEY(claimant_id) REFERENCES users(id),
  CONSTRAINT fk_claim_approved FOREIGN KEY(approved_report_id) REFERENCES reports(id),
  CONSTRAINT fk_claim_decider FOREIGN KEY(decided_by) REFERENCES users(id),
  CONSTRAINT chk_claim_status CHECK (status IN ('PENDING','APPROVED','REJECTED','CLOSED')),
  CONSTRAINT chk_claim_approval CHECK ((status = 'APPROVED' AND approved_report_id IS NOT NULL AND approved_report_id = found_report_id) OR (status <> 'APPROVED' AND approved_report_id IS NULL))
);
CREATE TABLE IF NOT EXISTS audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  actor_id BIGINT NOT NULL,
  action VARCHAR(80) NOT NULL,
  report_id BIGINT,
  reason VARCHAR(1000) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_audit_actor FOREIGN KEY(actor_id) REFERENCES users(id),
  CONSTRAINT fk_audit_report FOREIGN KEY(report_id) REFERENCES reports(id)
);
CREATE INDEX idx_reports_search ON reports(status,visibility,type,event_date);
CREATE INDEX idx_reports_owner ON reports(owner_id,status);
CREATE INDEX idx_reports_matching ON reports(type,status,category,event_date);
CREATE INDEX idx_matches_found ON matches(found_report_id,status);
CREATE INDEX idx_matches_lost ON matches(lost_report_id,status);
CREATE INDEX idx_notifications_user ON notifications(user_id,created_at);
CREATE INDEX idx_claims_found ON claims(found_report_id,status,created_at);
CREATE INDEX idx_claims_attempts ON claims(claimant_id,found_report_id,created_at);
