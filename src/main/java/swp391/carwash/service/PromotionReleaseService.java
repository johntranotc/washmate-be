package swp391.carwash.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swp391.carwash.entity.Promotion;
import swp391.carwash.entity.PromotionUsage;
import swp391.carwash.repository.PromotionRepository;
import swp391.carwash.repository.PromotionUsageRepository;

/**
 * Hoàn lại mã khuyến mãi khi một booking không đi tới đích (huỷ / từ chối / no-show /
 * hoàn tiền / hết hạn thanh toán).
 *
 * <p>Trước khi có class này, {@code PromotionUsage} chỉ được INSERT và {@code usedCount} chỉ
 * được tăng, không có đường hoàn. Hậu quả:
 * <ul>
 *   <li>Khách huỷ đơn là mất mã vĩnh viễn — ràng buộc UNIQUE(user_id, promotion_id) chặn
 *       không cho dùng lại, dù khách chưa hưởng lợi gì.</li>
 *   <li>Quota toàn cục {@code usageLimit} bị đốt bởi đơn rác → có thể tạo–huỷ đơn liên tục
 *       để làm cạn một campaign voucher.</li>
 * </ul>
 *
 * <p>Được gọi từ nhiều flow khác nhau nên phải IDEMPOTENT: gọi 2 lần cho cùng booking chỉ
 * hoàn 1 lần. Và giống {@code LoyaltyService}, method này chạy trong transaction của caller
 * (BookingService/PaymentService/scheduler) nên KHÔNG được ném exception — một RuntimeException
 * sẽ đánh dấu transaction rollback-only và làm hỏng chính việc huỷ/hoàn tiền.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PromotionReleaseService {

    private final PromotionUsageRepository promotionUsageRepository;
    private final PromotionRepository promotionRepository;

    /**
     * Nhả mã khuyến mãi mà booking này đang giữ (nếu có).
     *
     * @param bookingId booking vừa chuyển sang trạng thái không thành công
     */
    @Transactional
    public void releaseForBooking(Integer bookingId) {
        if (bookingId == null) {
            return;
        }

        try {
            List<PromotionUsage> usages = promotionUsageRepository.findByBooking_Id(bookingId);
            if (usages.isEmpty()) {
                // Đơn không dùng mã, hoặc đã được hoàn trước đó -> idempotent, không phải lỗi.
                return;
            }

            for (PromotionUsage usage : usages) {
                Integer promotionId = usage.getPromotion() == null
                        ? null
                        : usage.getPromotion().getPromotionId();

                // Xoá bản ghi sử dụng TRƯỚC: đây là thứ chặn khách dùng lại mã
                // (UNIQUE user_id + promotion_id).
                promotionUsageRepository.delete(usage);

                if (promotionId == null) {
                    continue;
                }

                // Khoá bi quan dòng promotion để usedCount-- không đua với các booking
                // đang tăng usedCount cùng lúc (cùng "ổ khoá" với BookingService.createBooking).
                Promotion promotion = promotionRepository.findByIdForUpdate(promotionId).orElse(null);
                if (promotion == null) {
                    continue;
                }

                int current = promotion.getUsedCount() == null ? 0 : promotion.getUsedCount();
                // Không cho âm: dữ liệu cũ có thể đã lệch, và cột là NOT NULL >= 0.
                promotion.setUsedCount(Math.max(current - 1, 0));
                promotionRepository.save(promotion);

                log.info("Đã hoàn mã khuyến mãi {} cho booking {} (usedCount {} -> {})",
                        promotionId, bookingId, current, promotion.getUsedCount());
            }
        } catch (RuntimeException ex) {
            // Hoàn mã là nghiệp vụ phụ: không được làm thất bại việc huỷ đơn / hoàn tiền.
            // Log ở mức ERROR để còn đối soát thủ công được.
            log.error("Không hoàn được mã khuyến mãi cho booking {} - cần đối soát thủ công",
                    bookingId, ex);
        }
    }
}
