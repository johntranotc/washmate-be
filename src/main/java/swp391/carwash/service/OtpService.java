package swp391.carwash.service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.entity.OtpCode;
import swp391.carwash.enums.OtpChannel;
import swp391.carwash.repository.AppUserRepository;
import swp391.carwash.repository.OtpCodeRepository;
import swp391.carwash.service.otp.OtpSender;

@Service
@RequiredArgsConstructor
public class OtpService {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[0-9+]{9,20}$");

    private final OtpCodeRepository otpCodeRepository;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpSender otpSender;
    private final PlatformTransactionManager transactionManager;

    @Value("${washmate.security.otp.mock-code:}")
    private String mockOtp;

    @Value("${washmate.security.otp.expose-mock-code:false}")
    private boolean exposeMockOtp;

    @Value("${washmate.security.otp.ttl-minutes:5}")
    private long otpTtlMinutes;

    @Value("${washmate.security.otp.max-attempts:5}")
    private int otpMaxAttempts;

    @Value("${washmate.security.otp.resend-cooldown-seconds:60}")
    private long otpResendCooldownSeconds;

    @Transactional
    public void requestOtp(String email) {
        // 1. Cooldown check & Get existing OTP
        OtpCode otpCode = otpCodeRepository.findByChannelAndIdentifier(OtpChannel.EMAIL, email).orElse(null);
        if (otpCode != null) {
            long secondsSince = java.time.temporal.ChronoUnit.SECONDS.between(
                otpCode.getCreatedAt(), OffsetDateTime.now());
            if (secondsSince < otpResendCooldownSeconds) {
                throw new swp391.carwash.common.exception.TooManyRequestsException("Vui lòng chờ 60 giây trước khi gửi lại");
            }
        } else {
            otpCode = OtpCode.builder()
                .channel(OtpChannel.EMAIL)
                .identifier(email)
                .build();
        }

        // 2. Tạo mã
        String rawOtp = isMock() ? mockOtp : generateSecureOtp();

        // 3. Cập nhật và lưu DB
        otpCode.setCode(passwordEncoder.encode(rawOtp));
        otpCode.setExpiresAt(OffsetDateTime.now().plusMinutes(otpTtlMinutes));
        otpCode.setFailedAttempts(0);
        otpCode.setCreatedAt(OffsetDateTime.now());
        otpCodeRepository.save(otpCode);

        // 4. Gửi — mock mode thì không gửi thật
        if (!isMock()) {
            otpSender.send(email, rawOtp);
        }
    }

    @Transactional
    public String verifyOtp(String rawIdentifier, String submittedOtp) {
        String email = resolveEmailIdentifier(rawIdentifier);
        OffsetDateTime now = OffsetDateTime.now();
        OtpCode expected = otpCodeRepository.findByChannelAndIdentifier(OtpChannel.EMAIL, email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Mã OTP không tồn tại hoặc đã bị thu hồi"));
        if (expected.getExpiresAt().isBefore(now)) {
            otpCodeRepository.delete(expected);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Mã OTP đã hết hạn");
        }
        if (!passwordEncoder.matches(submittedOtp, expected.getCode())) {
            // Ghi số lần sai trong transaction RIÊNG (REQUIRES_NEW) để KHÔNG bị rollback khi throw,
            // nếu không giới hạn số lần thử OTP sẽ vô tác dụng (brute-force 6 số).
            recordOtpFailure(expected.getId());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Mã OTP không chính xác");
        }
        otpCodeRepository.delete(expected);
        return email;
    }

    public String resolveEmailIdentifier(String rawIdentifier) {
        if (!StringUtils.hasText(rawIdentifier)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        String identifier = rawIdentifier.trim();
        if (EMAIL_PATTERN.matcher(identifier).matches()) {
            return identifier.toLowerCase(Locale.ROOT);
        }
        if (PHONE_PATTERN.matcher(identifier).matches()) {
            return appUserRepository.findByPhone(identifier)
                    .map(user -> {
                        if (!StringUtils.hasText(user.getEmail())) {
                            throw new ApiException(HttpStatus.BAD_REQUEST, "Email is required");
                        }
                        return user.getEmail().toLowerCase(Locale.ROOT);
                    })
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Email is required"));
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "Email is invalid");
    }

    private void recordOtpFailure(Integer otpId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> {
            OtpCode fresh = otpCodeRepository.findById(otpId).orElse(null);
            if (fresh == null) {
                return;
            }
            int attempts = fresh.getFailedAttempts() == null ? 1 : fresh.getFailedAttempts() + 1;
            fresh.setFailedAttempts(attempts);
            if (attempts >= otpMaxAttempts) {
                otpCodeRepository.delete(fresh); // hết lượt thử -> vô hiệu mã
            } else {
                otpCodeRepository.save(fresh);
            }
        });
    }

    private String generateSecureOtp() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }

    private boolean isMock() {
        return mockOtp != null && !mockOtp.isBlank();
    }
}
