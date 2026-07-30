# AI Insight — Kịch bản demo (5–7 phút)

---

## 1. Mở đầu (30 giây — nói, không cần slide)

> "Dashboard cho chủ tiệm rất nhiều số, nhưng số không nói cho họ biết phải làm gì.
> Chức năng AI Insight trả lời 3 câu: **Đang xảy ra chuyện gì — Vì sao — Nên làm gì.**
>
> Điểm quan trọng nhất của thiết kế này: **AI không phát hiện vấn đề. Backend phát hiện bằng rule, AI chỉ diễn giải.**
> Vì vậy hệ thống không bịa số."

---

## 2. Luồng xử lý (1 phút — 1 slide sơ đồ duy nhất)

```
Booking / Invoice / Loyalty
        ↓  gom số theo kỳ (kỳ này vs kỳ trước)
   InsightMetrics
        ↓  so với ngưỡng trong bảng insight_rule_config
   Rule Engine  ──→  business_insight   ("Doanh thu giảm 30%")
        ↓  Owner bấm "Phân tích bằng AI"
   Context Package (JSON, không có thông tin khách)
        ↓
   Gemini ──→ validate ──→ insight_ai_enrichment  (giải thích + đề xuất)
```

Chỉ cần nói 1 câu cho mỗi mũi tên. Không đi vào tên class.

---

## 3. Đầu vào / Đầu ra (1 phút — 1 slide bảng)

| | Nội dung |
|---|---|
| **Vào** | Booking, Invoice đã thanh toán, giao dịch điểm — trong kỳ đang xét **và kỳ liền trước** để so sánh |
| **Rule tính ra** | % thay đổi doanh thu, tỷ lệ đơn hủy, tỷ trọng dịch vụ, khung giờ cao điểm… |
| **Ra (tầng rule)** | `rule_code`, `severity`, tiêu đề, **evidence là số thật**, kỳ phân tích |
| **Ra (tầng AI)** | `aiSummary`, `aiExplanation`, 3 khuyến nghị, 1 campaign đề xuất, `confidenceScore` |

Một câu chốt: **"Cái gửi cho AI chỉ là số đã tổng hợp — không có tên, SĐT hay biển số khách."**

---

## 4. Cấu hình rule (1 phút — vừa nói vừa mở màn hình)

- Ngưỡng nằm trong bảng **`insight_rule_config`** — mỗi rule 1 dòng: `rule_code`, `threshold_value`, `severity`, `is_active`.
- Owner sửa qua màn hình quản lý rule (`PATCH /api/owner/insight-rules/{id}`).
- Mỗi lần chạy phân tích, backend load lại config từ DB rồi mới so ngưỡng.

> **Câu chốt: "Đổi ngưỡng từ 15% lên 40% là sửa dữ liệu, không phải sửa code — không cần deploy lại."**

---

## 5. DEMO trên máy (2–3 phút — phần chính)

**Bước 1.** Mở màn hình cấu hình rule → chỉ vào `REVENUE_DROP`, threshold = **15**.

**Bước 2.** Bấm **Làm mới phân tích** cho tháng 7 → insight *"Doanh thu đang có dấu hiệu giảm"* hiện ra, đọc to phần evidence có số thật.

**Bước 3.** Sửa threshold thành **40** → generate lại → **insight biến mất**.
> "Đây là bằng chứng rule chạy theo cấu hình trong DB."

**Bước 4.** Trả về 15, generate lại → bấm **Phân tích bằng AI** → đọc `aiSummary` và 1 khuyến nghị.

**Bước 5.** Bấm lại ngay → **429 cooldown**.
> "Mỗi lần gọi AI là tốn quota, nên có cooldown 60 giây."

Nếu còn thời gian mới làm bước 6, không thì bỏ.

**Bước 6 (tùy chọn).** Tab Deep Analysis → chỉ vào danh sách `rejected`.
> "AI đề xuất insight nhưng số không khớp DB nên bị loại. Backend đối chiếu từng con số trước khi lưu."

---

## 6. Chốt (20 giây)

> "Tóm lại: rule phát hiện, AI diễn giải. Ngưỡng cấu hình được từ database.
> Nếu Gemini sập, chức năng vẫn chạy — chỉ mất phần diễn giải."

---

# Phần dự phòng — chỉ dùng khi bị hỏi

**AI có thể đưa số sai không?**
Không lưu được. Response phải đúng schema mới nhận; ở luồng deep-analysis, mỗi con số bị đối chiếu với DB, lệch quá 0.01 là loại.

**Gemini sập thì sao?**
Rule engine độc lập hoàn toàn với AI. Owner vẫn thấy đủ insight.

**Sao không để AI tự tìm hết?**
Không tất định nên khó test và khó giải thích; tốn quota; không audit được. Rule-first thì viết được unit test.

**Chạy tự động lúc nào?**
Scheduler 00:30 mỗi ngày và 01:00 ngày 1 hàng tháng. Ghi theo `(rule_code, from_date, to_date)` nên chạy lại không sinh trùng.

**Sao AI không chạy tự động luôn?**
Tốn quota và mất ~15 giây mỗi lần. Chỉ gọi khi Owner bấm, và có cache — bấm lần 2 trả lại kết quả cũ.

**Có gửi dữ liệu khách ra ngoài không?**
Không. Chỉ gửi số tổng hợp; prompt cấm nhắc thông tin cá nhân.

---

*Bản đầy đủ (kiến trúc, danh sách 19 rule, toàn bộ API, 5 lớp validate): xem `AI-Insight-Presentation.md` — dùng để viết báo cáo, không dùng để đứng demo.*
