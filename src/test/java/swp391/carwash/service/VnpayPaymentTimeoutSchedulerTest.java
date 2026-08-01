package swp391.carwash.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import swp391.carwash.entity.Booking;
import swp391.carwash.entity.Payment;
import swp391.carwash.entity.PaymentTransaction;
import swp391.carwash.enums.BookingStatus;
import swp391.carwash.enums.PaymentMethod;
import swp391.carwash.enums.PaymentStatus;
import swp391.carwash.enums.PaymentTransactionStatus;
import swp391.carwash.repository.BookingRepository;
import swp391.carwash.repository.PaymentRepository;
import swp391.carwash.repository.PaymentTransactionRepository;

@ExtendWith(MockitoExtension.class)
class VnpayPaymentTimeoutSchedulerTest {
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PromotionReleaseService promotionReleaseService;

    /** Scheduler chạy mỗi payment trong transaction riêng -> test cần một PTM tối giản. */
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

    @Test
    void cancelsExpiredPendingPaymentBookingAndAttempts() {
        Booking booking = Booking.builder().id(100).status(BookingStatus.PENDING).build();
        Payment payment = Payment.builder()
                .id(200)
                .booking(booking)
                .method(PaymentMethod.VNPAY)
                .status(PaymentStatus.PENDING)
                .amount(new BigDecimal("50000.00"))
                .expiresAt(OffsetDateTime.now().minusMinutes(1))
                .build();
        PaymentTransaction attempt = PaymentTransaction.builder()
                .id(300)
                .payment(payment)
                .amount(payment.getAmount())
                .status(PaymentTransactionStatus.PENDING)
                .build();
        when(paymentRepository.findExpiredPaymentIds(
                org.mockito.ArgumentMatchers.eq(PaymentMethod.VNPAY),
                org.mockito.ArgumentMatchers.eq(PaymentStatus.PENDING),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(200));
        when(paymentRepository.findById(200)).thenReturn(Optional.of(payment));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
        when(paymentTransactionRepository.findByPaymentIdAndStatus(200, PaymentTransactionStatus.PENDING))
                .thenReturn(List.of(attempt));

        new VnpayPaymentTimeoutScheduler(
                paymentRepository, paymentTransactionRepository, bookingRepository, promotionReleaseService,
                passthroughTxManager())
                .cancelExpiredPayments();

        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
        assertEquals(PaymentTransactionStatus.CANCELLED, attempt.getStatus());
        // Hết hạn cửa sổ thanh toán -> phải nhả mã khuyến mãi đang giữ.
        verify(promotionReleaseService).releaseForBooking(100);
    }

    /**
     * Đơn đã được garage duyệt thì hết hạn phiên VNPAY KHÔNG được huỷ payment: làm vậy sẽ
     * tạo ngõ cụt CONFIRMED + payment CANCELLED (không trả được, không huỷ được, không hoàn tất được).
     * Chỉ đóng attempt treo và xoá hạn để khách bấm thanh toán lại.
     */
    @Test
    void reopensPaymentInsteadOfCancellingWhenBookingAlreadyConfirmed() {
        Booking booking = Booking.builder()
                .id(101)
                .bookingCode("BKG-CONFIRMED")
                .status(BookingStatus.CONFIRMED)
                .build();
        Payment payment = Payment.builder()
                .id(201)
                .booking(booking)
                .method(PaymentMethod.VNPAY)
                .status(PaymentStatus.PENDING)
                .amount(new BigDecimal("152000.00"))
                .expiresAt(OffsetDateTime.now().minusMinutes(1))
                .build();
        PaymentTransaction attempt = PaymentTransaction.builder()
                .id(301)
                .payment(payment)
                .amount(payment.getAmount())
                .status(PaymentTransactionStatus.PENDING)
                .build();

        when(paymentRepository.findExpiredPaymentIds(
                org.mockito.ArgumentMatchers.eq(PaymentMethod.VNPAY),
                org.mockito.ArgumentMatchers.eq(PaymentStatus.PENDING),
                any()))
                .thenReturn(List.of(201));
        when(paymentRepository.findById(201)).thenReturn(Optional.of(payment));
        when(bookingRepository.findDetailedByIdForUpdate(101)).thenReturn(Optional.of(booking));
        when(paymentRepository.findDetailedByIdForUpdate(201)).thenReturn(Optional.of(payment));
        when(paymentTransactionRepository.findByPaymentIdAndStatus(201, PaymentTransactionStatus.PENDING))
                .thenReturn(List.of(attempt));

        new VnpayPaymentTimeoutScheduler(
                paymentRepository, paymentTransactionRepository, bookingRepository, promotionReleaseService,
                passthroughTxManager())
                .cancelExpiredPayments();

        // Payment vẫn mở để khách trả tiếp, hạn cũ bị xoá.
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertNull(payment.getExpiresAt());
        // Booking giữ nguyên: slot đã cam kết cho khách.
        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
        // Attempt của phiên cũ vẫn phải đóng.
        assertEquals(PaymentTransactionStatus.CANCELLED, attempt.getStatus());
        // Đơn chưa huỷ nên không được nhả mã khuyến mãi.
        verify(promotionReleaseService, never()).releaseForBooking(any());
    }
}
