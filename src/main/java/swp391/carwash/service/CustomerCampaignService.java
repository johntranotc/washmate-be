package swp391.carwash.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swp391.carwash.common.TimeZones;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.dto.request.CampaignSendRequest;
import swp391.carwash.dto.response.CampaignPreviewResponse;
import swp391.carwash.dto.response.CampaignSendResponse;
import swp391.carwash.entity.BusinessInsight;
import swp391.carwash.entity.CampaignSendLog;
import swp391.carwash.entity.Promotion;
import swp391.carwash.enums.DiscountType;
import swp391.carwash.enums.PromotionStatus;
import swp391.carwash.enums.RecordStatus;
import swp391.carwash.enums.TransactionType;
import swp391.carwash.repository.BookingRepository;
import swp391.carwash.repository.BusinessInsightRepository;
import swp391.carwash.repository.CampaignSendLogRepository;
import swp391.carwash.repository.LoyaltyAccountRepository;
import swp391.carwash.repository.LoyaltyTransactionRepository;
import swp391.carwash.repository.PromotionRepository;
import swp391.carwash.service.insight.InsightThresholds;
import swp391.carwash.service.mail.CampaignEmailSender;

/**
 * Vòng lặp "insight → hành động": từ một insight, soạn nháp email + đề xuất voucher
 * (preview), rồi tạo voucher và gửi mail hàng loạt (send) tới ĐÚNG tệp khách của insight đó.
 *
 * <p>Human-in-the-loop: preview KHÔNG gửi gì; send chỉ chạy khi owner đã duyệt.
 * Có chống gửi trùng (dedup) và ghi nhật ký (audit) mỗi lần gửi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerCampaignService {

    /** Trong khoảng phút này không cho gửi lại chiến dịch cho cùng một insight. */
    private static final long DEDUP_COOLDOWN_MINUTES = 30;

    private final BusinessInsightRepository businessInsightRepository;
    private final LoyaltyTransactionRepository loyaltyTransactionRepository;
    private final LoyaltyAccountRepository loyaltyAccountRepository;
    private final BookingRepository bookingRepository;
    private final PromotionRepository promotionRepository;
    private final CampaignSendLogRepository campaignSendLogRepository;
    private final CampaignEmailSender campaignEmailSender;
    private final GeminiClient geminiClient;

    @Transactional(readOnly = true)
    public CampaignPreviewResponse preview(Integer insightId) {
        BusinessInsight insight = getInsight(insightId);
        // Preview trước khi chọn chi nhánh → đếm trên toàn hệ thống (garageId null).
        List<String> emails = resolveSegmentEmails(insight.getRuleCode(), null);

        String[] draft = draftEmail(insight);
        boolean aiGenerated = geminiClient.isConfigured();

        return new CampaignPreviewResponse(
                insight.getRuleCode(),
                emails.size(),
                emails.stream().limit(5).toList(),
                draft[0],
                draft[1],
                "PERCENTAGE",
                15,
                aiGenerated);
    }

    @Transactional
    public CampaignSendResponse send(Integer insightId, CampaignSendRequest request, Integer sentByUserId) {
        BusinessInsight insight = getInsight(insightId);

        OffsetDateTime dedupCutoff = OffsetDateTime.now(TimeZones.VIETNAM).minusMinutes(DEDUP_COOLDOWN_MINUTES);
        if (campaignSendLogRepository.existsByInsightIdAndSentAtAfterAndSentCountGreaterThan(insightId, dedupCutoff, 0)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Insight này vừa được gửi chiến dịch gần đây. Vui lòng chờ trước khi gửi lại.");
        }

        List<String> emails = resolveSegmentEmails(insight.getRuleCode(), request.garageId());
        if (emails.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Không có khách nào trong tệp mục tiêu để gửi");
        }

        Promotion voucher = createVoucher(request, emails.size());
        String bodyWithVoucher = request.body() + "\n\nMã ưu đãi của bạn: " + voucher.getPromoCode();

        int sent = 0;
        int failed = 0;
        String firstError = null;
        for (String email : emails) {
            String error = campaignEmailSender.send(email, request.subject(), bodyWithVoucher);
            if (error == null) {
                sent++;
            } else {
                failed++;
                if (firstError == null) {
                    firstError = error;
                }
            }
        }

        // Fail toàn bộ (thường do cấu hình SMTP/from chưa đúng): rollback voucher + log,
        // trả lỗi rõ ràng thay vì 200 với sent=0. Owner có thể sửa cấu hình và gửi lại ngay.
        if (sent == 0) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Không gửi được email nào (%d lỗi). Nguyên nhân: %s".formatted(failed, firstError));
        }

        campaignSendLogRepository.save(CampaignSendLog.builder()
                .insightId(insightId)
                .ruleCode(insight.getRuleCode())
                .garageId(request.garageId())
                .voucherCode(voucher.getPromoCode())
                .sentCount(sent)
                .failedCount(failed)
                .sentByUserId(sentByUserId)
                .sentAt(OffsetDateTime.now(TimeZones.VIETNAM))
                .build());

        log.info("Campaign insight={} rule={} sent={} failed={} voucher={} by={}",
                insightId, insight.getRuleCode(), sent, failed, voucher.getPromoCode(), sentByUserId);

        return new CampaignSendResponse(sent, failed, voucher.getPromoCode());
    }

    /**
     * Chọn ĐÚNG tệp khách theo loại insight — thay vì luôn dùng một tệp cố định.
     */
    private List<String> resolveSegmentEmails(String ruleCode, Integer garageId) {
        OffsetDateTime expiryThreshold = OffsetDateTime.now(TimeZones.VIETNAM)
                .plusDays(InsightThresholds.EXPIRING_WINDOW_DAYS);
        LocalDate inactiveCutoff = LocalDate.now(TimeZones.VIETNAM)
                .minusDays(InsightThresholds.INACTIVE_DAYS);

        return switch (ruleCode == null ? "" : ruleCode) {
            case "POINTS_EXPIRING_SOON" -> loyaltyTransactionRepository
                    .findDistinctEmailsWithExpiringPoints(TransactionType.EARN, expiryThreshold, garageId);
            case "LOW_POINT_REDEMPTION" -> loyaltyAccountRepository
                    .findEmailsWithAvailablePoints(RecordStatus.ACTIVE, garageId);
            case "INACTIVE_WINBACK", "LOW_RETURNING_CUSTOMER_RATE" -> bookingRepository
                    .findInactiveCustomerEmails(inactiveCutoff, garageId);
            case "HIGH_VALUE_CUSTOMER_GROUP", "UPGRADE_STALL" -> loyaltyAccountRepository
                    .findActiveLoyaltyEmails(RecordStatus.ACTIVE, garageId);
            default -> loyaltyTransactionRepository
                    .findDistinctEmailsWithExpiringPoints(TransactionType.EARN, expiryThreshold, garageId);
        };
    }

    private BusinessInsight getInsight(Integer insightId) {
        return businessInsightRepository.findById(insightId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Insight không tồn tại"));
    }

    private Promotion createVoucher(CampaignSendRequest request, int audienceSize) {
        DiscountType discountType;
        try {
            discountType = DiscountType.valueOf(request.discountType());
        } catch (IllegalArgumentException ex) {
            discountType = DiscountType.PERCENTAGE;
        }
        OffsetDateTime now = OffsetDateTime.now(TimeZones.VIETNAM);
        String promoCode = "WM" + Long.toString(System.currentTimeMillis(), 36).toUpperCase();

        Promotion promotion = Promotion.builder()
                .garageId(request.garageId())
                .promoCode(promoCode)
                .discountValue(BigDecimal.valueOf(request.discountValue()))
                .discountType(discountType)
                .maxDiscount(null)
                .minOrderValue(BigDecimal.ZERO)
                .usageLimit(audienceSize)
                .usedCount(0)
                .startDate(now)
                .endDate(now.plusDays(request.voucherValidDays()))
                .status(PromotionStatus.ACTIVE)
                .build();
        return promotionRepository.save(promotion);
    }

    /** Trả về [subject, body]. Dùng Gemini nếu cấu hình; nếu không thì template mặc định. */
    private String[] draftEmail(BusinessInsight insight) {
        String fallbackSubject = insight.getTitle() + " — ưu đãi dành riêng cho bạn";
        String fallbackBody = "Chào bạn, chúng tôi có một ưu đãi dành riêng cho bạn. "
                + "Đặt lịch rửa xe trong thời gian tới để nhận voucher giảm giá. Hẹn gặp bạn tại AutoWash!";

        if (!geminiClient.isConfigured()) {
            return new String[] {fallbackSubject, fallbackBody};
        }

        String prompt = """
                Bạn là trợ lý marketing của tiệm rửa xe WashMate. Soạn một email ngắn (tiếng Việt, thân thiện,
                tối đa 90 từ) gửi khách hàng dựa trên tình huống sau: "%s - %s".
                Mục tiêu: khuyến khích khách quay lại rửa xe và dùng ưu đãi.
                Trả về ĐÚNG định dạng:
                SUBJECT: <tiêu đề>
                BODY: <nội dung>
                """.formatted(insight.getTitle(), insight.getSummary() == null ? "" : insight.getSummary());

        try {
            String raw = geminiClient.generateContent(prompt);
            if (raw == null || raw.isBlank()) {
                return new String[] {fallbackSubject, fallbackBody};
            }
            String subject = extract(raw, "SUBJECT:");
            String body = extract(raw, "BODY:");
            return new String[] {
                    subject.isBlank() ? fallbackSubject : subject,
                    body.isBlank() ? fallbackBody : body
            };
        } catch (RuntimeException ex) {
            log.warn("Gemini draft failed, dùng template mặc định: {}", ex.getMessage());
            return new String[] {fallbackSubject, fallbackBody};
        }
    }

    private String extract(String raw, String marker) {
        int idx = raw.indexOf(marker);
        if (idx < 0) {
            return "";
        }
        int start = idx + marker.length();
        int end = raw.length();
        String[] otherMarkers = {"SUBJECT:", "BODY:"};
        for (String other : otherMarkers) {
            if (other.equals(marker)) {
                continue;
            }
            int otherIdx = raw.indexOf(other, start);
            if (otherIdx >= 0 && otherIdx < end) {
                end = otherIdx;
            }
        }
        return raw.substring(start, end).trim();
    }
}
