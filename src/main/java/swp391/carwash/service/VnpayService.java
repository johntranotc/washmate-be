package swp391.carwash.service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.config.VnpayProperties;
import swp391.carwash.dto.VnpayIpnResponse;
import swp391.carwash.dto.VnpayPaymentUrlResponse;
import swp391.carwash.entity.Booking;
import swp391.carwash.entity.Invoice;
import swp391.carwash.entity.Payment;
import swp391.carwash.entity.PaymentTransaction;
import swp391.carwash.enums.BookingStatus;
import swp391.carwash.enums.PaymentMethod;
import swp391.carwash.enums.PaymentStatus;
import swp391.carwash.enums.PaymentTransactionStatus;
import swp391.carwash.repository.PaymentRepository;
import swp391.carwash.repository.PaymentTransactionRepository;
import swp391.carwash.security.AppUserDetails;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class VnpayService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VnpayService.class);
    private static final String PROVIDER = "VNPAY";
    private static final ZoneId VIETNAM_ZONE = swp391.carwash.common.TimeZones.VIETNAM;
    private static final DateTimeFormatter VNPAY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final EnumSet<BookingStatus> VNPAY_BOOKING_STATUSES = EnumSet.of(
            BookingStatus.PENDING,
            BookingStatus.CONFIRMED,
            BookingStatus.CHECKED_IN,
            BookingStatus.WASHING);

    private final VnpayProperties properties;
    private final VnpaySigner signer;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentSettlementService paymentSettlementService;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final swp391.carwash.repository.BookingRepository bookingRepository;

    @PostConstruct
    void validateConfiguration() {
        if (!properties.isEnabled()) {
            return;
        }
        if (isBlank(properties.getTmnCode())
                || isBlank(properties.getHashSecret())
                || isBlank(properties.getPayUrl())
                || isBlank(properties.getReturnUrl())
                || isBlank(properties.getFrontendResultUrl())) {
            throw new IllegalStateException("VNPAY is enabled but its configuration is incomplete");
        }
        if (properties.getTimeoutMinutes() <= 0) {
            throw new IllegalStateException("VNPAY timeout must be positive");
        }
        URI.create(properties.getPayUrl());
        URI.create(properties.getReturnUrl());
        URI.create(properties.getFrontendResultUrl());
    }

    @Transactional
    public VnpayPaymentUrlResponse createPaymentUrl(Integer paymentId, AppUserDetails principal, String clientIp) {
        requireEnabled();
        Payment payment = paymentRepository.findDetailedByIdForUpdate(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payment not found"));
        Booking booking = payment.getBooking();

        if (!booking.getUser().getId().equals(principal.getId())
                || !principal.getRoleNames().contains("CUSTOMER")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You cannot create a VNPAY URL for this payment");
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Only PENDING payment can create a VNPAY URL");
        }
        if (!VNPAY_BOOKING_STATUSES.contains(booking.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only active booking can create a VNPAY URL");
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (payment.getMethod() != PaymentMethod.VNPAY) {
            payment.setMethod(PaymentMethod.VNPAY);
            payment.setUpdatedAt(now);
        }
        if (payment.getExpiresAt() == null) {
            payment.setExpiresAt(now.plusMinutes(properties.getTimeoutMinutes()));
            payment.setUpdatedAt(now);
        }
        if (!payment.getExpiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "VNPAY payment has expired");
        }

        // Mỗi lần bấm thanh toán = 1 attempt MỚI với vnp_TxnRef mới.
        // KHÔNG tái sử dụng attempt PENDING cũ: VNPAY từ chối vnp_TxnRef trùng
        // (Error.html?code=01) — xảy ra khi khách hủy giữa chừng rồi bấm thanh toán lại,
        // vì hủy thường không sinh IPN nên attempt cũ vẫn nằm ở PENDING.
        // Các attempt PENDING cũ được giữ nguyên (không hủy) để nếu khách quay lại
        // hoàn tất đúng lần thanh toán đó thì IPN vẫn settle được;
        // khi một attempt settle thành công, cancelOtherPendingAttempts() dọn phần còn lại.
        PaymentTransaction attempt = paymentTransactionRepository.save(PaymentTransaction.builder()
                .payment(payment)
                .provider(PROVIDER)
                .merchantTxnRef(generateMerchantTxnRef(paymentId, now))
                .amount(payment.getAmount())
                .status(PaymentTransactionStatus.PENDING)
                .expiresAt(payment.getExpiresAt())
                .createdAt(now)
                .build());

        Map<String, String> parameters = buildPaymentParameters(payment, booking, attempt, clientIp);
        String paymentUrl = signer.buildPaymentUrl(
                properties.getPayUrl(), parameters, properties.getHashSecret());
        return new VnpayPaymentUrlResponse(payment.getId(), paymentUrl, attempt.getExpiresAt());
    }

    public VnpayIpnResponse handleIpn(Map<String, String> callbackParameters) {
        if (!isCallbackAuthentic(callbackParameters)) {
            return VnpayIpnResponse.of("97", "Invalid signature");
        }
        try {
            VnpayIpnResponse response = new TransactionTemplate(transactionManager)
                    .execute(status -> processVerifiedIpn(callbackParameters));
            return response == null ? VnpayIpnResponse.of("99", "Unknown error") : response;
        } catch (RuntimeException ex) {
            // Không nuốt lỗi âm thầm: log stacktrace để còn debug được khi IPN lỗi ở production.
            log.error("VNPAY IPN processing failed for txnRef={}",
                    callbackParameters.get("vnp_TxnRef"), ex);
            return VnpayIpnResponse.of("99", "Unknown error");
        }
    }

    @Transactional
    public URI buildReturnRedirect(Map<String, String> callbackParameters) {
        String result = "invalid";
        Integer paymentId = null;

        if (isCallbackAuthentic(callbackParameters)) {
            String merchantTxnRef = callbackParameters.get("vnp_TxnRef");
            PaymentTransaction attempt = paymentTransactionRepository
                    .findByProviderAndMerchantTxnRef(PROVIDER, merchantTxnRef)
                    .orElse(null);
            if (attempt != null) {
                paymentId = attempt.getPayment().getId();
                if (isSuccessfulCallback(callbackParameters)) {
                    result = "success";
                } else if (isCancelledCallback(callbackParameters)) {
                    // Khách chủ động bấm hủy trên cổng VNPAY -> không phải lỗi, cho phép thanh toán lại.
                    result = "cancelled";
                } else {
                    result = "failed";
                }

                // Fallback settle qua return URL — CHỈ dùng khi test local (IPN không tới được localhost).
                // Production phải tắt (return-fallback-enabled=false, mặc định) — chỉ settle qua IPN.
                if (properties.isReturnFallbackEnabled()
                        && attempt.getStatus() == PaymentTransactionStatus.PENDING) {
                    try {
                        VnpayIpnResponse fallbackResponse = processVerifiedIpn(callbackParameters);
                        if (!"00".equals(fallbackResponse.rspCode())) {
                            log.warn("Fallback IPN failed: code={}, message={}",
                                    fallbackResponse.rspCode(), fallbackResponse.message());
                        }
                    } catch (Exception e) {
                        log.error("Fallback IPN processing error", e);
                    }
                }
            }
        }

        UriComponentsBuilder redirect = UriComponentsBuilder
                .fromUriString(properties.getFrontendResultUrl())
                .queryParam("result", result);
        if (paymentId != null) {
            redirect.queryParam("paymentId", paymentId);
        }
        return redirect.build().encode().toUri();
    }

    private VnpayIpnResponse processVerifiedIpn(Map<String, String> callbackParameters) {
        String merchantTxnRef = callbackParameters.get("vnp_TxnRef");
        PaymentTransaction attempt = paymentTransactionRepository
                .findByProviderAndMerchantTxnRef(PROVIDER, merchantTxnRef)
                .orElse(null);
        if (attempt == null) {
            return VnpayIpnResponse.of("01", "Order not found");
        }

        // Khóa theo thứ tự NHẤT QUÁN với BookingService/PaymentService: BOOKING trước, PAYMENT sau
        // -> IPN (do server VNPAY gọi) không đua chéo với thao tác hủy/xác nhận đồng thời của khách/staff.
        Booking booking = bookingRepository.findDetailedByIdForUpdate(attempt.getPayment().getBooking().getId())
                .orElse(null);
        if (booking == null) {
            return VnpayIpnResponse.of("01", "Order not found");
        }
        Payment payment = paymentRepository.findDetailedByIdForUpdate(attempt.getPayment().getId())
                .orElse(null);
        if (payment == null) {
            return VnpayIpnResponse.of("01", "Order not found");
        }
        if (!amountMatches(payment.getAmount(), callbackParameters.get("vnp_Amount"))) {
            return VnpayIpnResponse.of("04", "Invalid amount");
        }
        if (attempt.getStatus() != PaymentTransactionStatus.PENDING
                || payment.getStatus() != PaymentStatus.PENDING) {
            // Trường hợp nguy hiểm cần đối soát: VNPAY báo GIAO DỊCH THÀNH CÔNG (đã trừ tiền khách)
            // nhưng phía mình payment không còn PENDING và cũng CHƯA phải PAID
            // (vd đơn bị hủy / hết hạn trước khi IPN tới) -> tiền treo, cần hoàn tiền/đối soát thủ công.
            // Đã PAID thì đây chỉ là IPN trùng lặp bình thường -> bỏ qua.
            if (isSuccessfulCallback(callbackParameters)
                    && payment.getStatus() != PaymentStatus.PAID) {
                log.error("VNPAY RECONCILIATION NEEDED: success IPN but payment is not settleable. "
                                + "paymentId={}, paymentStatus={}, bookingId={}, providerTxnId={}, amount={}",
                        payment.getId(), payment.getStatus(), booking.getId(),
                        callbackParameters.get("vnp_TransactionNo"),
                        callbackParameters.get("vnp_Amount"));
            }
            return VnpayIpnResponse.of("02", "Order already confirmed");
        }

        String providerTxnId = callbackParameters.get("vnp_TransactionNo");
        if (!isBlank(providerTxnId)) {
            PaymentTransaction duplicate = paymentTransactionRepository
                    .findByProviderAndProviderTxnId(PROVIDER, providerTxnId)
                    .orElse(null);
            if (duplicate != null && !duplicate.getId().equals(attempt.getId())) {
                return VnpayIpnResponse.of("02", "Order already confirmed");
            }
        }

        attempt.setProviderTxnId(providerTxnId);
        attempt.setRawResponse(toSanitizedJson(callbackParameters));

        if (isSuccessfulCallback(callbackParameters)) {
            Invoice invoice = paymentSettlementService.settle(
                    payment, booking, attempt, PaymentMethod.VNPAY, OffsetDateTime.now());
            if (invoice == null) {
                throw new IllegalStateException("Invoice was not created");
            }
            cancelOtherPendingAttempts(payment.getId(), attempt.getId());
        } else {
            attempt.setStatus(isCancelledCallback(callbackParameters)
                    ? PaymentTransactionStatus.CANCELLED
                    : PaymentTransactionStatus.FAILED);
        }
        paymentTransactionRepository.flush();
        return VnpayIpnResponse.of("00", "Confirm Success");
    }

    private void cancelOtherPendingAttempts(Integer paymentId, Integer successfulAttemptId) {
        List<PaymentTransaction> pendingAttempts = paymentTransactionRepository
                .findByPaymentIdAndStatus(paymentId, PaymentTransactionStatus.PENDING);
        for (PaymentTransaction pendingAttempt : pendingAttempts) {
            if (!pendingAttempt.getId().equals(successfulAttemptId)) {
                pendingAttempt.setStatus(PaymentTransactionStatus.CANCELLED);
            }
        }
    }

    private Map<String, String> buildPaymentParameters(
            Payment payment,
            Booking booking,
            PaymentTransaction attempt,
            String clientIp) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("vnp_Version", "2.1.0");
        parameters.put("vnp_Command", "pay");
        parameters.put("vnp_TmnCode", properties.getTmnCode());
        parameters.put("vnp_Amount", toVnpayAmount(payment.getAmount()));
        parameters.put("vnp_CurrCode", "VND");
        parameters.put("vnp_TxnRef", attempt.getMerchantTxnRef());
        parameters.put("vnp_OrderInfo", "Thanh toan booking " + booking.getBookingCode());
        parameters.put("vnp_OrderType", "other");
        parameters.put("vnp_Locale", "vn");
        parameters.put("vnp_ReturnUrl", properties.getReturnUrl());
        String ipAddr = isBlank(clientIp) ? "127.0.0.1" : clientIp;
        if ("0:0:0:0:0:0:0:1".equals(ipAddr) || "::1".equals(ipAddr)) {
            ipAddr = "127.0.0.1";
        }
        parameters.put("vnp_IpAddr", ipAddr);
        parameters.put("vnp_CreateDate", formatVnpayDate(attempt.getCreatedAt()));
        parameters.put("vnp_ExpireDate", formatVnpayDate(attempt.getExpiresAt()));
        return parameters;
    }

    private boolean isCallbackAuthentic(Map<String, String> callbackParameters) {
        if (!properties.isEnabled()
                || !properties.getTmnCode().equals(callbackParameters.get("vnp_TmnCode"))) {
            return false;
        }
        return signer.verify(
                signatureParameters(callbackParameters),
                callbackParameters.get("vnp_SecureHash"),
                properties.getHashSecret());
    }

    private Map<String, String> signatureParameters(Map<String, String> callbackParameters) {
        Map<String, String> parameters = new TreeMap<>();
        callbackParameters.forEach((key, value) -> {
            if (key.startsWith("vnp_")
                    && !key.equals("vnp_SecureHash")
                    && !key.equals("vnp_SecureHashType")) {
                parameters.put(key, value);
            }
        });
        return parameters;
    }

    private String toSanitizedJson(Map<String, String> callbackParameters) {
        try {
            return objectMapper.writeValueAsString(signatureParameters(callbackParameters));
        } catch (JacksonException ex) {
            throw new IllegalStateException("Cannot serialize VNPAY response", ex);
        }
    }

    private boolean amountMatches(BigDecimal expected, String vnpayAmount) {
        try {
            BigDecimal received = BigDecimal.valueOf(Long.parseLong(vnpayAmount), 2);
            return expected.compareTo(received) == 0;
        } catch (NumberFormatException | NullPointerException ex) {
            return false;
        }
    }

    private String toVnpayAmount(BigDecimal amount) {
        try {
            return amount.movePointRight(2).longValueExact() + "";
        } catch (ArithmeticException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "Payment amount is not valid for VNPAY");
        }
    }

    private boolean isSuccessfulCallback(Map<String, String> callbackParameters) {
        return "00".equals(callbackParameters.get("vnp_ResponseCode"))
                && "00".equals(callbackParameters.get("vnp_TransactionStatus"));
    }

    // VNPAY mã 24 = khách hàng hủy giao dịch.
    private boolean isCancelledCallback(Map<String, String> callbackParameters) {
        return "24".equals(callbackParameters.get("vnp_ResponseCode"));
    }

    private String generateMerchantTxnRef(Integer paymentId, OffsetDateTime now) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "P" + paymentId + formatVnpayDate(now) + suffix;
    }

    private String formatVnpayDate(OffsetDateTime value) {
        return value.atZoneSameInstant(VIETNAM_ZONE).format(VNPAY_DATE_FORMAT);
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VNPAY payment is disabled");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
