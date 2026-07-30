package swp391.carwash.Schedule;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import swp391.carwash.entity.Booking;
import swp391.carwash.entity.Notification;
import swp391.carwash.entity.Payment;
import swp391.carwash.entity.PaymentTransaction;
import swp391.carwash.enums.BookingStatus;
import swp391.carwash.enums.PaymentMethod;
import swp391.carwash.enums.PaymentStatus;
import swp391.carwash.enums.PaymentTransactionStatus;
import swp391.carwash.repository.BookingRepository;
import swp391.carwash.repository.NotificationRepository;
import swp391.carwash.repository.PaymentRepository;
import swp391.carwash.repository.PaymentTransactionRepository;

/**
 * Tự động hủy các đơn thanh toán tiền mặt (CASH) còn ở trạng thái PENDING sau khi
 * khung giờ đặt đã trôi qua (cộng thêm khoảng ân hạn). Mục tiêu: giải phóng slot
 * cho các đơn cash bị bỏ dở mà khách không đến / garage không xác nhận.
 *
 * Chỉ xử lý đơn có giờ slot đã qua nên KHÔNG bao giờ đụng tới đơn đặt trước cho
 * tương lai. VNPAY do {@link swp391.carwash.service.VnpayPaymentTimeoutScheduler}
 * xử lý riêng theo expiresAt.
 */
@Component
@RequiredArgsConstructor
public class CashBookingTimeoutScheduler {

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final NotificationRepository notificationRepository;

    // Số phút ân hạn sau khi khung giờ kết thúc trước khi tự hủy đơn CASH chưa xác nhận.
    @Value("${washmate.booking.cash-timeout.grace-minutes:30}")
    private long graceMinutes;

    @Scheduled(fixedDelayString = "${washmate.booking.cash-timeout.scan-ms:300000}")
    @Transactional
    public void cancelExpiredCashBookings() {
        LocalDateTime now = LocalDateTime.now(swp391.carwash.common.TimeZones.VIETNAM);
        List<Booking> candidates = bookingRepository.findPendingBookingsUpToDate(now.toLocalDate());

        for (Booking booking : candidates) {
            if (booking.getStatus() != BookingStatus.PENDING
                    || booking.getSlot() == null
                    || booking.getSlot().getEndTime() == null) {
                continue;
            }

            // Chỉ hủy khi giờ kết thúc slot + ân hạn đã trôi qua.
            LocalDateTime deadline = LocalDateTime
                    .of(booking.getBookingDate(), booking.getSlot().getEndTime())
                    .plusMinutes(graceMinutes);
            if (deadline.isAfter(now)) {
                continue;
            }

            Payment payment = paymentRepository.findByBookingId(booking.getId()).orElse(null);
            if (payment == null
                    || payment.getMethod() != PaymentMethod.CASH
                    || payment.getStatus() != PaymentStatus.PENDING) {
                continue; // chỉ xử lý đơn tiền mặt chưa thanh toán
            }

            // Khóa theo đúng thứ tự NHẤT QUÁN với BookingService/PaymentService: BOOKING trước,
            // PAYMENT sau -> tránh race chéo với confirm/cancel/settle đồng thời.
            Booking lockedBooking = bookingRepository.findDetailedByIdForUpdate(booking.getId()).orElse(null);
            if (lockedBooking == null || lockedBooking.getStatus() != BookingStatus.PENDING) {
                continue;
            }
            Payment locked = paymentRepository.findDetailedByIdForUpdate(payment.getId()).orElse(null);
            if (locked == null || locked.getStatus() != PaymentStatus.PENDING) {
                continue;
            }

            OffsetDateTime nowOffset = OffsetDateTime.now();
            lockedBooking.setStatus(BookingStatus.CANCELLED);
            lockedBooking.setCancelledAt(nowOffset);

            locked.setStatus(PaymentStatus.CANCELLED);
            locked.setUpdatedAt(nowOffset);

            paymentTransactionRepository.save(PaymentTransaction.builder()
                    .payment(locked)
                    .provider("SYSTEM")
                    .amount(locked.getAmount())
                    .status(PaymentTransactionStatus.CANCELLED)
                    .build());

            notificationRepository.save(Notification.builder()
                    .userId(booking.getUser().getId())
                    .bookingId(booking.getId())
                    .title("Đơn đặt lịch đã hết hạn")
                    .content(String.format(
                            "Đơn đặt lịch %s (thanh toán tiền mặt) đã tự động hủy do quá khung giờ mà chưa được xác nhận.",
                            booking.getBookingCode()))
                    .type("BOOKING_CONFIRMATION")
                    .channel("IN_APP")
                    .status("PENDING")
                    .build());
        }
    }
}
