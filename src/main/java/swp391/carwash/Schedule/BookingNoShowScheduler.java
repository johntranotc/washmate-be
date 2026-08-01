package swp391.carwash.Schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import swp391.carwash.common.TimeZones;
import swp391.carwash.entity.Booking;
import swp391.carwash.entity.Notification;
import swp391.carwash.entity.Payment;
import swp391.carwash.entity.PaymentTransaction;
import swp391.carwash.enums.BookingStatus;
import swp391.carwash.enums.PaymentStatus;
import swp391.carwash.enums.PaymentTransactionStatus;
import swp391.carwash.repository.BookingRepository;
import swp391.carwash.repository.NotificationRepository;
import swp391.carwash.repository.PaymentRepository;
import swp391.carwash.repository.PaymentTransactionRepository;
import swp391.carwash.service.PromotionReleaseService;

/**
 * Tự động đánh dấu NO_SHOW cho các đơn đã CONFIRMED nhưng khách không đến.
 *
 * <p>Mốc tính là GIỜ BẮT ĐẦU slot cộng ân hạn (mặc định 15 phút): quá giờ hẹn 15 phút
 * mà đơn vẫn chưa được garage check-in thì coi như khách không tới, giải phóng slot
 * ngay để garage nhận khách khác — không cần đợi hết cả khung giờ.
 *
 * <p>Lý do tồn tại: trước đây NO_SHOW chỉ được set thủ công qua
 * {@code POST /api/bookings/{id}/no-show}. Nếu staff quên bấm, đơn kẹt ở
 * CONFIRMED vĩnh viễn. Vì CONFIRMED nằm trong CAPACITY_BLOCKING_STATUSES của
 * BookingService nên đơn chết đó chiếm chỗ slot mãi mãi, và
 * {@link swp391.carwash.service.VnpayPaymentTimeoutScheduler} /
 * {@link CashBookingTimeoutScheduler} đều không chạm tới (cả hai chỉ xử lý
 * booking PENDING).
 *
 * <p>Chỉ xét đơn có giờ hẹn đã trôi qua nên KHÔNG bao giờ đụng đơn đặt trước
 * cho tương lai. Đơn đã thanh toán (PAID) vẫn được đánh dấu NO_SHOW để giải
 * phóng capacity, nhưng payment giữ nguyên PAID và promotion KHÔNG bị nhả —
 * việc hoàn tiền do luồng refund xử lý riêng.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingNoShowScheduler {

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final NotificationRepository notificationRepository;
    private final PromotionReleaseService promotionReleaseService;
    private final PlatformTransactionManager transactionManager;

    // Số phút ân hạn sau GIỜ HẸN (giờ bắt đầu slot) trước khi tự đánh dấu NO_SHOW.
    @Value("${washmate.booking.no-show.grace-minutes:15}")
    private long graceMinutes;

    // Chỉ quét ngược tối đa bấy nhiêu ngày. Scheduler chạy mỗi phút nên 2 ngày là thừa sức
    // bắt mọi đơn lỡ hẹn thật, kể cả khi service có sập vài tiếng.
    @Value("${washmate.booking.no-show.lookback-days:2}")
    private int lookbackDays;

    // Dùng CHUNG với BookingService.validateNoShowStrikes để lời cảnh báo gửi cho khách
    // khớp đúng với ngưỡng thực sự chặn họ.
    @Value("${washmate.booking.no-show.max-before-block:2}")
    private int maxNoShowsBeforeBlock;

    @Value("${washmate.booking.no-show.block-window-days:90}")
    private int noShowBlockWindowDays;

    // Quét mỗi phút: ân hạn chỉ 15 phút nên chu kỳ quét thưa sẽ làm slot bị giữ thêm
    // cả chu kỳ đó trước khi được giải phóng.
    //
    // CỐ Ý KHÔNG đặt @Transactional ở đây. Nếu cả vòng lặp nằm trong một transaction thì:
    //   - Một đơn lỗi là rollback cả mẻ; phút sau quét lại gặp đúng đơn đó, lại rollback
    //     -> KHÔNG đơn nào được xử lý, mãi mãi, mà log chỉ hiện đúng một exception.
    //   - Pessimistic lock của mọi đơn đã xử lý bị giữ tới khi commit cả mẻ -> khách bấm
    //     huỷ đúng lúc đó sẽ bị treo.
    // Mỗi đơn chạy trong transaction RIÊNG và lỗi được cô lập theo từng đơn.
    @Scheduled(fixedDelayString = "${washmate.booking.no-show.scan-ms:60000}")
    public void markExpiredConfirmedBookingsAsNoShow() {
        LocalDateTime now = LocalDateTime.now(TimeZones.VIETNAM);
        // Chỉ xét đơn trong cửa sổ gần đây. Đơn CONFIRMED cũ hơn thế là RÁC DỮ LIỆU tồn đọng
        // (staff quên bấm complete, đơn kẹt từ đợt lỗi cũ...), không phải khách vừa lỡ hẹn —
        // đánh vắng mặt cho chúng là gửi thông báo sai và tính án tích oan cho khách thật.
        // Dọn tồn đọng cũ phải làm riêng bằng script, có người xem xét.
        LocalDate from = now.toLocalDate().minusDays(lookbackDays);
        List<Booking> candidates = bookingRepository.findConfirmedBookingsInWindow(from, now.toLocalDate());
        TransactionTemplate perBooking = newPerItemTransaction();

        for (Booking candidate : candidates) {
            if (candidate.getSlot() == null || candidate.getSlot().getStartTime() == null) {
                continue;
            }

            // Quá giờ hẹn + ân hạn mà chưa check-in -> khách không tới.
            LocalDateTime deadline = LocalDateTime
                    .of(candidate.getBookingDate(), candidate.getSlot().getStartTime())
                    .plusMinutes(graceMinutes);
            if (deadline.isAfter(now)) {
                continue;
            }

            try {
                perBooking.executeWithoutResult(status -> markOne(candidate.getId()));
            } catch (RuntimeException ex) {
                // Log kèm bookingId để lần được đúng đơn hỏng, rồi đi tiếp đơn kế.
                log.error("Không đánh dấu NO_SHOW được cho booking {} - bỏ qua, sẽ thử lại lần quét sau",
                        candidate.getId(), ex);
            }
        }
    }

    private TransactionTemplate newPerItemTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /**
     * Khóa theo đúng thứ tự NHẤT QUÁN với BookingService/PaymentService: BOOKING
     * trước, PAYMENT sau -> tránh race chéo với check-in/cancel/settle đồng thời.
     */
    private void markOne(Integer bookingId) {
        Booking booking = bookingRepository.findDetailedByIdForUpdate(bookingId).orElse(null);
        // Re-check sau khi khóa: staff có thể vừa check-in hoặc hủy đơn.
        if (booking == null || booking.getStatus() != BookingStatus.CONFIRMED) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        booking.setStatus(BookingStatus.NO_SHOW);
        booking.setNoShowAt(now);

        Payment paymentRef = paymentRepository.findByBookingId(bookingId).orElse(null);
        Payment payment = paymentRef == null
                ? null
                : paymentRepository.findDetailedByIdForUpdate(paymentRef.getId()).orElse(null);

        boolean unpaid = payment != null && payment.getStatus() == PaymentStatus.PENDING;
        if (unpaid) {
            // Khách không đến và chưa trả tiền -> đóng payment đang treo.
            payment.setStatus(PaymentStatus.CANCELLED);
            payment.setUpdatedAt(now);
            paymentTransactionRepository.save(PaymentTransaction.builder()
                    .payment(payment)
                    .provider("SYSTEM")
                    .amount(payment.getAmount())
                    .status(PaymentTransactionStatus.CANCELLED)
                    .build());
        }

        // Chỉ nhả mã khuyến mãi khi đơn CHƯA thanh toán. Đơn đã PAID thì mã coi như
        // đã tiêu; hoàn mã (nếu có) thuộc trách nhiệm của luồng refund.
        boolean paid = payment != null && payment.getStatus() == PaymentStatus.PAID;
        if (!paid) {
            promotionReleaseService.releaseForBooking(bookingId);
        }

        notificationRepository.save(Notification.builder()
                .userId(booking.getUser().getId())
                .bookingId(bookingId)
                .title("Đơn đặt lịch được đánh dấu vắng mặt")
                .content(String.format(
                        "Đơn đặt lịch %s đã quá giờ hẹn %d phút mà chưa check-in nên hệ thống tự đánh dấu vắng mặt (no-show) "
                                + "và giải phóng khung giờ.%s Vui lòng liên hệ garage nếu bạn cho rằng đây là nhầm lẫn.",
                        booking.getBookingCode(), graceMinutes, strikeWarning(booking.getUser().getId())))
                .type("BOOKING_CONFIRMATION")
                .channel("IN_APP")
                .status("PENDING")
                .build());

        log.info("Auto NO_SHOW booking {} (paid={})", booking.getBookingCode(), paid);
    }

    /**
     * Báo trước cho khách khi họ sắp/vừa chạm ngưỡng bị cấm đặt lịch. Bị chặn mà không hiểu
     * vì sao là trải nghiệm tệ nhất — nói thẳng ngay ở lần vắng mặt đầu tiên.
     *
     * <p>Lần vắng mặt vừa ghi ở trên đã nằm trong phép đếm này (cùng transaction).
     */
    private String strikeWarning(Integer userId) {
        if (maxNoShowsBeforeBlock <= 0) {
            return "";
        }
        long noShows = bookingRepository.countRecentNoShows(
                userId, OffsetDateTime.now().minusDays(noShowBlockWindowDays));

        if (noShows >= maxNoShowsBeforeBlock) {
            return String.format(
                    " Bạn đã vắng mặt %d lần trong %d ngày qua nên tạm thời không thể đặt lịch mới.",
                    noShows, noShowBlockWindowDays);
        }
        long remaining = maxNoShowsBeforeBlock - noShows;
        return String.format(
                " Lưu ý: vắng mặt thêm %d lần nữa trong %d ngày sẽ bị tạm ngưng quyền đặt lịch.",
                remaining, noShowBlockWindowDays);
    }
}
