package swp391.carwash.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        new VnpayPaymentTimeoutScheduler(paymentRepository, paymentTransactionRepository, bookingRepository)
                .cancelExpiredPayments();

        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
        assertEquals(PaymentTransactionStatus.CANCELLED, attempt.getStatus());
    }
}
