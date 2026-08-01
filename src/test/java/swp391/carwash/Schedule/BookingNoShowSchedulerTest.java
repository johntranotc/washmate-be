package swp391.carwash.Schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import swp391.carwash.common.TimeZones;
import swp391.carwash.entity.AppUser;
import swp391.carwash.entity.Booking;
import swp391.carwash.entity.BookingSlot;
import swp391.carwash.entity.Notification;
import swp391.carwash.entity.Payment;
import swp391.carwash.entity.PaymentTransaction;
import swp391.carwash.enums.BookingStatus;
import swp391.carwash.enums.PaymentStatus;
import swp391.carwash.repository.BookingRepository;
import swp391.carwash.repository.NotificationRepository;
import swp391.carwash.repository.PaymentRepository;
import swp391.carwash.repository.PaymentTransactionRepository;
import swp391.carwash.service.PromotionReleaseService;

@ExtendWith(MockitoExtension.class)
class BookingNoShowSchedulerTest {

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

    private BookingNoShowScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BookingNoShowScheduler(
                bookingRepository,
                paymentRepository,
                paymentTransactionRepository,
                notificationRepository,
                promotionReleaseService,
                passthroughTxManager());
        ReflectionTestUtils.setField(scheduler, "graceMinutes", 15L);
        ReflectionTestUtils.setField(scheduler, "lookbackDays", 2);
    }

    /** Scheduler chạy mỗi đơn trong transaction riêng -> test cần một PTM tối giản. */
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

    /** @param slotStart giờ HẸN của đơn — mốc để tính no-show (giờ hẹn + graceMinutes). */
    private Booking booking(Integer id, LocalDateTime slotStart, BookingStatus status) {
        AppUser user = new AppUser();
        user.setId(99);

        BookingSlot slot = new BookingSlot();
        slot.setStartTime(slotStart.toLocalTime());
        slot.setEndTime(slotStart.toLocalTime().plusHours(1));

        Booking booking = new Booking();
        booking.setId(id);
        booking.setBookingCode("BKG-TEST-" + id);
        booking.setUser(user);
        booking.setSlot(slot);
        booking.setBookingDate(slotStart.toLocalDate());
        booking.setStatus(status);
        return booking;
    }

    private Payment payment(Integer id, Booking booking, PaymentStatus status) {
        Payment payment = new Payment();
        payment.setId(id);
        payment.setBooking(booking);
        payment.setStatus(status);
        payment.setAmount(new BigDecimal("152000"));
        return payment;
    }

    @Test
    void marksConfirmedBookingAsNoShowAndCancelsPendingPayment() {
        LocalDateTime now = LocalDateTime.now(TimeZones.VIETNAM);
        // Giờ hẹn cách đây 20 phút -> đã vượt ân hạn 15 phút.
        Booking booking = booking(1, now.minusMinutes(20), BookingStatus.CONFIRMED);
        Payment payment = payment(10, booking, PaymentStatus.PENDING);

        when(bookingRepository.findConfirmedBookingsInWindow(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(booking));
        when(bookingRepository.findDetailedByIdForUpdate(1)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingId(1)).thenReturn(Optional.of(payment));
        when(paymentRepository.findDetailedByIdForUpdate(10)).thenReturn(Optional.of(payment));

        scheduler.markExpiredConfirmedBookingsAsNoShow();

        assertEquals(BookingStatus.NO_SHOW, booking.getStatus());
        assertNotNull(booking.getNoShowAt());
        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
        verify(paymentTransactionRepository).save(any(PaymentTransaction.class));
        verify(promotionReleaseService).releaseForBooking(1);
        verify(notificationRepository).save(any(Notification.class));
    }

    @Test
    void keepsPaidPaymentIntactAndDoesNotReleasePromotion() {
        LocalDateTime now = LocalDateTime.now(TimeZones.VIETNAM);
        Booking booking = booking(2, now.minusHours(3), BookingStatus.CONFIRMED);
        Payment payment = payment(20, booking, PaymentStatus.PAID);

        when(bookingRepository.findConfirmedBookingsInWindow(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(booking));
        when(bookingRepository.findDetailedByIdForUpdate(2)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingId(2)).thenReturn(Optional.of(payment));
        when(paymentRepository.findDetailedByIdForUpdate(20)).thenReturn(Optional.of(payment));

        scheduler.markExpiredConfirmedBookingsAsNoShow();

        // Vẫn giải phóng capacity...
        assertEquals(BookingStatus.NO_SHOW, booking.getStatus());
        // ...nhưng không đụng tới tiền đã thu và không nhả mã.
        assertEquals(PaymentStatus.PAID, payment.getStatus());
        verify(paymentTransactionRepository, never()).save(any(PaymentTransaction.class));
        verify(promotionReleaseService, never()).releaseForBooking(2);
    }

    @Test
    void skipsBookingStillWithinGracePeriod() {
        LocalDateTime now = LocalDateTime.now(TimeZones.VIETNAM);
        // Mới quá giờ hẹn 5 phút -> khách vẫn còn 10 phút ân hạn để tới.
        Booking booking = booking(3, now.minusMinutes(5), BookingStatus.CONFIRMED);

        when(bookingRepository.findConfirmedBookingsInWindow(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(booking));

        scheduler.markExpiredConfirmedBookingsAsNoShow();

        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
        assertNull(booking.getNoShowAt());
        verify(bookingRepository, never()).findDetailedByIdForUpdate(3);
    }

    @Test
    void skipsWhenBookingLeftConfirmedBeforeLockAcquired() {
        LocalDateTime now = LocalDateTime.now(TimeZones.VIETNAM);
        Booking candidate = booking(4, now.minusHours(2), BookingStatus.CONFIRMED);
        // Sau khi khóa, staff đã kịp check-in -> scheduler phải bỏ qua.
        Booking locked = booking(4, now.minusHours(2), BookingStatus.CHECKED_IN);

        when(bookingRepository.findConfirmedBookingsInWindow(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(candidate));
        when(bookingRepository.findDetailedByIdForUpdate(4)).thenReturn(Optional.of(locked));

        scheduler.markExpiredConfirmedBookingsAsNoShow();

        assertEquals(BookingStatus.CHECKED_IN, locked.getStatus());
        verify(notificationRepository, never()).save(any(Notification.class));
        verify(promotionReleaseService, never()).releaseForBooking(any());
    }

    @Test
    void handlesSlotWithoutStartTimeGracefully() {
        Booking booking = booking(5, LocalDateTime.now(TimeZones.VIETNAM).minusHours(2), BookingStatus.CONFIRMED);
        booking.getSlot().setStartTime(null);

        when(bookingRepository.findConfirmedBookingsInWindow(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(booking));

        scheduler.markExpiredConfirmedBookingsAsNoShow();

        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
        verify(bookingRepository, never()).findDetailedByIdForUpdate(5);
    }
}
