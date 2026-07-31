package swp391.carwash.Schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import swp391.carwash.common.TimeZones;
import swp391.carwash.entity.Booking;
import swp391.carwash.repository.BookingRepository;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingReminderScheduler {


    private final BookingRepository bookingRepository;
    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String mailFrom;


    @Scheduled(fixedRate = 5000)
    @Transactional
    public void sendReminder(){
        // Dùng giờ Việt Nam: server production chạy UTC, còn slot.startTime lưu theo giờ VN.
        // Nếu dùng OffsetDateTime.now() (UTC) rồi so với startTime (VN) sẽ lệch 7 tiếng.
        LocalDateTime now = LocalDateTime.now(TimeZones.VIETNAM);
        LocalDateTime windowStart = now.plusMinutes(59);
        LocalDateTime windowEnd = now.plusMinutes(61);

        // Query lấy đơn có bookingDate = ngày của windowStart và slot.startTime trong [windowStart, windowEnd).
        // (repo CAST tham số về LocalDate/LocalTime, nên truyền cùng mốc windowStart cho date & startTime.)
        List<Booking> bookings = bookingRepository.findBookingNeedReminder(
                windowStart,
                windowStart,
                windowEnd);

        for (Booking booking : bookings) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(mailFrom);
                message.setTo(booking.getUser().getEmail());
                message.setSubject("Nhắc nhở lịch rửa xe của bạn");
                message.setText("Chào bạn, lịch đặt của bạn (Mã: " + booking.getBookingCode()
                        + ") sẽ bắt đầu trong 1 tiếng nữa!");

                mailSender.send(message);

                // Chỉ đánh dấu đã gửi khi gửi mail thành công -> nếu lỗi sẽ được thử lại ở lần quét sau.
                booking.setReminderSent(true);
                bookingRepository.save(booking);

                log.info("Đã gửi mail nhắc nhở cho booking {}", booking.getBookingCode());
            } catch (Exception e) {
                log.error("Lỗi gửi mail nhắc nhở cho booking {}: {}",
                        booking.getBookingCode(), e.getMessage());
            }
        }
    }
}