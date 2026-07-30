-- Tách insight theo garage.
--
-- VẤN ĐỀ: business_insight không có garage_id, khoá duy nhất là (rule_code, from_date, to_date).
-- Nhưng AIDeepAnalysisService LẠI tính metric theo từng garage (resolveGarageScope +
-- MetricSnapshotBuilder.build(from, to, garageId)). Khi saveBusinessInsight tra
-- findByRuleCodeAndFromDateAndToDate — không có garage trong khoá — thì:
--   deep-analysis garage 5, kỳ P  -> tạo row (AI_TOTAL_REVENUE, P) với số của garage 5
--   deep-analysis garage 7, kỳ P  -> TÌM THẤY row của garage 5 và GHI ĐÈ bằng số garage 7
-- insight_analysis_run.garage_id ghi đúng scope, nhưng bảng insight thì không -> dữ liệu
-- hiển thị sai và không có cách nào phân biệt insight thuộc garage nào.
--
-- Đồng thời rule-based (InsightService) tính TOÀN HỆ THỐNG rồi ghi vào cùng bảng
-- -> quy ước: garage_id IS NULL = insight phạm vi toàn hệ thống.

ALTER TABLE business_insight
    ADD COLUMN IF NOT EXISTS garage_id INT REFERENCES garage(garage_id);

COMMENT ON COLUMN business_insight.garage_id IS
    'Phạm vi của insight. NULL = toàn hệ thống (rule engine); có giá trị = riêng garage đó (AI deep-analysis).';

-- Dữ liệu cũ: sinh trước khi có khái niệm scope -> coi là toàn hệ thống (NULL), không cần backfill.

-- Khoá duy nhất cũ không còn đủ: cùng rule_code + kỳ nhưng khác garage là 2 insight KHÁC nhau.
ALTER TABLE business_insight
    DROP CONSTRAINT IF EXISTS uq_business_insight_rule_period;

-- LƯU Ý QUAN TRỌNG: không dùng UNIQUE (rule_code, garage_id, from_date, to_date) được, vì
-- Postgres coi các NULL là KHÁC nhau -> sẽ không chặn được trùng ở nhánh toàn hệ thống
-- (garage_id IS NULL), đúng nhánh mà InsightService.upsertInsight dựa vào constraint này.
-- Dùng expression index với COALESCE để phủ cả hai nhánh bằng một index.
DROP INDEX IF EXISTS uq_business_insight_rule_scope_period;
CREATE UNIQUE INDEX uq_business_insight_rule_scope_period
    ON business_insight (rule_code, COALESCE(garage_id, -1), from_date, to_date);

-- Tra cứu theo phạm vi + kỳ (đường đọc của GET /api/owner/insights).
CREATE INDEX IF NOT EXISTS idx_business_insight_scope_period
    ON business_insight (COALESCE(garage_id, -1), from_date, to_date);
