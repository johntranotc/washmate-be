-- ============================================================================
-- V11: Bù 5 bảng bị thiếu hoàn toàn khỏi migration.
--
-- Các bảng notification, promotion, promotion_usage, reward, reward_redemption
-- có entity + repository và được luồng booking/payment dùng liên tục, nhưng chưa
-- từng được khai báo trong db/migration. Hậu quả:
--   - Profile "local" dùng ddl-auto=create-drop + flyway tắt -> Hibernate tự sinh
--     bảng nên chạy bình thường.
--   - Profile "supabase" dùng ddl-auto=validate + flyway bật -> Flyway không tạo
--     được 5 bảng này, Hibernate validate fail ngay lúc khởi động:
--     "Schema-validation: missing table [notification]".
-- Nếu DB hiện tại vẫn chạy thì là do ai đó tạo tay trên console -> schema không
-- tái lập được cho CI / máy mới / project mới.
--
-- Dùng IF NOT EXISTS xuyên suốt để chạy an toàn trên DB đã có sẵn bảng tạo tay.
-- Định nghĩa cột bám sát entity (xem swp391.carwash.entity.*) để ddl-auto=validate
-- không báo lệch kiểu.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- notification — mọi chuyển trạng thái booking đều ghi một bản ghi ở đây
-- (createBooking, confirm, reject, cancel, abort, no-show, các scheduler).
-- Thiếu bảng này thì TOÀN BỘ luồng booking đổ.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification (
    notification_id SERIAL PRIMARY KEY,
    user_id INT NOT NULL REFERENCES app_user(user_id),
    booking_id INT REFERENCES booking(booking_id),
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(50) NOT NULL,
    channel VARCHAR(20) NOT NULL DEFAULT 'IN_APP',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_notification_user_created ON notification(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notification_booking ON notification(booking_id);

-- ----------------------------------------------------------------------------
-- promotion — garage_id để cột thường (không FK) đúng như entity đang map
-- (Promotion.garageId là Integer, không phải quan hệ ManyToOne).
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS promotion (
    promotion_id SERIAL PRIMARY KEY,
    garage_id INT NOT NULL REFERENCES garage(garage_id),
    promo_code VARCHAR(50) NOT NULL,
    discount_value DECIMAL(15,2) NOT NULL CHECK (discount_value >= 0),
    discount_type VARCHAR(20) NOT NULL CHECK (discount_type IN ('PERCENTAGE','FIXED_AMOUNT')),
    max_discount DECIMAL(15,2) CHECK (max_discount IS NULL OR max_discount >= 0),
    min_order_value DECIMAL(15,2) NOT NULL DEFAULT 0 CHECK (min_order_value >= 0),
    usage_limit INT CHECK (usage_limit IS NULL OR usage_limit >= 0),
    used_count INT NOT NULL DEFAULT 0 CHECK (used_count >= 0),
    start_date TIMESTAMPTZ NOT NULL,
    end_date TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE','INACTIVE','EXPIRED','DELETED')),
    CHECK (end_date >= start_date)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_promotion_code_garage ON promotion(garage_id, promo_code);
CREATE INDEX IF NOT EXISTS idx_promotion_garage_status ON promotion(garage_id, status);

-- ----------------------------------------------------------------------------
-- promotion_usage — UNIQUE(user_id, promotion_id) là ràng buộc CỐT LÕI:
-- nó vừa chặn khách dùng lại mã, vừa là thứ PromotionReleaseService xoá để
-- "hoàn mã" khi đơn bị huỷ/từ chối/no-show/hoàn tiền. Thiếu unique này thì
-- toàn bộ cơ chế một-mã-một-lần vô hiệu.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS promotion_usage (
    id SERIAL PRIMARY KEY,
    promotion_id INT NOT NULL,
    user_id INT NOT NULL,
    booking_id INT NOT NULL,
    used_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_usage_promotion FOREIGN KEY (promotion_id) REFERENCES promotion(promotion_id),
    CONSTRAINT fk_usage_user FOREIGN KEY (user_id) REFERENCES app_user(user_id),
    CONSTRAINT fk_usage_booking FOREIGN KEY (booking_id) REFERENCES booking(booking_id),
    CONSTRAINT uq_user_promotion UNIQUE (user_id, promotion_id)
);
CREATE INDEX IF NOT EXISTS idx_promotion_usage_booking ON promotion_usage(booking_id);

-- ----------------------------------------------------------------------------
-- reward — catalogue quà đổi điểm.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS reward (
    reward_id SERIAL PRIMARY KEY,
    garage_id INT NOT NULL REFERENCES garage(garage_id),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    points_required INT NOT NULL CHECK (points_required >= 0),
    stock INT NOT NULL CHECK (stock >= 0),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE','INACTIVE','OUT_OF_STOCK','DELETED')),
    discount_type VARCHAR(20) NOT NULL CHECK (discount_type IN ('PERCENTAGE','FIXED_AMOUNT')),
    discount_value DECIMAL(15,2) NOT NULL CHECK (discount_value >= 0),
    max_discount DECIMAL(15,2) CHECK (max_discount IS NULL OR max_discount >= 0),
    min_order_value DECIMAL(15,2) NOT NULL DEFAULT 0 CHECK (min_order_value >= 0),
    valid_days INT NOT NULL CHECK (valid_days > 0)
);
CREATE INDEX IF NOT EXISTS idx_reward_garage_status ON reward(garage_id, status);

-- ----------------------------------------------------------------------------
-- reward_redemption — BookingService.requirePromotionOwnership tra bảng này để
-- biết một promotion có phải voucher cá nhân (đổi từ điểm) hay không. Thiếu bảng
-- thì mọi lần đặt đơn CÓ MÃ đều nổ.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS reward_redemption (
    redemption_id SERIAL PRIMARY KEY,
    account_id INT NOT NULL REFERENCES loyalty_account(account_id),
    garage_id INT NOT NULL REFERENCES garage(garage_id),
    reward_id INT NOT NULL REFERENCES reward(reward_id),
    points_used INT NOT NULL CHECK (points_used >= 0),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','APPROVED','COMPLETED','REJECTED','CANCELLED')),
    redeemed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    approved_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    promotion_id INT REFERENCES promotion(promotion_id),
    booking_id INT REFERENCES booking(booking_id),
    used_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_reward_redemption_account ON reward_redemption(account_id);
CREATE INDEX IF NOT EXISTS idx_reward_redemption_promotion ON reward_redemption(promotion_id);

-- ============================================================================
-- Siết bảo mật giống các bảng khác trong V1: bật RLS và thu hồi quyền của
-- anon/authenticated (backend đi bằng service role, không qua Data API).
-- ============================================================================
ALTER TABLE notification ENABLE ROW LEVEL SECURITY;
ALTER TABLE promotion ENABLE ROW LEVEL SECURITY;
ALTER TABLE promotion_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE reward ENABLE ROW LEVEL SECURITY;
ALTER TABLE reward_redemption ENABLE ROW LEVEL SECURITY;

DO $$
DECLARE
    t TEXT;
    r TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['notification','promotion','promotion_usage','reward','reward_redemption'] LOOP
        EXECUTE format('REVOKE ALL PRIVILEGES ON TABLE public.%I FROM PUBLIC', t);
        FOREACH r IN ARRAY ARRAY['anon','authenticated'] LOOP
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
                EXECUTE format('REVOKE ALL PRIVILEGES ON TABLE public.%I FROM %I', t, r);
            END IF;
        END LOOP;
    END LOOP;
END
$$;
