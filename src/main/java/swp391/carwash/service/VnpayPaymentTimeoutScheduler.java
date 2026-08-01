package swp391.carwash.service;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "washmate.payment.vnpay.enabled", havingValue = "true")
public class VnpayPaymentTimeoutScheduler {
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final BookingRepository bookingRepository;
    private final PromotionReleaseService promotionReleaseService;
    private final PlatformTransactionManager transactionManager;

    // KHÔNG @Transactional ở đây: một payment lỗi không được phép rollback cả mẻ và làm
    // scheduler kẹt vĩnh viễn. Mỗi payment chạy trong transaction riêng.
    @Scheduled(fixedDelayString = "${washmate.payment.vnpay.timeout-scan-ms:60000}")
    public void cancelExpiredPayments() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Integer> paymentIds = paymentRepository.findExpiredPaymentIds(
                PaymentMethod.VNPAY, PaymentStatus.PENDING, now);

        TransactionTemplate perPayment = new TransactionTemplate(transactionManager);
        perPayment.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        for (Integer paymentId : paymentIds) {
            try {
                perPayment.executeWithoutResult(status -> handleExpiredPayment(paymentId, now));
            } catch (RuntimeException ex) {
                log.error("Không xử lý được payment VNPAY hết hạn {} - bỏ qua, thử lại lần quét sau",
                        paymentId, ex);
            }
        }
    }

    private void handleExpiredPayment(Integer paymentId, OffsetDateTime now) {
        // Đọc payment nhẹ để lấy bookingId, rồi khóa theo thứ tự NHẤT QUÁN: BOOKING trước, PAYMENT sau.
        Payment paymentRef = paymentRepository.findById(paymentId).orElse(null);
        if (paymentRef == null) {
            return;
        }
        Booking booking = bookingRepository.findDetailedByIdForUpdate(paymentRef.getBooking().getId()).orElse(null);
        if (booking == null) {
            return;
        }
        Payment payment = paymentRepository.findDetailedByIdForUpdate(paymentId).orElse(null);
        if (payment == null
                || payment.getMethod() != PaymentMethod.VNPAY
                || payment.getStatus() != PaymentStatus.PENDING
                || payment.getExpiresAt() == null
                || payment.getExpiresAt().isAfter(now)) {
            return;
        }

        // Phiên thanh toán đã hết hạn -> đóng mọi attempt đang treo (dù xử lý booking thế nào).
        List<PaymentTransaction> pendingAttempts = paymentTransactionRepository
                .findByPaymentIdAndStatus(paymentId, PaymentTransactionStatus.PENDING);
        pendingAttempts.forEach(attempt -> attempt.setStatus(PaymentTransactionStatus.CANCELLED));

        if (booking.getStatus() == BookingStatus.PENDING) {
            // Đơn chưa được garage duyệt và khách cũng không trả tiền -> huỷ, giải phóng slot.
            payment.setStatus(PaymentStatus.CANCELLED);
            payment.setUpdatedAt(now);
            booking.setStatus(BookingStatus.CANCELLED);
            booking.setCancelledAt(now);
            promotionReleaseService.releaseForBooking(booking.getId());
            return;
        }

        // Đơn đã được garage duyệt (CONFIRMED trở lên): slot đã được cam kết cho khách nên
        // KHÔNG huỷ. Chỉ mở lại cửa sổ thanh toán để khách trả tiếp.
        //
        // Trước đây nhánh này set payment CANCELLED mà để booking nguyên CONFIRMED, tạo ngõ cụt:
        // createPaymentUrl / confirmPayment / cancelPayment / complete đều chặn vì payment không
        // còn PENDING -> đơn kẹt vĩnh viễn và chiếm capacity slot.
        // Khách không đến thật thì BookingNoShowScheduler dọn sau khi qua giờ slot.
        payment.setExpiresAt(null);
        payment.setUpdatedAt(now);
        log.info("VNPAY session expired for booking {} (status={}) -> mở lại payment {} cho khách thanh toán lại",
                booking.getBookingCode(), booking.getStatus(), paymentId);
    }
}
