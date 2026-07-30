package swp391.carwash.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import swp391.carwash.entity.PromotionUsage;

public interface PromotionUsageRepository extends JpaRepository<PromotionUsage, Integer> {

    boolean existsByUser_IdAndPromotion_PromotionId(Integer userId, Integer promotionId);

    /**
     * Các bản ghi sử dụng mã gắn với một booking — dùng để HOÀN mã khi booking bị huỷ/hoàn tiền
     * (xem {@code PromotionReleaseService}). Trả List thay vì Optional để phòng dữ liệu lệch
     * có nhiều dòng cho cùng booking.
     */
    List<PromotionUsage> findByBooking_Id(Integer bookingId);

}
