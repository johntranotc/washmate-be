package swp391.carwash.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swp391.carwash.entity.LoyaltyAccount;
import swp391.carwash.entity.LoyaltyTierHistory;
import swp391.carwash.entity.MembershipTier;
import swp391.carwash.entity.Notification;
import swp391.carwash.enums.RecordStatus;
import swp391.carwash.enums.TierChangeType;
import swp391.carwash.enums.TransactionType;
import swp391.carwash.repository.*;
import swp391.carwash.service.Loyalty.Support.QuarterCalculator;
import swp391.carwash.service.Loyalty.Support.QuarterPeriod;

import java.time.LocalDate;
import swp391.carwash.common.TimeZones;
import java.time.OffsetDateTime;


@Service
@RequiredArgsConstructor
public class LoyaltyTierEvaluationServiceImpl implements LoyaltyTierEvaluationService {

    private static final String LOYALTY_UPDATE = "LOYALTY_UPDATE";
    private static final String IN_APP = "IN_APP";
    private static final String PENDING = "PENDING";

    private final LoyaltyAccountRepository loyaltyAccountRepository;
    private final MembershipTierRepository membershipTierRepository;
    private final LoyaltyTransactionRepository loyaltyTransactionRepository;
    private final LoyaltyTierHistoryRepository loyaltyTierHistoryRepository;
    private final NotificationRepository notificationRepository;
    private final QuarterCalculator quarterCalculator;

    @Override
    @Transactional
    public void evaluateQuarterlyTier() {
        QuarterPeriod period = quarterCalculator.previousQuarter();

        loyaltyAccountRepository.findByStatus(RecordStatus.ACTIVE)
                .forEach(account -> evaluate(account, period));
    }

    @Override
    @Transactional
    public void notifyMaintainWarning() {
        if (!isWarningPeriod()) {
            return;
        }

        QuarterPeriod period = quarterCalculator.currentQuarter();

        loyaltyAccountRepository.findByStatus(RecordStatus.ACTIVE)
                .forEach(account -> processMaintainWarning(account, period));
    }

    private void evaluate(LoyaltyAccount account, QuarterPeriod period) {

        if (shouldMaintain(account, period)) {
            return;
        }

        MembershipTier lowerTier = findPreviousTier(account.getTier());

        if (lowerTier == null) {
            return;
        }

        downgrade(account, lowerTier);
    }
    private boolean shouldMaintain(LoyaltyAccount account, QuarterPeriod period) {
        Integer earnedPoint = loyaltyTransactionRepository.sumEarnPoint(
                account.getId(),
                TransactionType.EARN,
                period.start(),
                period.end()
        );

        return earnedPoint >= account.getTier().getMaintainPoints();
    }

    private MembershipTier findPreviousTier(MembershipTier currentTier) {
        return membershipTierRepository
                .findFirstByGarageIdAndStatusAndMinPointsLessThanOrderByMinPointsDesc(
                        currentTier.getGarage().getId(),
                        RecordStatus.ACTIVE,
                        currentTier.getMinPoints())
                .orElse(null);
    }

    private void downgrade(
            LoyaltyAccount account,
            MembershipTier newTier) {

        MembershipTier oldTier = account.getTier();

        account.setTier(newTier);
        account.setUpdatedAt(OffsetDateTime.now());

        loyaltyAccountRepository.save(account);

        saveHistory(
                account,
                oldTier,
                newTier,
                TierChangeType.DOWNGRADE,
                "Không đủ điểm duy trì hạng sau kỳ đánh giá"
        );
    }

    private void processMaintainWarning(LoyaltyAccount account, QuarterPeriod period) {
        if (shouldMaintain(account, period)) {
            return;
        }

        if (alreadyWarned(account, period.start())) {
            return;
        }

        createMaintainWarningNotification(account, period);
    }

    private boolean alreadyWarned(LoyaltyAccount account, OffsetDateTime warningStart) {
        return notificationRepository.existsByUserIdAndTypeAndCreatedAtAfter(
                account.getUser().getId(),
                LOYALTY_UPDATE,
                warningStart
        );
    }

    private void createMaintainWarningNotification(
            LoyaltyAccount account,
            QuarterPeriod period) {

        Integer earnedPoint = loyaltyTransactionRepository.sumEarnPoint(
                account.getId(),
                TransactionType.EARN,
                period.start(),
                period.end()
        );

        int missingPoints = account.getTier().getMaintainPoints() - earnedPoint;

        Notification notification = Notification.builder()
                .userId(account.getUser().getId())
                .title("Cảnh báo duy trì hạng thành viên")
                .content("Bạn còn thiếu "
                        + missingPoints
                        + " điểm để duy trì hạng "
                        + account.getTier().getTierName()
                        + ". Vui lòng tích đủ điểm trước kỳ đánh giá.")
                .type(LOYALTY_UPDATE)
                .channel(IN_APP)
                .status(PENDING)
                .isRead(false)
                .createdAt(OffsetDateTime.now())
                .build();

        notificationRepository.save(notification);
    }

    private boolean isWarningPeriod() {
        LocalDate today = LocalDate.now(TimeZones.VIETNAM);

        int month = today.getMonthValue();
        int day = today.getDayOfMonth();

        return (month == 3 && day >= 17)
                || (month == 6 && day >= 16)
                || (month == 9 && day >= 16)
                || (month == 12 && day >= 17);
    }
    private void saveHistory(
            LoyaltyAccount account,
            MembershipTier oldTier,
            MembershipTier newTier,
            TierChangeType changeType,
            String reason) {

        LoyaltyTierHistory history = LoyaltyTierHistory.builder()
                .account(account)
                .garage(account.getGarage())
                .oldTier(oldTier)
                .newTier(newTier)
                .changeType(changeType)
                .changeReason(reason)
                .createdAt(OffsetDateTime.now())
                .build();

        loyaltyTierHistoryRepository.save(history);
    }
}
