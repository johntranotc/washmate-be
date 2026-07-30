package swp391.carwash.service;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swp391.carwash.entity.Booking;
import swp391.carwash.entity.LoyaltyAccount;
import swp391.carwash.entity.LoyaltyPolicy;
import swp391.carwash.entity.LoyaltyTransaction;
import swp391.carwash.enums.BookingStatus;
import swp391.carwash.enums.RecordStatus;
import swp391.carwash.enums.TransactionType;
import swp391.carwash.repository.BookingRepository;
import swp391.carwash.repository.LoyaltyAccountRepository;
import swp391.carwash.repository.LoyaltyPolicyRepository;
import swp391.carwash.repository.LoyaltyTransactionRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyPointExpiryServiceImpl implements LoyaltyPointExpiryService {

    private final LoyaltyAccountRepository accountRepository;
    private final LoyaltyPolicyRepository policyRepository;
    private final BookingRepository bookingRepository;
    private final LoyaltyTransactionRepository transactionRepository;

    @Override
    @Transactional
    public void expirePoints() {
        List<LoyaltyAccount> accounts =
                accountRepository.findByStatusAndAvailablePointsGreaterThan(
                        RecordStatus.ACTIVE,
                        0
                );

        OffsetDateTime now = OffsetDateTime.now();

        for (LoyaltyAccount account : accounts) {
            expireAccount(account, now);
        }
    }

    private void expireAccount(LoyaltyAccount account, OffsetDateTime now) {
        Integer garageId = account.getGarage().getId();
        Integer userId = account.getUser().getId();

        LoyaltyPolicy policy = policyRepository
                .findByGarageIdAndStatus(garageId, RecordStatus.ACTIVE)
                .orElse(null);

        if (policy == null) {
            return;
        }

        Booking lastCompletedBooking = bookingRepository
                .findFirstByUserIdAndGarageIdAndStatusOrderByCompletedTimeDesc(
                        userId,
                        garageId,
                        BookingStatus.COMPLETED
                )
                .orElse(null);

        if (lastCompletedBooking == null
                || lastCompletedBooking.getCompletedTime() == null) {
            return;
        }

        OffsetDateTime expiryDate = lastCompletedBooking
                .getCompletedTime()
                .plusMonths(policy.getPointExpiryMonths());

        if (expiryDate.isAfter(now)) {
            return;
        }

        int expiredPoints = account.getAvailablePoints();

        account.setAvailablePoints(0);
        account.setUpdatedAt(now);
        accountRepository.save(account);

        LoyaltyTransaction transaction = LoyaltyTransaction.builder()
                .account(account)
                .points(-expiredPoints)
                .transactionType(TransactionType.EXPIRE)
                .description(expiredPoints
                        + " points expired because customer did not use service for "
                        + policy.getPointExpiryMonths()
                        + " months")
                .earnedAt(now)
                .expiresAt(now)
                .createdAt(now)
                .expired(true)
                .build();

        transactionRepository.save(transaction);

        log.info("Expired {} loyalty points of account {}", expiredPoints, account.getId());
    }
}
