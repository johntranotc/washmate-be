-- ============================================================================
-- V12: Cho phép hoãn kiểm tra FK composite (booking_id, garage_id).
--
-- payment và invoice đều tham chiếu booking bằng cặp (booking_id, garage_id).
-- Luồng "staff chuyển đơn sang garage khác" (BookingService.updateBooking) sửa
-- garage_id trên CẢ booking lẫn payment trong cùng một transaction.
--
-- Hibernate mặc định KHÔNG sắp thứ tự UPDATE giữa các entity khác nhau
-- (hibernate.order_updates = false), nên thứ tự phát câu lệnh phụ thuộc vào
-- thứ tự duyệt persistence context. Nếu UPDATE payment chạy trước UPDATE booking,
-- cặp (booking_id, garage_id) mới chưa tồn tại bên booking -> Postgres kiểm tra
-- FK ngay cuối statement -> foreign key violation.
--
-- Triệu chứng điển hình: chuyển garage lúc được lúc không, không tái hiện ổn định.
--
-- Cách sửa: đánh dấu hai FK này DEFERRABLE INITIALLY DEFERRED để Postgres dồn
-- việc kiểm tra tới lúc COMMIT, khi cả hai bảng đã nhất quán. Ràng buộc vẫn được
-- thực thi đầy đủ, chỉ đổi THỜI ĐIỂM kiểm tra.
-- ============================================================================

-- Gỡ MỌI FK composite (booking_id, garage_id) -> booking đang tồn tại, bất kể tên.
-- Không hardcode tên mặc định của Postgres: nếu bảng từng được tạo bằng đường khác
-- (Hibernate ddl-auto, script tay) thì tên constraint sẽ khác, DROP ... IF EXISTS
-- theo tên cố định sẽ âm thầm không làm gì và ta lại thêm FK thứ hai — cái FK cũ
-- KHÔNG deferrable vẫn tiếp tục gây lỗi.
DO $$
DECLARE
    c RECORD;
BEGIN
    FOR c IN
        SELECT con.conname, rel.relname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_class ref ON ref.oid = con.confrelid
        JOIN pg_namespace ns ON ns.oid = rel.relnamespace
        WHERE con.contype = 'f'
          AND ns.nspname = 'public'
          AND rel.relname IN ('payment', 'invoice')
          AND ref.relname = 'booking'
          AND array_length(con.conkey, 1) = 2
    LOOP
        EXECUTE format('ALTER TABLE public.%I DROP CONSTRAINT %I', c.relname, c.conname);
    END LOOP;
END
$$;

ALTER TABLE payment
    ADD CONSTRAINT payment_booking_id_garage_id_fkey
    FOREIGN KEY (booking_id, garage_id) REFERENCES booking(booking_id, garage_id)
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE invoice
    ADD CONSTRAINT invoice_booking_id_garage_id_fkey
    FOREIGN KEY (booking_id, garage_id) REFERENCES booking(booking_id, garage_id)
    DEFERRABLE INITIALLY DEFERRED;
