-- Seed LoyaltyPolicy mặc định cho mọi garage ACTIVE chưa có policy.
--
-- LÝ DO: trước migration này KHÔNG có chỗ nào tạo loyalty_policy ngoài API admin
-- (POST /api/v1/admin/loyalty/policy). Trong khi đó LoyaltyService.accruePoints() lại
-- yêu cầu policy ACTIVE, và nó được gọi từ BookingService.complete() -> mọi garage chưa
-- được tạo policy bằng tay sẽ KHÔNG hoàn tất được đơn nào.
--
-- Code đã được sửa để bỏ qua tích điểm khi thiếu policy (không còn làm hỏng complete()),
-- nhưng seed này cần thiết để loyalty THỰC SỰ hoạt động thay vì im lặng không cộng điểm.
--
-- Giá trị dùng đúng DEFAULT của schema (V1): 10.000đ = 1 điểm, điểm hết hạn 12 tháng,
-- auto-enroll bật — khớp yêu cầu "Points expire after 12 months" của đề.
--
-- Idempotent: ON CONFLICT theo ràng buộc UNIQUE(garage_id) nên chạy lại an toàn và
-- KHÔNG ghi đè policy mà admin đã tự cấu hình.

-- Đồng bộ sequence trước khi insert: baseline có thể đã nạp dữ liệu mà không đẩy sequence,
-- khiến INSERT mới xin trùng policy_id (cùng lý do như V2/V3).
SELECT setval(
    pg_get_serial_sequence('loyalty_policy', 'policy_id'),
    GREATEST(COALESCE((SELECT MAX(policy_id) FROM loyalty_policy), 0), 1)
);

INSERT INTO loyalty_policy (garage_id, amount_per_point, point_expiry_months, auto_enroll, status)
SELECT g.garage_id, 10000, 12, TRUE, 'ACTIVE'
FROM garage g
WHERE g.status = 'ACTIVE'
ON CONFLICT (garage_id) DO NOTHING;

-- Garage đã có policy nhưng bị soft-delete (status='DELETED') thì KHÔNG tự bật lại ở đây:
-- đó là quyết định nghiệp vụ của admin. Lưu ý ràng buộc UNIQUE(garage_id) khiến không thể
-- tạo policy mới cho garage đó qua API (xem AUDIT_4_CHUC_NANG_CHINH.md - D/H12): cần sửa
-- LoyaltyPolicyServiceImpl.create() để reactivate row cũ thay vì insert row mới.
