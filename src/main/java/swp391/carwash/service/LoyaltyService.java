package swp391.carwash.service;

import java.math.RoundingMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swp391.carwash.dto.LoyaltyAccountResponse;
import swp391.carwash.dto.LoyaltyTransactionResponse;
import swp391.carwash.entity.*;
import swp391.carwash.enums.TierChangeType;
import swp391.carwash.enums.TransactionType;
import swp391.carwash.repository.*;
import swp391.carwash.security.AppUserDetails;
import swp391.carwash.enums.RecordStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class LoyaltyService {
    private final LoyaltyAccountRepository loyaltyAccountRepository;
    private final LoyaltyTransactionRepository loyaltyTransactionRepository;
    private final MembershipTierRepository membershipTierRepository;
    private final LoyaltyPolicyRepository loyaltyPolicyRepository;
    private final LoyaltyTierHistoryRepository loyaltyTierHistoryRepository;

    @Transactional(readOnly = true)
    public List<LoyaltyTransactionResponse> getMyTransactions(AppUserDetails principal) {
        return loyaltyTransactionRepository.findByAccountUserIdOrderByCreatedAtDesc(principal.getId()).stream()
                .map(LoyaltyTransactionResponse::from)
                .toList();
    }

    /**
     * Tích điểm cho booking đã hoàn tất.
     *
     * <p>QUAN TRỌNG: đây là nghiệp vụ PHỤ, được gọi từ trong transaction của
     * {@code BookingService.complete()}. Method này KHÔNG được ném exception khi loyalty
     * chưa được cấu hình / không áp dụng — vì nó chạy cùng transaction với caller nên một
     * RuntimeException sẽ đánh dấu transaction là rollback-only và làm HỎNG việc hoàn tất
     * đơn (try/catch ở caller cũng không cứu được). Mọi trường hợp "không tích được điểm"
     * đều bỏ qua kèm log, chỉ để lỗi hạ tầng (DB) nổi lên.
     */
    @Transactional
    public void accruePoints(Booking booking) {
        if (!canAccrue(booking)) {
            return;
        }

        LoyaltyPolicy policy = findActivePolicy(booking.getGarage().getId());
        if (policy == null) {
            log.info("Bỏ qua tích điểm: garage {} chưa có LoyaltyPolicy ACTIVE (bookingId={})",
                    booking.getGarage().getId(), booking.getId());
            return;
        }

        int point = calculatePoints(booking, policy);
        if (point <= 0) {
            return;
        }

        LoyaltyAccount account = resolveAccount(booking, policy);
        if (account == null) {
            return;
        }

        updateAccount(account, point);
        saveEarnTransaction(account, booking, point);
        evaluateUpgrade(account);
    }

    private void evaluateUpgrade(LoyaltyAccount account) {

        MembershipTier currentTier = account.getTier();

        MembershipTier highestTier =
                membershipTierRepository
                        .findFirstByGarageIdAndStatusAndMinPointsLessThanEqualOrderByMinPointsDesc(
                                account.getGarage().getId(),
                                RecordStatus.ACTIVE,
                                account.getTotalPoints())
                        .orElse(currentTier);

        // Không có hạng nào phù hợp, hoặc account chưa gắn hạng -> không nâng (tránh NPE
        // trên đường complete() booking).
        if (highestTier == null || currentTier == null) {
            return;
        }

        if (highestTier.getMinPoints() <= currentTier.getMinPoints()) {
            return;
        }

        account.setTier(highestTier);
        account.setUpdatedAt(OffsetDateTime.now());

        loyaltyAccountRepository.save(account);

        saveUpgradeHistory(account, currentTier, highestTier);
    }
    private void saveUpgradeHistory(
            LoyaltyAccount account,
            MembershipTier oldTier,
            MembershipTier newTier) {

        LoyaltyTierHistory history =
                LoyaltyTierHistory.builder()
                        .account(account)
                        .garage(account.getGarage())
                        .oldTier(oldTier)
                        .newTier(newTier)
                        .changeType(TierChangeType.UPGRADE)
                        .changeReason("Tự động nâng hạng khi đủ điểm")
                        .createdAt(OffsetDateTime.now())
                        .build();

        loyaltyTierHistoryRepository.save(history);
    }


    /**
     * Thu hồi điểm đã tích của một booking khi hoàn tiền.
     *
     * <p>Cùng lý do như {@link #accruePoints}: method này chạy trong transaction của
     * {@code PaymentService.refundPayment()}, nên KHÔNG được ném exception khi không có gì
     * để thu hồi. Trước đây nó ném khi booking chưa từng tích điểm (đơn đã trả tiền nhưng
     * chưa COMPLETED) → làm mọi lần hoàn tiền cho đơn đó thất bại, mà đó lại là đường DUY
     * NHẤT để huỷ đơn đã thanh toán.
     */
    @Transactional
    public void rollbackEarnedPointsForBooking(Booking booking) {
        LoyaltyTransaction earned = loyaltyTransactionRepository
                .findByBookingIdAndTransactionType(booking.getId(), TransactionType.EARN)
                .orElse(null);
        if (earned == null) {
            // Đơn chưa từng tích điểm (vd hoàn tiền đơn đã trả nhưng chưa COMPLETED)
            // -> không có gì để thu hồi, đây là trường hợp bình thường.
            log.debug("Không có điểm để thu hồi cho booking {}", booking.getId());
            return;
        }

        boolean alreadyRolledBack = loyaltyTransactionRepository
                .existsBySourceTransactionIdAndTransactionType(
                        earned.getId(),
                        TransactionType.ROLLBACK);
        if (alreadyRolledBack) {
            // Idempotent: đã thu hồi rồi thì bỏ qua thay vì làm hỏng transaction hoàn tiền.
            log.info("Điểm của booking {} đã được thu hồi trước đó, bỏ qua", booking.getId());
            return;
        }

        LoyaltyAccount account = earned.getAccount();
        rollbackAccount(account, earned);
        saveRollbackTransaction(account, booking, earned);
    }

    private void saveRollbackTransaction(
            LoyaltyAccount account,
            Booking booking,
            LoyaltyTransaction earned) {
        int rollbackPoints = Math.abs(earned.getPoints());
        OffsetDateTime now = OffsetDateTime.now();
        LoyaltyTransaction rollback =
                LoyaltyTransaction.builder()
                        .account(account)
                        .booking(booking)
                        .sourceTransaction(earned)
                        .points(-rollbackPoints)
                        .transactionType(TransactionType.ROLLBACK)
                        .description("Rollback earned points after payment refund")
                        .earnedAt(now)
                        .createdAt(now)
                        .build();

        loyaltyTransactionRepository.save(rollback);

    }

    private void rollbackAccount(
            LoyaltyAccount account,
            LoyaltyTransaction earned) {

        int rollbackPoints = Math.abs(earned.getPoints());

        account.setAvailablePoints(
                Math.max(
                        account.getAvailablePoints() - rollbackPoints,
                        0));

        account.setTotalPoints(
                Math.max(
                        account.getTotalPoints() - rollbackPoints,
                        0));

        account.setUpdatedAt(OffsetDateTime.now());

        loyaltyAccountRepository.save(account);

    }

    /** Kiểm tra booking có đủ điều kiện tích điểm hay không (không ném exception). */
    private boolean canAccrue(Booking booking) {

        if (loyaltyTransactionRepository.existsByBookingIdAndTransactionType(
                booking.getId(),
                TransactionType.EARN)) {
            // Idempotent: đã tích điểm rồi thì bỏ qua, không coi là lỗi.
            log.debug("Bỏ qua tích điểm: booking {} đã được tích điểm trước đó", booking.getId());
            return false;
        }

        if (booking.getFinalAmount() == null
                || booking.getFinalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            // Đơn 0đ (giảm 100%) là hợp lệ về nghiệp vụ, chỉ là không có gì để tích điểm.
            log.debug("Bỏ qua tích điểm: booking {} có finalAmount <= 0", booking.getId());
            return false;
        }

        return true;
    }
    private int calculatePoints(
            Booking booking,
            LoyaltyPolicy policy)
    {
        BigDecimal amountPerPoint = policy.getAmountPerPoint();

        // DB đã có CHECK (amount_per_point > 0), nhưng đây là đường complete() booking nên
        // chặn chia-cho-0 tại đây thay vì để ArithmeticException phá transaction.
        if (amountPerPoint == null || amountPerPoint.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Bỏ qua tích điểm: policy {} có amountPerPoint không hợp lệ ({})",
                    policy.getId(), amountPerPoint);
            return 0;
        }

        return booking.getFinalAmount()
                .divide(amountPerPoint, RoundingMode.DOWN)
                .intValue();
    }
    /** Trả về account để tích điểm, hoặc null nếu không thể (đã log lý do). */
    private LoyaltyAccount resolveAccount(
            Booking booking,
            LoyaltyPolicy policy) {

        LoyaltyAccount existing = loyaltyAccountRepository
                .findByUserIdAndGarageId(
                        booking.getUser().getId(),
                        booking.getGarage().getId())
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        if (!Boolean.TRUE.equals(policy.getAutoEnroll())) {
            log.info("Bỏ qua tích điểm: user {} chưa tham gia loyalty tại garage {} và autoEnroll đang tắt",
                    booking.getUser().getId(), booking.getGarage().getId());
            return null;
        }

        return createAccount(booking);
    }

    /** Trả về account mới, hoặc null nếu garage chưa có hạng mặc định (đã log lý do). */
    private LoyaltyAccount createAccount(Booking booking) {

        MembershipTier defaultTier = membershipTierRepository
                .findFirstByGarageIdAndStatusOrderByMinPointsAsc(
                        booking.getGarage().getId(),
                        RecordStatus.ACTIVE)
                .orElse(null);
        if (defaultTier == null) {
            log.warn("Bỏ qua tích điểm: garage {} chưa có MembershipTier ACTIVE nào",
                    booking.getGarage().getId());
            return null;
        }

        LoyaltyAccount account = LoyaltyAccount.builder()
                .user(booking.getUser())
                .garage(booking.getGarage())
                .tier(defaultTier)
                .totalPoints(0)
                .availablePoints(0)
                .status(RecordStatus.ACTIVE)
                .createdAt(OffsetDateTime.now())
                .build();

        return loyaltyAccountRepository.save(account);
    }
    private void updateAccount(LoyaltyAccount account, int pointsToAdd) {

        account.setTotalPoints(account.getTotalPoints() + pointsToAdd);

        account.setAvailablePoints(account.getAvailablePoints() + pointsToAdd);

        account.setUpdatedAt(OffsetDateTime.now());

        loyaltyAccountRepository.save(account);
    }
    private void saveEarnTransaction(
            LoyaltyAccount account,
            Booking booking,
            int pointsToAdd) {

        LoyaltyTransaction transaction = LoyaltyTransaction.builder()
                .account(account)
                .booking(booking)
                .points(pointsToAdd)
                .transactionType(TransactionType.EARN)
                .description("Earned from booking " + booking.getBookingCode())
                .earnedAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .build();

        loyaltyTransactionRepository.save(transaction);
    }
    /** Trả về policy ACTIVE của garage, hoặc null nếu garage chưa bật loyalty. */
    private LoyaltyPolicy findActivePolicy(Integer garageId) {

        return loyaltyPolicyRepository
                .findByGarageIdAndStatus(
                        garageId,
                        RecordStatus.ACTIVE)
                .orElse(null);
    }

}
