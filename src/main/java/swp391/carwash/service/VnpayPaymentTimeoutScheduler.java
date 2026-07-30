package swp391.carwash.service;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
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

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "washmate.payment.vnpay.enabled", havingValue = "true")
public class VnpayPaymentTimeoutScheduler {
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final BookingRepository bookingRepository;

    @Scheduled(fixedDelayString = "${washmate.payment.vnpay.timeout-scan-ms:60000}")
    @Transactional
    public void cancelExpiredPayments() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Integer> paymentIds = paymentRepository.findExpiredPaymentIds(
                PaymentMethod.VNPAY, PaymentStatus.PENDING, now);

        for (Integer paymentId : paymentIds) {
            // Đọc payment nhẹ để lấy bookingId, rồi khóa theo thứ tự NHẤT QUÁN: BOOKING trước, PAYMENT sau.
            Payment paymentRef = paymentRepository.findById(paymentId).orElse(null);
            if (paymentRef == null) {
                continue;
            }
            Booking booking = bookingRepository.findDetailedByIdForUpdate(paymentRef.getBooking().getId()).orElse(null);
            if (booking == null) {
                continue;
            }
            Payment payment = paymentRepository.findDetailedByIdForUpdate(paymentId).orElse(null);
            if (payment == null
                    || payment.getMethod() != PaymentMethod.VNPAY
                    || payment.getStatus() != PaymentStatus.PENDING
                    || payment.getExpiresAt() == null
                    || payment.getExpiresAt().isAfter(now)) {
                continue;
            }

            payment.setStatus(PaymentStatus.CANCELLED);
            payment.setUpdatedAt(now);
            if (booking.getStatus() == BookingStatus.PENDING) {
                booking.setStatus(BookingStatus.CANCELLED);
                booking.setCancelledAt(now);
            }

            List<PaymentTransaction> pendingAttempts = paymentTransactionRepository
                    .findByPaymentIdAndStatus(paymentId, PaymentTransactionStatus.PENDING);
            pendingAttempts.forEach(attempt -> attempt.setStatus(PaymentTransactionStatus.CANCELLED));
        }
    }
}
