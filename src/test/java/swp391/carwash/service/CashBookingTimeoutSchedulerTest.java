package swp391.carwash.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import swp391.carwash.Schedule.CashBookingTimeoutScheduler;
import swp391.carwash.entity.AppUser;
import swp391.carwash.entity.Booking;
import swp391.carwash.entity.BookingSlot;
import swp391.carwash.entity.Notification;
import swp391.carwash.entity.Payment;
import swp391.carwash.entity.PaymentTransaction;
import swp391.carwash.enums.BookingStatus;
import swp391.carwash.enums.PaymentMethod;
import swp391.carwash.enums.PaymentStatus;
import swp391.carwash.repository.BookingRepository;
import swp391.carwash.repository.NotificationRepository;
import swp391.carwash.repository.PaymentRepository;
import swp391.carwash.repository.PaymentTransactionRepository;

@ExtendWith(MockitoExtension.class)
class CashBookingTimeoutSchedulerTest {
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private PromotionReleaseService promotionReleaseService;

    /**
     * Scheduler chạy mỗi đơn trong transaction RIÊNG qua TransactionTemplate, nên test cần
     * một PlatformTransactionManager tối giản: mở transaction giả rồi để callback chạy thẳng.
     */
    private static org.springframework.transaction.PlatformTransactionManager passthroughTxManager() {
        return new org.springframework.transaction.PlatformTransactionManager() {
            @Override
            public org.springframework.transaction.TransactionStatus getTransaction(
                    org.springframework.transaction.TransactionDefinition definition) {
                return new org.springframework.transaction.support.SimpleTransactionStatus();
            }

            @Override
            public void commit(org.springframework.transaction.TransactionStatus status) {
            }

            @Override
            public void rollback(org.springframework.transaction.TransactionStatus status) {
            }
        };
    }

    private CashBookingTimeoutScheduler scheduler() {
        return new CashBookingTimeoutScheduler(
                bookingRepository, paymentRepository, paymentTransactionRepository, notificationRepository,
                promotionReleaseService, passthroughTxManager());
    }

    @Test
    void cancelsExpiredPendingCashBookingAndFreesSlot() {
        AppUser user = AppUser.builder().id(10).build();
        BookingSlot slot = BookingSlot.builder()
                .id(5).startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(9, 0)).build();
        Booking booking = Booking.builder()
                .id(100).bookingCode("BKG-1").user(user).slot(slot)
                .bookingDate(LocalDate.now().minusDays(1))
                .status(BookingStatus.PENDING).build();
        Payment payment = Payment.builder()
                .id(200).booking(booking).method(PaymentMethod.CASH)
                .status(PaymentStatus.PENDING).amount(new BigDecimal("50000.00")).build();

        when(bookingRepository.findPendingBookingsUpToDate(any())).thenReturn(List.of(booking));
        when(paymentRepository.findByBookingId(100)).thenReturn(Optional.of(payment));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));

        scheduler().cancelExpiredCashBookings();

        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
        verify(paymentTransactionRepository).save(any(PaymentTransaction.class));
        verify(notificationRepository).save(any(Notification.class));
    }

    @Test
    void skipsBookingWhoseSlotHasNotPassed() {
        BookingSlot slot = BookingSlot.builder().id(5).endTime(LocalTime.of(9, 0)).build();
        Booking booking = Booking.builder()
                .id(101).slot(slot).bookingDate(LocalDate.now().plusDays(1))
                .status(BookingStatus.PENDING).build();

        when(bookingRepository.findPendingBookingsUpToDate(any())).thenReturn(List.of(booking));

        scheduler().cancelExpiredCashBookings();

        assertEquals(BookingStatus.PENDING, booking.getStatus());
        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    void skipsNonCashPayment() {
        BookingSlot slot = BookingSlot.builder().id(5).endTime(LocalTime.of(9, 0)).build();
        Booking booking = Booking.builder()
                .id(102).slot(slot).bookingDate(LocalDate.now().minusDays(1))
                .status(BookingStatus.PENDING).build();
        Payment payment = Payment.builder()
                .id(202).booking(booking).method(PaymentMethod.VNPAY)
                .status(PaymentStatus.PENDING).amount(new BigDecimal("50000.00")).build();

        when(bookingRepository.findPendingBookingsUpToDate(any())).thenReturn(List.of(booking));
        when(paymentRepository.findByBookingId(102)).thenReturn(Optional.of(payment));

        scheduler().cancelExpiredCashBookings();

        assertEquals(BookingStatus.PENDING, booking.getStatus());
        verify(paymentTransactionRepository, never()).save(any());
    }
}
