package swp391.carwash.repository;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import swp391.carwash.entity.RewardRedemption;

public interface RewardRedemptionRepository extends JpaRepository<RewardRedemption,Integer> {


    // Customer tự xem lịch sử đổi quà cá nhân tại một Garage cụ thể (quét qua quan hệ nested: loyaltyAccount -> appUser -> userId)
    Page<RewardRedemption> findByLoyaltyAccount_User_IdAndGarage_Id(Integer userId, Integer garageId, Pageable pageable);
    @EntityGraph(attributePaths = {"loyaltyAccount", "loyaltyAccount.user", "garage", "reward"})
    @Query("""
            select redemption from RewardRedemption redemption
            where redemption.redeemedAt >= :fromTime
              and redemption.redeemedAt < :toTime
              and (:garageId is null or redemption.garage.id = :garageId)
            """)
    List<RewardRedemption> findForInsightPeriod(
            @Param("fromTime") OffsetDateTime fromTime,
            @Param("toTime") OffsetDateTime toTime,
            @Param("garageId") Integer garageId);

    Page<RewardRedemption>
    findByLoyaltyAccountUserIdAndGarageIdOrderByRedeemedAtDesc(
            Integer userId,
            Integer garageId,
            Pageable pageable
    );

    Page<RewardRedemption>
    findByGarageIdOrderByRedeemedAtDesc(
            Integer garageId,
            Pageable pageable
    );

    Page<RewardRedemption>
    findByGarageIdAndStatusOrderByRedeemedAtDesc(
            Integer garageId,
            String status,
            Pageable pageable
    );

    /**
     * Voucher cá nhân (sinh ra từ đổi điểm) có thuộc về user này hay không.
     *
     * <p>Dùng khi ÁP DỤNG promotion để chặn việc khách khác đoán promotionId rồi dùng voucher
     * mà người ta vừa tiêu điểm để đổi. Trước đây ràng buộc này chỉ có trong query LIỆT KÊ
     * ({@code PromotionRepository.findAvailablePromotions}) mà không có ở đường ghi.
     */
    boolean existsByPromotion_PromotionIdAndLoyaltyAccount_User_IdAndStatus(
            Integer promotionId,
            Integer userId,
            String status
    );

    /** Voucher này có phải voucher cá nhân từ đổi điểm (của bất kỳ ai) hay không. */
    boolean existsByPromotion_PromotionId(Integer promotionId);

}
