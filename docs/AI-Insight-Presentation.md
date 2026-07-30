# AI Insight — Nội dung thuyết trình
### Hệ thống WashMate / AutoWash (SU26SWP01)

> Tài liệu soạn theo góc nhìn giảng viên: đi từ *bài toán* → *kiến trúc* → *dữ liệu vào/ra* → *cấu hình rule* → *cơ chế chống bịa số* → *demo*.

---

## PHẦN 0. Đặt vấn đề (1 slide, ~1 phút)

Chủ tiệm rửa xe có dashboard đầy số: doanh thu, số đơn, điểm loyalty. Nhưng số liệu **không tự nói cho họ biết phải làm gì**.

Chức năng **AI Insight** trả lời 3 câu hỏi:

| Câu hỏi | Thành phần trả lời |
|---|---|
| Chuyện gì đang xảy ra? | Rule Engine (deterministic) |
| Tại sao lại như vậy? | AI Enrichment (Gemini) |
| Tôi nên làm gì? | AI Recommendation + Campaign Suggestion |

**Nguyên tắc thiết kế cốt lõi — nhấn mạnh khi thuyết trình:**
> AI **không phát hiện** vấn đề. Backend phát hiện bằng rule. AI chỉ **diễn giải** và **đề xuất hành động** trên số liệu backend đã tính sẵn.
> → Đây là kiến trúc *rule-first, AI-second*, giúp hệ thống **không bao giờ bịa số**.

---

## PHẦN 1. Kiến trúc tổng thể (1 slide sơ đồ)

```
[DB: booking, invoice, loyalty_transaction, reward_redemption, service_package]
                          │
                          ▼  (1) AGGREGATE
        ReportAggregationService → InsightMetrics (current + previous period)
                          │
                          ▼  (2) DETECT — deterministic
        InsightRuleEngine + 6 InsightRule
        (ngưỡng lấy từ bảng insight_rule_config)
                          │
                          ▼  (3) PERSIST
                business_insight  (upsert theo rule_code + from_date + to_date)
                          │
                          ▼  (4) ENRICH — chỉ khi Owner bấm nút
        InsightMetricAggregator → Context Package (JSON)
                 → AIPromptBuilderService → GeminiClient (gemini-2.5-flash)
                 → AIResponseValidatorService (validate + chặn từ cấm)
                          │
                          ▼
                insight_ai_enrichment
```

**Luồng 2 (nâng cao) — Deep Analysis:** AI tự tìm pattern mà rule cố định bỏ sót → bắt buộc đi qua `AiInsightVerifier` đối chiếu từng con số với DB trước khi được lưu.

---

## PHẦN 2. DỮ LIỆU ĐẦU VÀO

### 2.1. Tầng 1 — Dữ liệu thô từ Database

`InsightMetrics` được dựng từ 5 nguồn, giới hạn trong khoảng thời gian `[fromDate, toDate]`:

| Nguồn | Bảng / Entity | Dùng để tính |
|---|---|---|
| Đơn hàng | `Booking` | tổng đơn, đơn hoàn thành, đơn hủy/từ chối/no-show, khung giờ |
| Hóa đơn đã thanh toán | `Invoice` (PAID) | doanh thu, giá trị đơn trung bình |
| Giao dịch điểm | `LoyaltyTransaction` | điểm tích (EARN), điểm tiêu |
| Đổi thưởng | `RewardRedemption` | tỷ lệ đổi điểm, tệp khách có đổi điểm |
| Dịch vụ | `ServicePackage` | doanh thu/số đơn theo từng dịch vụ |
| Trạng thái loyalty | `LoyaltyStateSnapshot` | điểm sắp hết hạn, khách sát ngưỡng lên hạng, hạ hạng |

**Điểm quan trọng:** hệ thống luôn dựng **2 kỳ** — kỳ hiện tại và **kỳ liền trước có độ dài bằng nhau** — để so sánh tăng/giảm.

### 2.2. Tầng 2 — Metrics đã tính (đầu vào của Rule Engine)

Ví dụ các chỉ số dẫn xuất:

- `revenue_change_percent`, `order_change_percent`
- `cancelled_order_rate_percent`, `completed_order_rate_percent`
- `average_revenue_per_order`
- `returning_order_share_percent`, `new_customers`, `returning_customers`
- `redemption_rate_against_earned_points_percent`
- Breakdown theo dịch vụ: `service_{id}_revenue_share_percent`, `service_{id}_order_share_percent`
- Breakdown theo khung giờ: `time_slot_{label}_order_share_percent`

### 2.3. Tầng 3 — Context Package (đầu vào của AI)

Đây là thứ **duy nhất** được gửi sang Gemini. Cấu trúc `InsightContext`:

```json
{
  "insightType": "REVENUE",
  "headline": {
    "ruleCode": "REVENUE_DROP",
    "severity": "WARNING",
    "title": "Doanh thu đang có dấu hiệu giảm",
    "evidence": "Kỳ hiện tại đạt 42.500.000đ, kỳ trước đạt 61.000.000đ.",
    "current":  { "totalRevenue": ..., "totalOrders": ..., ... },
    "previous": { "totalRevenue": ..., "totalOrders": ..., ... }
  },
  "breakdown": [
    { "dimension": "SERVICE",   "label": "Rửa xe cao cấp", "values": {...} },
    { "dimension": "TIME_SLOT", "label": "08:00-10:00",    "values": {...} }
  ],
  "trend": [ { "date": "...", "revenue": ... } ],
  "scope": {
    "fromDate": "2026-07-01", "toDate": "2026-07-31",
    "branchId": "ALL",
    "source": "RULE_BASED",
    "dataPolicy": "Only backend-aggregated numbers are included. No customer PII is provided."
  }
}
```

**Nhấn mạnh 2 ý khi thuyết trình:**
1. AI **không có quyền truy cập DB**. Nó chỉ nhìn thấy JSON này.
2. **Không có PII** — không tên khách, email, SĐT, biển số. Chỉ có số đã tổng hợp.

---

## PHẦN 3. DỮ LIỆU ĐẦU RA

### 3.1. Đầu ra tầng Rule (bảng `business_insight`)

| Trường | Ý nghĩa |
|---|---|
| `rule_code` | Mã rule đã kích hoạt, VD `REVENUE_DROP` |
| `type` | REVENUE / ORDER / SERVICE / CUSTOMER / LOYALTY |
| `severity` | POSITIVE / OPPORTUNITY / WARNING / CRITICAL |
| `title`, `summary` | Tiêu đề + tóm tắt |
| `evidence` | **Con số cụ thể làm bằng chứng** |
| `meaning`, `recommendation` | Ý nghĩa nghiệp vụ + gợi ý mặc định |
| `from_date`, `to_date`, `status` | Kỳ phân tích + NEW/VIEWED/DISMISSED |

Ràng buộc: **upsert theo `(rule_code, from_date, to_date)`** → chạy lại nhiều lần **không sinh bản ghi trùng** (idempotent — rất quan trọng vì có scheduler).

### 3.2. Đầu ra tầng AI (bảng `insight_ai_enrichment`)

Gemini **bắt buộc** trả về đúng schema JSON sau:

```json
{
  "aiSummary": "Doanh thu tháng 7 giảm 30% chủ yếu do nhóm dịch vụ cao cấp...",
  "aiExplanation": "Số đơn chỉ giảm 8% nhưng doanh thu giảm 30%, cho thấy...",
  "aiRecommendation": [
    "Rà soát lại giá gói cao cấp",
    "Đẩy upsell tại quầy cho khách khung 08:00-10:00",
    "Kích hoạt lại tệp khách chưa quay lại 45 ngày"
  ],
  "aiCampaignSuggestion": {
    "campaignName": "Weekday Double Point",
    "targetCustomers": "Khách có điểm khả dụng nhưng chưa đổi",
    "offer": "Nhân đôi điểm thứ 2 - thứ 5",
    "duration": "3 tuần",
    "goal": "Kéo đơn về khung thấp điểm, tăng tỷ lệ đổi điểm"
  },
  "confidenceScore": 0.82
}
```

Ngoài ra hệ thống lưu thêm **metadata truy vết**: `aiModel` (gemini-2.5-flash), `promptVersion` (v1), `source` (RULE_BASED / AI_DETECTED), `evidenceJson`, `verified`, `generatedAt`.

---

## PHẦN 4. CẤU HÌNH RULE — Cấu hình ở đâu, như thế nào?

Đây là phần thường bị hỏi nhiều nhất. Trả lời: **rule được cấu hình ở 2 tầng**.

### 4.1. Tầng code — giá trị mặc định (fallback)

**File:** `service/insight/InsightThresholds.java`

```java
public static final double REVENUE_CHANGE_PERCENT = 15.0;
public static final double ORDER_CANCEL_WARNING_PERCENT = 10.0;
public static final double PEAK_SLOT_SHARE_PERCENT = 30.0;
public static final int    EXPIRING_WINDOW_DAYS = 30;
public static final double MIN_EXPIRING_POINTS = 100.0;
public static final int    MAX_INSIGHTS = 10;   // giới hạn số insight mỗi kỳ
```

Vai trò: **giá trị an toàn** khi DB chưa có dòng config tương ứng → hệ thống vẫn chạy được.

### 4.2. Tầng database — cấu hình động, Owner sửa được

**Bảng:** `insight_rule_config` — mỗi rule là một dòng.

| Cột | Ví dụ | Ý nghĩa |
|---|---|---|
| `rule_code` | `REVENUE_DROP` | Khóa liên kết với code (UNIQUE) |
| `rule_name` | "Doanh thu giảm mạnh" | Tên hiển thị |
| `type` | `REVENUE` | Nhóm insight |
| `threshold_value` | `15.00` | **Ngưỡng kích hoạt** |
| `comparison_operator` | `LESS_THAN` | Toán tử so sánh |
| `severity` | `WARNING` | Mức độ nghiêm trọng |
| `is_active` | `TRUE` | **Bật/tắt rule** |
| `description` | "..." | Căn cứ chọn ngưỡng |

**Seed dữ liệu:** `src/main/resources/db/migration/`
- `V2__seed_tier_health_rules.sql` — 4 rule sức khỏe tier/loyalty
- `V4__ensure_all_insight_rules.sql` — đảm bảo đủ **19 rule_code**, dùng `ON CONFLICT DO NOTHING` để không ghi đè cấu hình Owner đã chỉnh
- `V6__seed_inactive_winback_rule.sql` — rule win-back khách inactive

### 4.3. API cấu hình (dành cho Owner/Admin)

```
GET    /api/owner/insight-rules        → danh sách rule + ngưỡng hiện tại
PATCH  /api/owner/insight-rules/{id}   → sửa threshold / operator / severity / active
```

Body PATCH cho phép sửa từng phần (partial update):
```json
{ "thresholdValue": 20, "severity": "CRITICAL", "active": true }
```

### 4.4. Cơ chế đọc config lúc runtime — `InsightRuleConfigRegistry`

Đây là mắt xích nối 2 tầng. Trước mỗi lần chạy, `InsightService` load toàn bộ config từ DB, index theo `rule_code`, rồi truyền vào context:

```java
List<InsightRuleConfig> ruleConfigs = insightRuleConfigRepository.findAll();
InsightAnalysisContext context = new InsightAnalysisContext(
        current, previous, OffsetDateTime.now(),
        InsightRuleConfigRegistry.from(ruleConfigs));
```

Registry cung cấp 3 phương thức, **luôn có fallback**:

```java
boolean active(ruleCode)                      // không có config → mặc định BẬT
double  threshold(ruleCode, fallback)         // không có → lấy InsightThresholds
InsightSeverity severity(ruleCode, fallback)  // không có → lấy mặc định trong rule
```

Trong rule sử dụng như sau (trích `RevenueInsightRule`):

```java
if (context.active(REVENUE_DROP)
        && revenueChange <= -context.threshold(REVENUE_DROP,
                                InsightThresholds.REVENUE_CHANGE_PERCENT)) {
    insights.add(new InsightResponse(
            REVENUE_DROP, type(),
            context.severity(REVENUE_DROP, InsightSeverity.WARNING),
            "Doanh thu đang có dấu hiệu giảm", ...));
}
```

> **Câu chốt để nói:** Owner đổi ngưỡng từ 15% → 20% trên giao diện, lần generate kế tiếp rule tự áp ngưỡng mới. **Không cần sửa code, không cần deploy lại.**

### 4.5. Danh mục 6 nhóm rule (19 rule_code)

| Rule class | rule_code tiêu biểu |
|---|---|
| `RevenueInsightRule` | REVENUE_GROWTH, REVENUE_DROP, WEEKEND_REVENUE_HIGH |
| `OrderInsightRule` | ORDER_CANCEL_RATE_HIGH, PEAK_HOUR_ORDERS, ORDER_VALUE_LOW |
| `ServiceInsightRule` | DOMINANT_SERVICE_REVENUE, LOW_SERVICE_USAGE, HIGH_VALUE_SERVICE |
| `CustomerInsightRule` | HIGH/LOW_RETURNING_CUSTOMER_RATE, HIGH_VALUE_CUSTOMER_GROUP |
| `LoyaltyInsightRule` | LOW_POINT_REDEMPTION, LOYALTY_UNUSED_POINTS, LOYALTY_EFFECTIVE |
| `TierHealthInsightRule` | POINTS_EXPIRING_SOON, UPGRADE_STALL, DOWNGRADE_WAVE, TIER_DISTRIBUTION_SKEW |

Rule engine gom kết quả, **sắp xếp theo `severity.priority`**, rồi cắt còn tối đa `MAX_INSIGHTS = 10`.

---

## PHẦN 5. Cơ chế chống "AI bịa số" (điểm nhấn kỹ thuật)

Đây là phần ghi điểm nhất khi bảo vệ. Hệ thống có **5 lớp phòng vệ**:

**Lớp 1 — Guardrail trong prompt**
`AIPromptBuilderService` ràng buộc rõ:
- "Chỉ dùng số liệu có trong Context Package"
- "Không bịa thêm số liệu mới, tỉ lệ mới, tên dịch vụ mới"
- "Mọi con số phải trace được về headline / breakdown / trend"
- "Nếu context không đủ dữ liệu → nói rõ *chưa đủ dữ liệu*, không suy đoán"

**Lớp 2 — Tham số model tất định**
`temperature = 0.0`, `thinkingBudget = 0`, `maxOutputTokens = 2048` → cùng input cho ra cùng output, dễ tái lập khi demo.

**Lớp 3 — Schema validation**
`AIResponseValidatorService` bắt buộc: `aiSummary`/`aiExplanation` không rỗng, `aiRecommendation` ≥ 1 phần tử, `aiCampaignSuggestion` đủ 5 trường, `confidenceScore ∈ [0, 1]`. Sai → HTTP **422**, không lưu DB.

**Lớp 4 — Chặn thuật ngữ sai ngữ cảnh**
AutoWash là hệ thống nội bộ, không phải sàn thương mại. Nếu output chứa "platform", "marketplace", "hoa hồng nền tảng", "doanh thu toàn sàn"… → **reject**.

**Lớp 5 — Numeric verification (luồng Deep Analysis)**
`AiInsightVerifier` đối chiếu **từng con số** AI đưa ra với `MetricSnapshot` của backend:

```java
BigDecimal dbValue = snapshot.valueOf(insight.evidence().metric());
if (dbValue == null)  → reject "metric does not exist in snapshot";
if (|dbValue - aiValue| > 0.01) → reject "AI evidence value does not match backend metric";
```

Insight bị reject **vẫn được trả về** trong danh sách `rejected` kèm lý do → minh bạch, có thể audit.

---

## PHẦN 6. Trigger — Chức năng chạy khi nào?

| Trigger | Cơ chế | Ghi chú |
|---|---|---|
| Tự động hằng ngày | `@Scheduled(cron = "0 30 0 * * *", zone="Asia/Ho_Chi_Minh")` | 00:30 — phân tích ngày hôm qua |
| Tự động hằng tháng | `@Scheduled(cron = "0 0 1 1 * *")` | 01:00 ngày 1 — phân tích tháng trước |
| Owner bấm "Làm mới phân tích" | `POST /api/owner/insights/generate` | |
| Auto-generate khi xem | `GET /api/owner/insights` | Nếu kỳ chưa có insight nào |
| Owner bấm "Phân tích bằng AI" | `POST /api/owner/insights/{id}/ai-enrich` | Lazy — chỉ gọi Gemini khi user yêu cầu |

**Tại sao AI là lazy, không chạy tự động?**
Mỗi lần gọi Gemini tốn quota + độ trễ ~15s. Chạy AI cho cả 10 insight × 30 ngày là lãng phí. Vì vậy:
- `ai-enrich` → nếu đã có enrichment thì **trả cache**, không gọi lại API
- `ai-regenerate` → có **cooldown 60 giây** (`AI_REGENERATE_COOLDOWN_SECONDS`) chống spam
- Lời gọi Gemini được đặt **ngoài transaction DB** để không giữ connection suốt 15s

---

## PHẦN 7. Danh sách API đầy đủ

```
GET    /api/owner/insights                     Danh sách insight (lọc theo kỳ/type/status)
GET    /api/owner/insights/{id}                Chi tiết + phần AI enrichment (nếu có)
POST   /api/owner/insights/generate            Chạy rule engine cho một kỳ
PATCH  /api/owner/insights/{id}/status         NEW → VIEWED → DISMISSED

POST   /api/owner/insights/{id}/ai-enrich      Sinh diễn giải AI (có cache)
POST   /api/owner/insights/{id}/ai-regenerate  Sinh lại (cooldown 60s)
POST   /api/owner/insights/deep-analysis       AI tự tìm pattern (có verify)
GET    /api/owner/insights/ai-health           Kiểm tra cấu hình Gemini

GET    /api/owner/insight-rules                Danh sách rule + ngưỡng
PATCH  /api/owner/insight-rules/{id}           Sửa ngưỡng / severity / bật-tắt
```

Phân quyền: `@PreAuthorize("hasAnyRole('OWNER','ADMIN')")` — riêng deep-analysis mở thêm cho `MANAGER`.

---

## PHẦN 8. Cấu hình hệ thống (`application.properties`)

```properties
gemini.api.key=${GEMINI_API_KEY}
gemini.model=${GEMINI_MODEL:gemini-2.5-flash}
gemini.api.base-url=${GEMINI_API_BASE_URL:https://generativelanguage.googleapis.com/v1beta/models}
gemini.prompt.version=${GEMINI_PROMPT_VERSION:v1}
gemini.temperature=${GEMINI_TEMPERATURE:0.0}
gemini.max-output-tokens=${GEMINI_MAX_OUTPUT_TOKENS:2048}
gemini.thinking-budget=${GEMINI_THINKING_BUDGET:0}
washmate.insight.ai.regenerate-cooldown-seconds=${AI_REGENERATE_COOLDOWN_SECONDS:60}
```

API key **không hardcode**, đọc từ biến môi trường. `promptVersion` được lưu cùng mỗi enrichment → khi đổi prompt vẫn biết bản ghi cũ sinh từ prompt nào.

---

## PHẦN 9. Kịch bản demo (đề xuất, ~3 phút)

1. Mở màn hình **Cấu hình rule** → chỉ vào `REVENUE_DROP`, threshold = 15.
2. Bấm **Làm mới phân tích** cho tháng 7 → xuất hiện insight *"Doanh thu đang có dấu hiệu giảm"* kèm evidence là con số thật.
3. Sửa threshold lên **40** → generate lại → insight biến mất. **Chứng minh rule cấu hình được từ DB, không cần sửa code.**
4. Hạ threshold về 15, generate lại → bấm **Phân tích bằng AI** → hiện `aiSummary`, `aiExplanation`, 3 khuyến nghị và 1 campaign đề xuất.
5. Bấm lại ngay lần nữa → trả về **429 cooldown** — cho thấy có kiểm soát chi phí.
6. Mở tab **Deep Analysis** → chỉ vào danh sách `rejected` kèm lý do → chứng minh có lớp verify số liệu.

---

## PHẦN 10. Câu hỏi phản biện thường gặp & gợi ý trả lời

**Q: AI có thể đưa ra số liệu sai không?**
A: Không lưu được. Enrichment bị chặn bởi schema validation; deep-analysis bị chặn bởi `AiInsightVerifier` — sai lệch quá 0.01 so với DB là reject.

**Q: Nếu Gemini sập / hết quota thì sao?**
A: Chức năng cốt lõi vẫn hoạt động. Rule engine hoàn toàn độc lập với AI. Owner vẫn thấy đủ insight, chỉ mất phần diễn giải. `GET /ai-health` báo trạng thái cấu hình.

**Q: Tại sao không để AI tự phát hiện toàn bộ?**
A: Ba lý do — (1) không tất định, khó test và khó bảo vệ; (2) chi phí API tăng tuyến tính theo dữ liệu; (3) không audit được. Rule-first cho phép viết unit test (`InsightRuleEngineTest`, `RevenueInsightRuleTest`…) và giải thích được vì sao insight xuất hiện.

**Q: Có gửi dữ liệu khách hàng ra ngoài không?**
A: Không. Context Package chỉ chứa số liệu tổng hợp, có `dataPolicy` ghi rõ và prompt cấm nhắc PII. Deep-analysis guardrail cấm tường minh tên khách, email, SĐT, biển số.

**Q: Chạy scheduler nhiều lần có sinh dữ liệu rác không?**
A: Không. Upsert theo `(rule_code, from_date, to_date)` — idempotent.

---

## Tổng kết — 5 ý cần khán giả nhớ

1. **Rule-first, AI-second** — backend phát hiện, AI chỉ diễn giải.
2. **Input 3 tầng**: dữ liệu thô → metrics → Context Package (không PII).
3. **Output 2 tầng**: `business_insight` (rule) + `insight_ai_enrichment` (AI, có metadata truy vết).
4. **Rule cấu hình động** ở bảng `insight_rule_config`, đọc runtime qua `InsightRuleConfigRegistry`, có fallback từ `InsightThresholds` — sửa ngưỡng không cần deploy.
5. **5 lớp chống bịa số**, trong đó lớp verify đối chiếu từng con số với DB là điểm khác biệt so với các hệ thống "gắn AI cho có".
