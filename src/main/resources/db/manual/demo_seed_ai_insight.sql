-- =====================================================================
-- SEED DỮ LIỆU DEMO CHO CHỨC NĂNG AI INSIGHT
-- =====================================================================
-- Chạy trên: Supabase SQL Editor (hoặc psql) — KHÔNG phải Flyway migration.
-- File đặt trong db/manual/ nên Flyway sẽ bỏ qua.
--
-- KỲ PHÂN TÍCH KHI DEMO:
--     Kỳ hiện tại : 2026-07-01  →  2026-07-15
--     Kỳ so sánh  : 2026-06-16  →  2026-06-30   (backend tự tính, không cần nhập)
--
-- SỐ LIỆU ĐƯỢC THIẾT KẾ ĐỂ KÍCH HOẠT 4 INSIGHT:
--   1. REVENUE_DROP            doanh thu 30.000.000 → 21.000.000  = -30%   (ngưỡng 15)
--   2. ORDER_CANCEL_RATE_HIGH  7 đơn hủy / 52 đơn   = 13.46%              (ngưỡng 10)
--   3. PEAK_HOUR_ORDERS        khung giờ đầu 20/52 đơn = 38.46%           (ngưỡng 30)
--   4. DOMINANT_SERVICE_REVENUE dịch vụ #1 chiếm 55% doanh thu            (ngưỡng 40)
--   (+ có thể xuất hiện thêm HIGH_RETURNING_CUSTOMER_RATE — insight POSITIVE, vô hại)
--
-- AN TOÀN:
--   * Script TỰ DÒ garage / dịch vụ / khung giờ / khách + xe đang có trong DB.
--   * Mọi booking sinh ra đều có booking_code bắt đầu bằng 'DEMO-'.
--   * Chạy lại nhiều lần được: đầu script tự xóa dữ liệu DEMO- cũ, không đụng dữ liệu thật.
--   * Không tạo user, không tạo xe, không sửa bảng cấu hình rule.
-- =====================================================================

-- =====================================================================
-- BƯỚC 0 (CHẠY RIÊNG TRƯỚC): kiểm tra 2 kỳ demo đã có dữ liệu thật chưa
-- ---------------------------------------------------------------------
-- Nếu kết quả trả về 0 dòng  → an toàn, chạy tiếp BƯỚC 1.
-- Nếu có dòng                → DB đã có booking thật trong 2 kỳ này, số liệu
--                              sẽ bị cộng dồn và lệch khỏi thiết kế. Khi đó
--                              hãy đổi 4 mốc ngày trong file sang khoảng trống.
-- =====================================================================
SELECT b.booking_date, COUNT(*) AS so_don_that
  FROM booking b
 WHERE b.booking_code NOT LIKE 'DEMO-%'
   AND b.booking_date BETWEEN DATE '2026-06-16' AND DATE '2026-07-15'
 GROUP BY b.booking_date
 ORDER BY b.booking_date;


BEGIN;

-- ---------------------------------------------------------------------
-- BƯỚC 1: Dọn dữ liệu demo của lần chạy trước (nếu có)
--         Xóa theo đúng thứ tự phụ thuộc khóa ngoại (đều là ON DELETE RESTRICT).
-- ---------------------------------------------------------------------
CREATE TEMP TABLE tmp_demo_bookings ON COMMIT DROP AS
SELECT booking_id FROM booking WHERE booking_code LIKE 'DEMO-%';

DELETE FROM penalty_fee         WHERE booking_id IN (SELECT booking_id FROM tmp_demo_bookings);
DELETE FROM loyalty_transaction WHERE booking_id IN (SELECT booking_id FROM tmp_demo_bookings);
DELETE FROM invoice             WHERE booking_id IN (SELECT booking_id FROM tmp_demo_bookings);
DELETE FROM payment_transaction WHERE payment_id IN
       (SELECT payment_id FROM payment WHERE booking_id IN (SELECT booking_id FROM tmp_demo_bookings));
DELETE FROM payment             WHERE booking_id IN (SELECT booking_id FROM tmp_demo_bookings);
DELETE FROM booking             WHERE booking_id IN (SELECT booking_id FROM tmp_demo_bookings);

-- ---------------------------------------------------------------------
-- BƯỚC 2: Sinh dữ liệu
-- ---------------------------------------------------------------------
DO $$
DECLARE
    v_garage_id  INT;
    v_svc        INT[];       -- id các dịch vụ (tối đa 3)
    v_slot       INT[];       -- id các khung giờ (tối đa 3)
    v_user       INT[];       -- id khách hàng
    v_veh        INT[];       -- id xe TƯƠNG ỨNG từng khách (cùng chỉ số)
    v_pairs      INT;         -- số cặp khách-xe khả dụng

    i            INT;
    v_seq        INT := 0;
    v_bid        INT;
    v_date       DATE;
    v_amount     NUMERIC(10,2);
    v_svc_id     INT;
    v_slot_id    INT;
    v_idx        INT;
    v_status     TEXT;
BEGIN
    ------------------------------------------------------------------
    -- 2.1. Chọn garage có đủ dữ liệu nền nhất
    ------------------------------------------------------------------
    SELECT g.garage_id INTO v_garage_id
      FROM garage g
     WHERE EXISTS (SELECT 1 FROM booking_slot     s WHERE s.garage_id = g.garage_id AND s.status = 'ACTIVE')
       AND EXISTS (SELECT 1 FROM service_package sp WHERE sp.garage_id = g.garage_id AND sp.status = 'ACTIVE')
     ORDER BY (SELECT COUNT(*) FROM service_package sp WHERE sp.garage_id = g.garage_id AND sp.status = 'ACTIVE') DESC,
              g.garage_id
     LIMIT 1;

    IF v_garage_id IS NULL THEN
        RAISE EXCEPTION 'Khong tim thay garage nao co ca booking_slot ACTIVE lan service_package ACTIVE.';
    END IF;

    ------------------------------------------------------------------
    -- 2.2. Lấy tối đa 3 dịch vụ và 3 khung giờ của garage đó
    ------------------------------------------------------------------
    SELECT ARRAY(SELECT service_id FROM service_package
                  WHERE garage_id = v_garage_id AND status = 'ACTIVE'
                  ORDER BY price DESC, service_id LIMIT 3)
      INTO v_svc;

    SELECT ARRAY(SELECT slot_id FROM booking_slot
                  WHERE garage_id = v_garage_id AND status = 'ACTIVE'
                  ORDER BY start_time, slot_id LIMIT 3)
      INTO v_slot;

    ------------------------------------------------------------------
    -- 2.3. Lấy các cặp (khách hàng, xe của chính khách đó)
    --      Bắt buộc đúng cặp vì booking có FK ghép (vehicle_id, user_id).
    ------------------------------------------------------------------
    SELECT ARRAY(SELECT user_id    FROM (
                     SELECT DISTINCT ON (v.user_id) v.user_id, v.vehicle_id
                       FROM vehicle v
                       JOIN app_user u ON u.user_id = v.user_id
                      WHERE v.status = 'ACTIVE' AND u.status = 'ACTIVE'
                      ORDER BY v.user_id, v.vehicle_id
                 ) t ORDER BY user_id LIMIT 20),
           ARRAY(SELECT vehicle_id FROM (
                     SELECT DISTINCT ON (v.user_id) v.user_id, v.vehicle_id
                       FROM vehicle v
                       JOIN app_user u ON u.user_id = v.user_id
                      WHERE v.status = 'ACTIVE' AND u.status = 'ACTIVE'
                      ORDER BY v.user_id, v.vehicle_id
                 ) t ORDER BY user_id LIMIT 20)
      INTO v_user, v_veh;

    v_pairs := COALESCE(array_length(v_user, 1), 0);

    IF v_pairs = 0 THEN
        RAISE EXCEPTION 'Khong tim thay khach hang ACTIVE nao co xe ACTIVE. Hay tao it nhat 1 khach + 1 xe truoc.';
    END IF;

    RAISE NOTICE 'Garage=% | so dich vu=% | so khung gio=% | so cap khach-xe=%',
                 v_garage_id, array_length(v_svc,1), array_length(v_slot,1), v_pairs;

    ------------------------------------------------------------------
    -- 2.4. KỲ TRƯỚC: 2026-06-16 → 2026-06-30
    --      60 đơn hoàn thành, doanh thu 30.000.000
    ------------------------------------------------------------------
    FOR i IN 0..59 LOOP
        v_seq  := v_seq + 1;
        v_date := DATE '2026-06-16' + (i % 15);

        -- 24 đơn dịch vụ A / 20 đơn dịch vụ B / 16 đơn dịch vụ C
        IF i < 24 THEN
            v_idx := 1; v_amount := 550000;
        ELSIF i < 44 THEN
            v_idx := 2; v_amount := 500000;
        ELSE
            v_idx := 3; v_amount := 425000;
        END IF;
        v_svc_id := v_svc[LEAST(v_idx, array_length(v_svc,1))];

        -- khung giờ chia đều 20/20/20 → kỳ trước KHÔNG có giờ cao điểm
        v_slot_id := v_slot[LEAST((i / 20) + 1, array_length(v_slot,1))];

        INSERT INTO booking (booking_code, user_id, garage_id, slot_id, service_id, vehicle_id,
                             booking_date, total_amount, discount_amount, final_amount,
                             status, completed_time, created_at)
        VALUES ('DEMO-' || LPAD(v_seq::TEXT, 5, '0'),
                v_user[(i % v_pairs) + 1], v_garage_id, v_slot_id, v_svc_id, v_veh[(i % v_pairs) + 1],
                v_date, v_amount, 0, v_amount,
                'COMPLETED',
                (v_date + TIME '11:00') AT TIME ZONE 'Asia/Ho_Chi_Minh',
                (v_date + TIME '08:00') AT TIME ZONE 'Asia/Ho_Chi_Minh')
        RETURNING booking_id INTO v_bid;

        INSERT INTO invoice (invoice_code, booking_id, garage_id, subtotal, discount,
                             penalty_total, total_amount, status, issued_at, paid_at)
        VALUES ('DEMOINV-' || LPAD(v_seq::TEXT, 5, '0'), v_bid, v_garage_id,
                v_amount, 0, 0, v_amount, 'PAID',
                (v_date + TIME '11:05') AT TIME ZONE 'Asia/Ho_Chi_Minh',
                (v_date + TIME '11:10') AT TIME ZONE 'Asia/Ho_Chi_Minh');
    END LOOP;

    -- kỳ trước: 3 đơn không hoàn tất (tỷ lệ 4.8% — dưới ngưỡng, để làm nền so sánh)
    FOR i IN 0..2 LOOP
        v_seq  := v_seq + 1;
        v_date := DATE '2026-06-16' + (i * 4);
        v_status := CASE WHEN i < 2 THEN 'CANCELLED' ELSE 'NO_SHOW' END;

        INSERT INTO booking (booking_code, user_id, garage_id, slot_id, service_id, vehicle_id,
                             booking_date, total_amount, discount_amount, final_amount,
                             status, cancelled_at, no_show_at, created_at)
        VALUES ('DEMO-' || LPAD(v_seq::TEXT, 5, '0'),
                v_user[(i % v_pairs) + 1], v_garage_id,
                v_slot[LEAST(2, array_length(v_slot,1))],
                v_svc[LEAST(3, array_length(v_svc,1))],
                v_veh[(i % v_pairs) + 1],
                v_date, 400000, 0, 400000,
                v_status,
                CASE WHEN v_status = 'CANCELLED' THEN (v_date + TIME '07:00') AT TIME ZONE 'Asia/Ho_Chi_Minh' END,
                CASE WHEN v_status = 'NO_SHOW'   THEN (v_date + TIME '10:00') AT TIME ZONE 'Asia/Ho_Chi_Minh' END,
                (v_date + TIME '06:00') AT TIME ZONE 'Asia/Ho_Chi_Minh');
    END LOOP;

    ------------------------------------------------------------------
    -- 2.5. KỲ HIỆN TẠI: 2026-07-01 → 2026-07-15
    --      45 đơn hoàn thành, doanh thu 21.000.000  (giảm đúng 30%)
    ------------------------------------------------------------------
    FOR i IN 0..44 LOOP
        v_seq  := v_seq + 1;
        v_date := DATE '2026-07-01' + (i % 15);

        -- 20 đơn dịch vụ A (55% doanh thu) / 15 đơn B / 10 đơn C
        IF i < 20 THEN
            v_idx := 1; v_amount := 577500;
        ELSIF i < 35 THEN
            v_idx := 2; v_amount := 420000;
        ELSE
            v_idx := 3; v_amount := 315000;
        END IF;
        v_svc_id := v_svc[LEAST(v_idx, array_length(v_svc,1))];

        -- DỒN 20 đơn vào khung giờ đầu tiên → tạo giờ cao điểm 38.46%
        IF i < 20 THEN
            v_slot_id := v_slot[1];
        ELSIF i < 33 THEN
            v_slot_id := v_slot[LEAST(2, array_length(v_slot,1))];
        ELSE
            v_slot_id := v_slot[LEAST(3, array_length(v_slot,1))];
        END IF;

        INSERT INTO booking (booking_code, user_id, garage_id, slot_id, service_id, vehicle_id,
                             booking_date, total_amount, discount_amount, final_amount,
                             status, completed_time, created_at)
        VALUES ('DEMO-' || LPAD(v_seq::TEXT, 5, '0'),
                v_user[(i % v_pairs) + 1], v_garage_id, v_slot_id, v_svc_id, v_veh[(i % v_pairs) + 1],
                v_date, v_amount, 0, v_amount,
                'COMPLETED',
                (v_date + TIME '11:00') AT TIME ZONE 'Asia/Ho_Chi_Minh',
                (v_date + TIME '08:00') AT TIME ZONE 'Asia/Ho_Chi_Minh')
        RETURNING booking_id INTO v_bid;

        INSERT INTO invoice (invoice_code, booking_id, garage_id, subtotal, discount,
                             penalty_total, total_amount, status, issued_at, paid_at)
        VALUES ('DEMOINV-' || LPAD(v_seq::TEXT, 5, '0'), v_bid, v_garage_id,
                v_amount, 0, 0, v_amount, 'PAID',
                (v_date + TIME '11:05') AT TIME ZONE 'Asia/Ho_Chi_Minh',
                (v_date + TIME '11:10') AT TIME ZONE 'Asia/Ho_Chi_Minh');
    END LOOP;

    -- kỳ hiện tại: 7 đơn không hoàn tất → 7/52 = 13.46% (vượt ngưỡng 10%)
    FOR i IN 0..6 LOOP
        v_seq  := v_seq + 1;
        v_date := DATE '2026-07-01' + (i * 2);
        v_status := CASE WHEN i < 4 THEN 'CANCELLED' ELSE 'NO_SHOW' END;

        INSERT INTO booking (booking_code, user_id, garage_id, slot_id, service_id, vehicle_id,
                             booking_date, total_amount, discount_amount, final_amount,
                             status, cancelled_at, no_show_at, created_at)
        VALUES ('DEMO-' || LPAD(v_seq::TEXT, 5, '0'),
                v_user[(i % v_pairs) + 1], v_garage_id,
                v_slot[LEAST(2, array_length(v_slot,1))],
                v_svc[LEAST(3, array_length(v_svc,1))],
                v_veh[(i % v_pairs) + 1],
                v_date, 400000, 0, 400000,
                v_status,
                CASE WHEN v_status = 'CANCELLED' THEN (v_date + TIME '07:00') AT TIME ZONE 'Asia/Ho_Chi_Minh' END,
                CASE WHEN v_status = 'NO_SHOW'   THEN (v_date + TIME '10:00') AT TIME ZONE 'Asia/Ho_Chi_Minh' END,
                (v_date + TIME '06:00') AT TIME ZONE 'Asia/Ho_Chi_Minh');
    END LOOP;

    RAISE NOTICE 'Da tao % booking DEMO-. Chay phan KIEM TRA ben duoi de doi chieu.', v_seq;
END $$;

COMMIT;

-- =====================================================================
-- KIỂM TRA — chạy sau khi seed, đối chiếu với cột "Kỳ vọng"
-- =====================================================================
-- Kỳ vọng:
--   2026-06-16..30 : 60 don hoan thanh | 3 don huy  | doanh thu 30,000,000
--   2026-07-01..15 : 45 don hoan thanh | 7 don huy  | doanh thu 21,000,000
--   => thay doi doanh thu = -30.00%    | ty le huy ky hien tai = 13.46%
-- ---------------------------------------------------------------------
SELECT
    CASE WHEN b.booking_date <= DATE '2026-06-30' THEN '1. Ky truoc (06-16..06-30)'
         ELSE                                          '2. Ky hien tai (07-01..07-15)' END AS ky,
    COUNT(*) FILTER (WHERE b.status = 'COMPLETED')                      AS don_hoan_thanh,
    COUNT(*) FILTER (WHERE b.status IN ('CANCELLED','NO_SHOW'))         AS don_huy,
    COUNT(*)                                                            AS tong_don,
    ROUND(100.0 * COUNT(*) FILTER (WHERE b.status IN ('CANCELLED','NO_SHOW'))
          / NULLIF(COUNT(*), 0), 2)                                     AS ty_le_huy_pct,
    COALESCE(SUM(i.total_amount) FILTER (WHERE i.status = 'PAID'), 0)   AS doanh_thu
FROM booking b
LEFT JOIN invoice i ON i.booking_id = b.booking_id
WHERE b.booking_code LIKE 'DEMO-%'
GROUP BY 1
ORDER BY 1;

-- Tỷ trọng doanh thu theo dịch vụ trong kỳ hiện tại (dịch vụ đầu phải > 40%)
SELECT sp.name AS dich_vu,
       COUNT(*) AS so_don,
       SUM(i.total_amount) AS doanh_thu,
       ROUND(100.0 * SUM(i.total_amount) / SUM(SUM(i.total_amount)) OVER (), 2) AS ty_trong_pct
FROM booking b
JOIN invoice i         ON i.booking_id = b.booking_id AND i.status = 'PAID'
JOIN service_package sp ON sp.service_id = b.service_id
WHERE b.booking_code LIKE 'DEMO-%'
  AND b.booking_date BETWEEN DATE '2026-07-01' AND DATE '2026-07-15'
GROUP BY sp.name
ORDER BY doanh_thu DESC;

-- Tỷ trọng đơn theo khung giờ trong kỳ hiện tại (khung đầu phải > 30%)
SELECT TO_CHAR(s.start_time, 'HH24:MI') || '-' || TO_CHAR(s.end_time, 'HH24:MI') AS khung_gio,
       COUNT(*) FILTER (WHERE b.status = 'COMPLETED') AS so_don,
       ROUND(100.0 * COUNT(*) FILTER (WHERE b.status = 'COMPLETED')
             / NULLIF((SELECT COUNT(*) FROM booking b2
                        WHERE b2.booking_code LIKE 'DEMO-%'
                          AND b2.booking_date BETWEEN DATE '2026-07-01' AND DATE '2026-07-15'), 0), 2) AS ty_trong_pct
FROM booking b
JOIN booking_slot s ON s.slot_id = b.slot_id
WHERE b.booking_code LIKE 'DEMO-%'
  AND b.booking_date BETWEEN DATE '2026-07-01' AND DATE '2026-07-15'
GROUP BY s.start_time, s.end_time
ORDER BY so_don DESC;


-- =====================================================================
-- GỠ DỮ LIỆU DEMO SAU KHI XONG (chạy riêng khi cần)
-- =====================================================================
-- BEGIN;
-- CREATE TEMP TABLE tmp_cleanup ON COMMIT DROP AS
--   SELECT booking_id FROM booking WHERE booking_code LIKE 'DEMO-%';
-- DELETE FROM penalty_fee         WHERE booking_id IN (SELECT booking_id FROM tmp_cleanup);
-- DELETE FROM loyalty_transaction WHERE booking_id IN (SELECT booking_id FROM tmp_cleanup);
-- DELETE FROM invoice             WHERE booking_id IN (SELECT booking_id FROM tmp_cleanup);
-- DELETE FROM payment             WHERE booking_id IN (SELECT booking_id FROM tmp_cleanup);
-- DELETE FROM booking             WHERE booking_id IN (SELECT booking_id FROM tmp_cleanup);
-- DELETE FROM insight_ai_enrichment WHERE business_insight_id IN (
--     SELECT insight_id FROM business_insight
--      WHERE from_date = DATE '2026-07-01' AND to_date = DATE '2026-07-15');
-- DELETE FROM business_insight WHERE from_date = DATE '2026-07-01' AND to_date = DATE '2026-07-15';
-- COMMIT;
