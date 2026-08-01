-- ============================================================================
-- DỌN HẬU QUẢ: BookingNoShowScheduler quét ngược vô hạn vào dữ liệu lịch sử
--
-- Bản đầu của scheduler dùng query `bookingDate <= today` KHÔNG có giới hạn dưới, nên
-- lần chạy đầu tiên đã đánh NO_SHOW cho MỌI đơn CONFIRMED tồn đọng từ trước — phần lớn
-- là rác dữ liệu (staff quên bấm complete, đơn kẹt từ đợt lỗi cũ), không phải khách lỡ hẹn.
--
-- Hậu quả cần gỡ:
--   1. Khách nhận thông báo "bạn đã vắng mặt" cho đơn từ hàng tháng trước
--   2. Án tích vắng mặt (2 lần / 90 ngày) khiến khách THẬT bị khoá quyền đặt lịch
--
-- Cách xử lý: chuyển các bản ghi bị đánh oan sang CANCELLED. Slot vẫn được giải phóng
-- (mục đích ban đầu), nhưng KHÔNG còn tính vào án tích — vì countRecentNoShows chỉ đếm
-- đơn còn ở trạng thái NO_SHOW.
--
-- CHẠY THỦ CÔNG, KHÔNG phải migration: cần người xem kết quả bước 1 rồi mới quyết định.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- BƯỚC 1 — XEM TRƯỚC. Chạy riêng, đọc kỹ trước khi sang bước 2.
-- Đổi mốc thời gian cho khớp thời điểm bạn khởi động app lần đầu sau khi deploy.
-- ---------------------------------------------------------------------------
SELECT
    b.booking_id,
    b.booking_code,
    b.booking_date,
    b.no_show_at,
    p.status  AS payment_status,
    u.email
FROM booking b
JOIN app_user u ON u.user_id = b.user_id
LEFT JOIN payment p ON p.booking_id = b.booking_id
WHERE b.status = 'NO_SHOW'
  -- Dấu hiệu bị đánh oan: no_show_at là VỪA XONG, nhưng booking_date thì đã lâu.
  -- Đơn lỡ hẹn thật có hai mốc này sát nhau (chênh vài chục phút).
  AND b.no_show_at >= NOW() - INTERVAL '1 day'
  AND b.booking_date < CURRENT_DATE - INTERVAL '2 days'
ORDER BY b.booking_date;

-- ---------------------------------------------------------------------------
-- BƯỚC 2 — GỠ ÁN. Chỉ chạy sau khi đã xem bước 1 và thấy đúng.
-- ---------------------------------------------------------------------------
BEGIN;

UPDATE booking
SET status         = 'CANCELLED',
    cancelled_at   = NOW(),
    rejection_reason = COALESCE(rejection_reason, '')
                       || '[Hệ thống] Gỡ đánh dấu vắng mặt do quét nhầm dữ liệu tồn đọng'
WHERE status = 'NO_SHOW'
  AND no_show_at >= NOW() - INTERVAL '1 day'
  AND booking_date < CURRENT_DATE - INTERVAL '2 days';

-- Kiểm tra số dòng vừa đổi có khớp với bước 1 không. Khớp thì COMMIT, lệch thì ROLLBACK.
COMMIT;

-- ---------------------------------------------------------------------------
-- BƯỚC 3 — Dọn thông báo sai đã gửi cho khách (chưa đọc thì xoá luôn cho đỡ hoang mang).
-- ---------------------------------------------------------------------------
DELETE FROM notification
WHERE title = 'Đơn đặt lịch được đánh dấu vắng mặt'
  AND created_at >= NOW() - INTERVAL '1 day'
  AND is_read = FALSE
  AND booking_id IN (
      SELECT booking_id FROM booking
      WHERE status = 'CANCELLED'
        AND rejection_reason LIKE '%quét nhầm dữ liệu tồn đọng%'
  );

-- ---------------------------------------------------------------------------
-- BƯỚC 4 — Đối chiếu: còn khách nào đang bị khoá quyền đặt lịch không?
-- (ngưỡng mặc định: 2 lần NO_SHOW trong 90 ngày)
-- ---------------------------------------------------------------------------
SELECT u.user_id, u.email, COUNT(*) AS no_show_count
FROM booking b
JOIN app_user u ON u.user_id = b.user_id
WHERE b.status = 'NO_SHOW'
  AND b.no_show_at >= NOW() - INTERVAL '90 days'
GROUP BY u.user_id, u.email
HAVING COUNT(*) >= 2
ORDER BY no_show_count DESC;
