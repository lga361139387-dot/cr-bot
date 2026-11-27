-- 发现的证据与定位。location_status：1已定位 2未定位 3无证据。evidence_strength：1强 2弱。
ALTER TABLE review_finding ADD COLUMN existing_code TEXT;
ALTER TABLE review_finding ADD COLUMN suggestion_code TEXT;
ALTER TABLE review_finding ADD COLUMN location_status SMALLINT;
ALTER TABLE review_finding ADD COLUMN evidence_strength SMALLINT;
