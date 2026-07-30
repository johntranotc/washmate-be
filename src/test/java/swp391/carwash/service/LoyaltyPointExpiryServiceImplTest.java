package swp391.carwash.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import swp391.carwash.entity.AppUser;
import swp391.carwash.entity.Booking;
import swp391.carwash.entity.Garage;
import swp391.carwash.entity.LoyaltyAccount;
import swp391.carwash.entity.LoyaltyPolicy;
import swp391.carwash.entity.LoyaltyTransaction;
import swp391.carwash.entity.MembershipTier;
import swp391.carwash.enums.BookingStatus;
import swp391.carwash.enums.RecordStatus;
import swp391.carwash.enums.TransactionType;
import swp391.carwash.repository.BookingRepository;
import swp391.carwash.repository.LoyaltyAccountRepository;
import swp391.carwash.repository.LoyaltyPolicyRepository;
import swp391.carwash.repository.LoyaltyTransactionRepository;

@ExtendWith(MockitoExtension.class)
class LoyaltyPointExpiryServiceImplTest {

    @Mock
    private LoyaltyAccountRepository accountRepository;
    @Mock
    private LoyaltyPolicyRepository policyRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private LoyaltyTransactionRepository transactionRepository;

    private LoyaltyPointExpiryServiceImpl service;
    private AppUser user;
    private Garage garage;
    private MembershipTier tier;
    private LoyaltyAccount account;
    private LoyaltyPolicy policy;

    @BeforeEach
    void setUp() {
        service = new LoyaltyPointExpiryServiceImpl(
                accountRepository,
                policyRepository,
                bookingRepository,
                transactionRepository
        );

        user = AppUser.builder().id(10).build();
        garage = Garage.builder().id(1).name("Garage 1").build();
        tier = MembershipTier.builder().id(1).garage(garage).build();

        account = LoyaltyAccount.builder()
                .id(100)
                .user(user)
                .garage(garage)
                .tier(tier)
                .totalPoints(250)
                .availablePoints(100)
                .status(RecordStatus.ACTIVE)
                .build();

        policy = LoyaltyPolicy.builder()
                .id(20)
                .garage(garage)
                .pointExpiryMonths(12)
                .status(RecordStatus.ACTIVE)
                .build();
    }

    @Test
    void expirePointsClearsAvailablePointsAndCreatesExpireTransactionWhenLastBookingIsExpired() {
        Booking lastCompletedBooking = Booking.builder()
                .id(300)
                .user(user)
                .garage(garage)
                .status(BookingStatus.COMPLETED)
                .completedTime(OffsetDateTime.now().minusMonths(13))
                .build();

        when(accountRepository.findByStatusAndAvailablePointsGreaterThan(RecordStatus.ACTIVE, 0))
                .thenReturn(List.of(account));
        when(policyRepository.findByGarageIdAndStatus(1, RecordStatus.ACTIVE))
                .thenReturn(Optional.of(policy));
        when(bookingRepository.findFirstByUserIdAndGarageIdAndStatusOrderByCompletedTimeDesc(
                10,
                1,
                BookingStatus.COMPLETED
        )).thenReturn(Optional.of(lastCompletedBooking));

        service.expirePoints();

        assertEquals(0, account.getAvailablePoints());
        assertEquals(250, account.getTotalPoints());
        assertNotNull(account.getUpdatedAt());
        verify(accountRepository).save(account);

        ArgumentCaptor<LoyaltyTransaction> transactionCaptor =
                ArgumentCaptor.forClass(LoyaltyTransaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());

        LoyaltyTransaction transaction = transactionCaptor.getValue();
        assertEquals(account, transaction.getAccount());
        assertEquals(-100, transaction.getPoints());
        assertEquals(TransactionType.EXPIRE, transaction.getTransactionType());
        assertTrue(transaction.getDescription().contains("12 months"));
        assertEquals(true, transaction.getExpired());
        assertNotNull(transaction.getEarnedAt());
        assertNotNull(transaction.getExpiresAt());
        assertNotNull(transaction.getCreatedAt());
    }

    @Test
    void expirePointsSkipsAccountWhenLastBookingIsStillWithinExpiryPeriod() {
        Booking lastCompletedBooking = Booking.builder()
                .id(300)
                .user(user)
                .garage(garage)
                .status(BookingStatus.COMPLETED)
                .completedTime(OffsetDateTime.now().minusMonths(6))
                .build();

        when(accountRepository.findByStatusAndAvailablePointsGreaterThan(RecordStatus.ACTIVE, 0))
                .thenReturn(List.of(account));
        when(policyRepository.findByGarageIdAndStatus(1, RecordStatus.ACTIVE))
                .thenReturn(Optional.of(policy));
        when(bookingRepository.findFirstByUserIdAndGarageIdAndStatusOrderByCompletedTimeDesc(
                10,
                1,
                BookingStatus.COMPLETED
        )).thenReturn(Optional.of(lastCompletedBooking));

        service.expirePoints();

        assertEquals(100, account.getAvailablePoints());
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void expirePointsSkipsAccountWhenGarageHasNoActivePolicy() {
        when(accountRepository.findByStatusAndAvailablePointsGreaterThan(RecordStatus.ACTIVE, 0))
                .thenReturn(List.of(account));
        when(policyRepository.findByGarageIdAndStatus(1, RecordStatus.ACTIVE))
                .thenReturn(Optional.empty());

        service.expirePoints();

        assertEquals(100, account.getAvailablePoints());
        verifyNoInteractions(bookingRepository);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void expirePointsSkipsAccountWhenNoCompletedBookingExists() {
        when(accountRepository.findByStatusAndAvailablePointsGreaterThan(RecordStatus.ACTIVE, 0))
                .thenReturn(List.of(account));
        when(policyRepository.findByGarageIdAndStatus(1, RecordStatus.ACTIVE))
                .thenReturn(Optional.of(policy));
        when(bookingRepository.findFirstByUserIdAndGarageIdAndStatusOrderByCompletedTimeDesc(
                10,
                1,
                BookingStatus.COMPLETED
        )).thenReturn(Optional.empty());

        service.expirePoints();

        assertEquals(100, account.getAvailablePoints());
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void expirePointsSkipsAccountWhenLastCompletedBookingHasNoCompletedTime() {
        Booking lastCompletedBooking = Booking.builder()
                .id(300)
                .user(user)
                .garage(garage)
                .status(BookingStatus.COMPLETED)
                .completedTime(null)
                .build();

        when(accountRepository.findByStatusAndAvailablePointsGreaterThan(RecordStatus.ACTIVE, 0))
                .thenReturn(List.of(account));
        when(policyRepository.findByGarageIdAndStatus(1, RecordStatus.ACTIVE))
                .thenReturn(Optional.of(policy));
        when(bookingRepository.findFirstByUserIdAndGarageIdAndStatusOrderByCompletedTimeDesc(
                10,
                1,
                BookingStatus.COMPLETED
        )).thenReturn(Optional.of(lastCompletedBooking));

        service.expirePoints();

        assertEquals(100, account.getAvailablePoints());
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }
}
