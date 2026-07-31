package swp391.carwash.service;

import java.time.OffsetDateTime;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import swp391.carwash.dto.*;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.entity.AppUser;
import swp391.carwash.entity.Role;
import swp391.carwash.entity.UserRole;
import swp391.carwash.enums.RecordStatus;
import swp391.carwash.enums.RoleName;
import swp391.carwash.enums.UserStatus;
import swp391.carwash.enums.AuthProvider;
import swp391.carwash.repository.AppUserRepository;
import swp391.carwash.repository.RoleRepository;
import swp391.carwash.repository.UserRoleRepository;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;

@Service
@RequiredArgsConstructor
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AppUserRepository appUserRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;
    private final TokenService tokenService;
    private final GoogleAuthService googleAuthService;
    private final PlatformTransactionManager transactionManager;

    @Value("${washmate.security.login.max-failed-attempts:5}")
    private int loginMaxFailedAttempts;

    @Value("${washmate.security.login.lock-minutes:15}")
    private long loginLockMinutes;

    @Transactional
    public OtpResponse register(RegisterRequest request) {
        if (request.hasRequestedRole()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Đăng ký công khai chỉ cho phép với vai trò CUSTOMER");
        }

        String email = normalizeEmail(request.email());
        AppUser existingUser = appUserRepository.findByEmailIgnoreCase(email).orElse(null);

        if (existingUser != null) {
            if (existingUser.getStatus() == UserStatus.ACTIVE) {
                throw new ApiException(HttpStatus.CONFLICT, "Email đã được sử dụng");
            } else if (existingUser.getStatus() == UserStatus.PENDING_VERIFY) {
                // Check if the requested phone belongs to another user
                AppUser phoneUser = appUserRepository.findByPhone(request.phone()).orElse(null);
                if (phoneUser != null && !phoneUser.getId().equals(existingUser.getId())) {
                    if (phoneUser.getStatus() == UserStatus.ACTIVE) {
                        throw new ApiException(HttpStatus.CONFLICT, "Số điện thoại đã được sử dụng");
                    } else {
                        throw new ApiException(HttpStatus.CONFLICT, "Số điện thoại đang chờ xác thực bởi tài khoản khác");
                    }
                }

                // Cho phép ghi đè thông tin nếu tài khoản hiện tại chưa được xác thực
                existingUser.setPasswordHash(passwordEncoder.encode(request.password()));
                existingUser.setFullName(request.fullName());
                existingUser.setPhone(request.phone());
                appUserRepository.save(existingUser);
                
                otpService.requestOtp(email);
                log.info("Re-registration for pending account: email={}", email);
                return new OtpResponse(email, null, "Đã gửi lại mã OTP");
            }
        }

        // Logic cho user hoàn toàn mới
        if (appUserRepository.existsByPhone(request.phone())) {
            throw new ApiException(HttpStatus.CONFLICT, "Số điện thoại đã được sử dụng");
        }
        
        AppUser user = AppUser.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .phone(request.phone())
                .status(UserStatus.PENDING_VERIFY)
                .build();
        appUserRepository.save(user);
        assignRole(user, RoleName.CUSTOMER);
        otpService.requestOtp(email);
        
        log.info("New user registered: email={}, userId={}", email, user.getId());
        return new OtpResponse(email, null, "Đã gửi mã OTP");
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        AppUser user = findUserByIdentifier(request.identifier())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Tài khoản hoặc mật khẩu không chính xác"));
        OffsetDateTime now = OffsetDateTime.now();

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            log.warn("Login attempt on locked account: userId={}", user.getId());
            throw new ApiException(HttpStatus.LOCKED, "Tài khoản đang bị tạm khóa");
        }

        if (!StringUtils.hasText(user.getPasswordHash()) || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // Ghi counter trong transaction RIÊNG (REQUIRES_NEW) để KHÔNG bị rollback khi throw bên dưới,
            // nếu không cơ chế khóa tài khoản sau N lần sai sẽ vô tác dụng.
            recordFailedLogin(user.getId(), now);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Tài khoản hoặc mật khẩu không chính xác");
        }

        ensureActiveForLogin(user);
        clearLoginLock(user);
        user.setLastLoginAt(now);
        appUserRepository.save(user);
        log.info("Login successful: userId={}", user.getId());
        return tokenService.issueTokens(user.getId());
    }

    @Transactional
    public AuthResponse loginWithGoogle(GoogleLoginRequest request) {
        GoogleIdToken.Payload payload = googleAuthService.verifyIdToken(request.getIdToken());
        String email = normalizeEmail(payload.getEmail());
        
        AppUser user = appUserRepository.findByEmailIgnoreCase(email).orElse(null);
        OffsetDateTime now = OffsetDateTime.now();

        if (user == null) {
            // Đăng ký mới qua Google
            String name = (String) payload.get("name");
            if (name == null || name.isBlank()) name = email.split("@")[0];

            user = AppUser.builder()
                    .email(email)
                    .fullName(name)
                    .status(UserStatus.ACTIVE)
                    .provider(AuthProvider.GOOGLE)
                    .lastLoginAt(now)
                    .passwordHash("") // Không có password
                    .build();
            appUserRepository.save(user);
            assignRole(user, RoleName.CUSTOMER);
            log.info("New Google user registered: email={}, userId={}", email, user.getId());
        } else {
            // User đã tồn tại
            if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
                throw new ApiException(HttpStatus.LOCKED, "Tài khoản đang bị tạm khóa");
            }
            if (user.getStatus() == UserStatus.PENDING_VERIFY) {
                user.setStatus(UserStatus.ACTIVE);
            }
            ensureActive(user);

            clearLoginLock(user);
            user.setLastLoginAt(now);
            appUserRepository.save(user);
            log.info("Google login successful: userId={}", user.getId());
        }

        return tokenService.issueTokens(user.getId());
    }

    @Transactional
    public OtpResponse requestOtp(OtpRequest request) {
        String email = otpService.resolveEmailIdentifier(request.identifier());
        otpService.requestOtp(email);
        return new OtpResponse(email, null, "Đã gửi mã xác thực (OTP)");
    }

    @Transactional
    public AuthResponse verifyOtp(OtpVerifyRequest request) {
        String email = otpService.verifyOtp(request.identifier(), request.otp());
        AppUser user = appUserRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Tài khoản không tồn tại hoặc chưa đăng ký"));

        // Đây cũng là một đường CẤP TOKEN, nên phải tôn trọng khóa đăng nhập giống
        // login() và loginWithGoogle() — nếu không, brute-force bị khóa vẫn vào được qua OTP.
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(OffsetDateTime.now())) {
            log.warn("OTP verify attempt on locked account: userId={}", user.getId());
            throw new ApiException(HttpStatus.LOCKED, "Tài khoản đang bị tạm khóa");
        }

        if (user.getStatus() == UserStatus.PENDING_VERIFY) {
            user.setStatus(UserStatus.ACTIVE);
            appUserRepository.save(user);
            log.info("Account verified via OTP: userId={}, email={}", user.getId(), email);
        }
        ensureActive(user);
        return tokenService.issueTokens(user.getId());
    }

    public AuthResponse refresh(RefreshTokenRequest request) {
        return tokenService.rotateRefreshToken(request.refreshToken());
    }

    public void logout(RefreshTokenRequest request) {
        tokenService.revokeRefreshToken(request.refreshToken());
    }

    // KHÔNG @Transactional: cần nuốt lỗi từ otpService.requestOtp (cooldown, không có email...)
    // mà không làm hỏng transaction ngoài; requestOtp tự quản transaction riêng.
    public OtpResponse forgotPassword(OtpRequest request) {
        // Chống dò tài khoản (user enumeration): luôn trả cùng một thông báo, bất kể
        // tài khoản có tồn tại/đang hoạt động hay không, và không lộ nguyên nhân lỗi.
        String genericMessage = "Nếu tài khoản tồn tại, mã xác thực (OTP) đã được gửi tới email.";
        try {
            AppUser user = findUserByIdentifier(request.identifier()).orElse(null);
            if (user != null && user.getStatus() == UserStatus.ACTIVE) {
                String email = otpService.resolveEmailIdentifier(request.identifier());
                otpService.requestOtp(email);
                log.info("Forgot password OTP requested: userId={}", user.getId());
            } else {
                log.info("Forgot password requested for non-existent/inactive account");
            }
        } catch (Exception ex) {
            // Không để lộ nguyên nhân (email không hợp lệ, cooldown, không có email...) ra ngoài.
            log.warn("Forgot password processing issue: {}", ex.getMessage());
        }
        return new OtpResponse(responseEmailFor(request.identifier()), null, genericMessage);
    }

    // Email hiển thị lại trong response chỉ suy ra từ CHÍNH input người dùng nhập (không tra DB),
    // nên không tiết lộ tài khoản có tồn tại hay không.
    private String responseEmailFor(String identifier) {
        if (identifier == null) {
            return null;
        }
        String trimmed = identifier.trim();
        return trimmed.contains("@") ? trimmed.toLowerCase(Locale.ROOT) : null;
    }

    @Transactional
    public AuthResponse resetPassword(ResetPasswordRequest request) {
        String email = otpService.verifyOtp(request.identifier(), request.otp());
        AppUser user = appUserRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Tài khoản không tồn tại"));
        ensureActive(user);
        
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        // Đặt lại mật khẩu qua OTP là đường thoát hợp lệ khỏi lockout — phải gỡ khóa,
        // nếu không user vẫn ăn 423 ở lần login kế tiếp dù mật khẩu mới đã đúng.
        clearLoginLock(user);
        appUserRepository.save(user);
        tokenService.revokeAllTokensForUser(user.getId());

        log.info("Password reset successful: userId={}", user.getId());
        return tokenService.issueTokens(user.getId());
    }

    @Transactional
    public void changePassword(Integer userId, ChangePasswordRequest request) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Tài khoản không tồn tại"));
        ensureActive(user);

        if (user.getProvider() == AuthProvider.GOOGLE && !StringUtils.hasText(user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Tài khoản Google không hỗ trợ đổi mật khẩu");
        }
        
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Mật khẩu cũ không chính xác");
        }
        
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        clearLoginLock(user);
        appUserRepository.save(user);
        tokenService.revokeAllTokensForUser(user.getId());
        log.info("Password changed: userId={}", userId);
    }

    private void assignRole(AppUser user, RoleName roleName) {
        Role role = roleRepository.findByRoleName(roleName)
                .orElseGet(() -> roleRepository.save(Role.builder()
                        .roleName(roleName)
                        .description(roleName.name() + " role")
                        .status(RecordStatus.ACTIVE)
                        .build()));
        userRoleRepository.save(UserRole.builder().user(user).role(role).status(RecordStatus.ACTIVE).build());
    }

    private void ensureActive(AppUser user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Tài khoản chưa được kích hoạt");
        }
    }

    private void ensureActiveForLogin(AppUser user) {
        if (user.getStatus() == UserStatus.PENDING_VERIFY) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Tài khoản cần được xác thực");
        }
        ensureActive(user);
    }

    private java.util.Optional<AppUser> findUserByIdentifier(String rawIdentifier) {
        if (!StringUtils.hasText(rawIdentifier)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Tài khoản hoặc mật khẩu không chính xác");
        }
        String identifier = rawIdentifier.trim();
        if (identifier.contains("@")) {
            return appUserRepository.findByEmailIgnoreCase(identifier.toLowerCase(Locale.ROOT));
        }
        return appUserRepository.findByPhone(identifier);
    }

    private void recordFailedLogin(Integer userId, OffsetDateTime now) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Integer failedCount;
        try {
            failedCount = template.execute(status -> {
                AppUser fresh = appUserRepository.findById(userId).orElse(null);
                if (fresh == null) {
                    return null;
                }
                // Cửa sổ khóa đã hết hạn -> đếm lại từ đầu. Nếu không reset, counter vẫn
                // đứng ở ngưỡng cũ và CHỈ MỘT lần sai kế tiếp là khóa lại ngay,
                // user không bao giờ được thử lại đủ số lượt cho phép.
                boolean lockExpired = fresh.getLockedUntil() != null
                        && !fresh.getLockedUntil().isAfter(now);
                int previous = (lockExpired || fresh.getFailedLoginCount() == null)
                        ? 0
                        : fresh.getFailedLoginCount();
                int count = previous + 1;
                fresh.setFailedLoginCount(count);
                fresh.setLockedUntil(count >= loginMaxFailedAttempts
                        ? now.plusMinutes(loginLockMinutes)
                        : null);
                // Flush ngay trong transaction con để lỗi ghi (constraint, mất kết nối...)
                // nổ ra ở đây thay vì im lặng làm cơ chế khóa mất tác dụng.
                appUserRepository.saveAndFlush(fresh);
                return count;
            });
        } catch (RuntimeException ex) {
            // Không nuốt im lặng: counter không ghi được nghĩa là chống brute-force đang hỏng.
            log.error("Cannot record failed login attempt: userId={}", userId, ex);
            return;
        }
        if (failedCount != null) {
            log.warn("Failed login attempt: userId={}, failedCount={}", userId, failedCount);
            if (failedCount >= loginMaxFailedAttempts) {
                log.warn("Account locked after {} failed attempts: userId={}", failedCount, userId);
            }
        }
    }

    /** Gỡ khóa đăng nhập sau khi người dùng đã chứng minh được quyền sở hữu tài khoản. */
    private void clearLoginLock(AppUser user) {
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
