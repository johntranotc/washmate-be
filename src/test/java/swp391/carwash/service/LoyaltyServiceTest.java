package swp391.carwash.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import swp391.carwash.entity.AppUser;
import swp391.carwash.entity.Booking;
import swp391.carwash.entity.Garage;
import swp391.carwash.entity.LoyaltyAccount;
import swp391.carwash.entity.LoyaltyPolicy;
import swp391.carwash.entity.LoyaltyTransaction;
import swp391.carwash.entity.MembershipTier;
import swp391.carwash.enums.RecordStatus;
import swp391.carwash.enums.TransactionType;
import swp391.carwash.repository.LoyaltyAccountRepository;
import swp391.carwash.repository.LoyaltyPolicyRepository;
import swp391.carwash.repository.LoyaltyTierHistoryRepository;
import swp391.carwash.repository.LoyaltyTransactionRepository;
import swp391.carwash.repository.MembershipTierRepository;

@ExtendWith(MockitoExtension.class)
class LoyaltyServiceTest {
    @Mock
    private LoyaltyAccountRepository loyaltyAccountRepository;
    @Mock
    private LoyaltyTransactionRepository loyaltyTransactionRepository;
    @Mock
    private MembershipTierRepository membershipTierRepository;
    @Mock
    private LoyaltyPolicyRepository loyaltyPolicyRepository;
    @Mock
    private LoyaltyTierHistoryRepository loyaltyTierHistoryRepository;

    @InjectMocks
    private LoyaltyService loyaltyService;

    private Booking booking;
    private Garage garage;
    private AppUser customer;
    private LoyaltyAccount account;
    private MembershipTier tier;
    private LoyaltyPolicy policy;

    @BeforeEach
    void setUp() {
        garage = Garage.builder().id(1).build();
        customer = AppUser.builder().id(10).build();

        tier = MembershipTier.builder().id(1).garage(garage).minPoints(0).status(RecordStatus.ACTIVE).build();

        // 1 điểm = 10.000đ → 50.000đ = 5 điểm
        policy = LoyaltyPolicy.builder()
                .amountPerPoint(new BigDecimal("10000"))
                .autoEnroll(true)
                .status(RecordStatus.ACTIVE)
                .build();

        booking = Booking.builder()
                .id(100)
                .user(customer)
                .garage(garage)
                .finalAmount(new BigDecimal("50000.00"))
                .build();

        account = LoyaltyAccount.builder()
                .id(1)
                .user(customer)
                .garage(garage)
                .tier(tier)
                .totalPoints(10)
                .availablePoints(10)
                .build();
    }

    @Test
    void accruePointsCalculatesAndSavesProperly() {
        when(loyaltyTransactionRepository.existsByBookingIdAndTransactionType(100, TransactionType.EARN)).thenReturn(false);
        when(loyaltyPolicyRepository.findByGarageIdAndStatus(1, RecordStatus.ACTIVE)).thenReturn(Optional.of(policy));
        when(loyaltyAccountRepository.findByUserIdAndGarageId(10, 1)).thenReturn(Optional.of(account));
        when(membershipTierRepository.findFirstByGarageIdAndStatusAndMinPointsLessThanEqualOrderByMinPointsDesc(1, RecordStatus.ACTIVE, 15))
                .thenReturn(Optional.of(tier));

        loyaltyService.accruePoints(booking);

        assertEquals(15, account.getTotalPoints());
        assertEquals(15, account.getAvailablePoints());
        verify(loyaltyTransactionRepository).save(any(LoyaltyTransaction.class));
    }

    // Các test dưới đây khẳng định: tích điểm là nghiệp vụ PHỤ nên khi không áp dụng được
    // thì BỎ QUA im lặng, KHÔNG ném exception. Nếu ném, transaction của
    // BookingService.complete() sẽ bị đánh dấu rollback-only và làm hỏng việc hoàn tất đơn.

    @Test
    void accruePointsSkipsWhenAlreadyEarned() {
        when(loyaltyTransactionRepository.existsByBookingIdAndTransactionType(100, TransactionType.EARN)).thenReturn(true);

        assertDoesNotThrow(() -> loyaltyService.accruePoints(booking));

        verify(loyaltyAccountRepository, never()).save(any());
        verify(loyaltyTransactionRepository, never()).save(any());
    }

    @Test
    void accruePointsSkipsForZeroAmount() {
        booking.setFinalAmount(BigDecimal.ZERO);
        when(loyaltyTransactionRepository.existsByBookingIdAndTransactionType(100, TransactionType.EARN)).thenReturn(false);

        assertDoesNotThrow(() -> loyaltyService.accruePoints(booking));

        verify(loyaltyAccountRepository, never()).save(any());
        verify(loyaltyTransactionRepository, never()).save(any());
    }

    /** Garage chưa bật loyalty (không có LoyaltyPolicy ACTIVE) vẫn phải complete được đơn. */
    @Test
    void accruePointsSkipsWhenGarageHasNoActivePolicy() {
        when(loyaltyTransactionRepository.existsByBookingIdAndTransactionType(100, TransactionType.EARN)).thenReturn(false);
        when(loyaltyPolicyRepository.findByGarageIdAndStatus(1, RecordStatus.ACTIVE)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> loyaltyService.accruePoints(booking));

        verify(loyaltyAccountRepository, never()).save(any());
        verify(loyaltyTransactionRepository, never()).save(any());
    }

    @Test
    void accruePointsSkipsWhenGarageHasNoActiveTier() {
        when(loyaltyTransactionRepository.existsByBookingIdAndTransactionType(100, TransactionType.EARN)).thenReturn(false);
        when(loyaltyPolicyRepository.findByGarageIdAndStatus(1, RecordStatus.ACTIVE)).thenReturn(Optional.of(policy));
        when(loyaltyAccountRepository.findByUserIdAndGarageId(10, 1)).thenReturn(Optional.empty());
        when(membershipTierRepository.findFirstByGarageIdAndStatusOrderByMinPointsAsc(1, RecordStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertDoesNotThrow(() -> loyaltyService.accruePoints(booking));

        verify(loyaltyAccountRepository, never()).save(any());
        verify(loyaltyTransactionRepository, never()).save(any());
    }

    @Test
    void rollbackEarnedPointsReducesPointsCorrectly() {
        LoyaltyTransaction earned = LoyaltyTransaction.builder()
                .id(99)
                .account(account)
                .points(5)
                .build();

        when(loyaltyTransactionRepository.findByBookingIdAndTransactionType(100, TransactionType.EARN)).thenReturn(Optional.of(earned));
        when(loyaltyTransactionRepository.existsBySourceTransactionIdAndTransactionType(99, TransactionType.ROLLBACK)).thenReturn(false);

        loyaltyService.rollbackEarnedPointsForBooking(booking);

        assertEquals(5, account.getTotalPoints());
        assertEquals(5, account.getAvailablePoints());
        verify(loyaltyTransactionRepository).save(any(LoyaltyTransaction.class));
    }

    /**
     * Case làm hoàn tiền chết trước đây: hoàn tiền cho đơn đã PAID nhưng chưa COMPLETED
     * (nên chưa từng tích điểm). Không có gì để thu hồi -> phải no-op, không được ném.
     */
    @Test
    void rollbackEarnedPointsIsNoOpWhenBookingNeverEarned() {
        when(loyaltyTransactionRepository.findByBookingIdAndTransactionType(100, TransactionType.EARN))
                .thenReturn(Optional.empty());

        assertDoesNotThrow(() -> loyaltyService.rollbackEarnedPointsForBooking(booking));

        verify(loyaltyAccountRepository, never()).save(any());
        verify(loyaltyTransactionRepository, never()).save(any());
    }

    /** Thu hồi 2 lần phải idempotent, không được làm hỏng transaction hoàn tiền. */
    @Test
    void rollbackEarnedPointsIsIdempotentWhenAlreadyRolledBack() {
        LoyaltyTransaction earned = LoyaltyTransaction.builder()
                .id(99)
                .account(account)
                .points(5)
                .build();

        when(loyaltyTransactionRepository.findByBookingIdAndTransactionType(100, TransactionType.EARN))
                .thenReturn(Optional.of(earned));
        when(loyaltyTransactionRepository.existsBySourceTransactionIdAndTransactionType(99, TransactionType.ROLLBACK))
                .thenReturn(true);

        assertDoesNotThrow(() -> loyaltyService.rollbackEarnedPointsForBooking(booking));

        assertEquals(10, account.getTotalPoints());
        verify(loyaltyAccountRepository, never()).save(any());
        verify(loyaltyTransactionRepository, never()).save(any());
    }

    @Test
    void rollbackEarnedPointsDoesNotGoBelowZero() {
        account.setTotalPoints(2);
        account.setAvailablePoints(2);
        LoyaltyTransaction earned = LoyaltyTransaction.builder()
                .id(99)
                .account(account)
                .points(5) // đã tích 5 nhưng số dư hiện tại chỉ còn 2 (có thể đã tiêu bớt)
                .build();

        when(loyaltyTransactionRepository.findByBookingIdAndTransactionType(100, TransactionType.EARN)).thenReturn(Optional.of(earned));
        when(loyaltyTransactionRepository.existsBySourceTransactionIdAndTransactionType(99, TransactionType.ROLLBACK)).thenReturn(false);

        loyaltyService.rollbackEarnedPointsForBooking(booking);

        assertEquals(0, account.getTotalPoints());
        assertEquals(0, account.getAvailablePoints());
    }
}
