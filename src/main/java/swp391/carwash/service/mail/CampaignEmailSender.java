package swp391.carwash.service.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Gửi email chiến dịch (nhắc đổi điểm / win-back) tới khách hàng.
 * Dùng chung JavaMailSender như InvoiceEmailSender. Trả về true nếu gửi thành công.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignEmailSender {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String from;

    // Mock mode: log thay vì gửi thật → test full flow không cần SMTP (Resend key).
    @Value("${washmate.campaign.mail.mock:false}")
    private boolean mockMode;

    /** @return {@code null} nếu gửi thành công, ngược lại là thông điệp lỗi (để caller surface lên API). */
    public String send(String toEmail, String subject, String bodyText) {
        if (toEmail == null || toEmail.isBlank()) {
            return "Địa chỉ email trống";
        }
        if (mockMode) {
            log.info("[MOCK] Campaign email → {} | subject: {} | body: {}",
                    toEmail, subject, bodyText == null ? "" : bodyText.replace("\n", " ").trim());
            return null;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(buildHtml(bodyText), true);
            mailSender.send(message);
            log.info("Campaign email sent to {}", toEmail);
            return null;
        } catch (MessagingException | RuntimeException e) {
            log.error("Failed to send campaign email to {}: {}", toEmail, e.getMessage());
            return e.getMessage();
        }
    }

    private String buildHtml(String bodyText) {
        String safe = bodyText == null ? "" : bodyText.replace("\n", "<br>");
        return """
            <div style="font-family:sans-serif;max-width:600px;margin:20px auto;
                        padding:30px;border:1px solid #e5e7eb;border-radius:12px">
              <div style="text-align:center;margin-bottom:20px;">
                  <h1 style="margin:0;color:#2563eb;">WASHMATE</h1>
              </div>
              <hr style="border:none;border-top:1px solid #e5e7eb;margin:20px 0;">
              <p style="color:#374151;font-size:15px;line-height:1.6;">%s</p>
              <p style="color:#9ca3af;font-size:13px;text-align:center;margin-top:30px;">
                Email này được gửi từ chương trình khách hàng thân thiết WashMate.
              </p>
            </div>
            """.formatted(safe);
    }
}
