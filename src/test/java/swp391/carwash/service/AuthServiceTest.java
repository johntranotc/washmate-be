package swp391.carwash.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.dto.AuthResponse;
import swp391.carwash.dto.GoogleLoginRequest;
import swp391.carwash.dto.LoginRequest;
import swp391.carwash.dto.OtpResponse;
import swp391.carwash.dto.OtpVerifyRequest;
import swp391.carwash.dto.RegisterRequest;
import swp391.carwash.dto.ResetPasswordRequest;
import swp391.carwash.entity.AppUser;
import swp391.carwash.entity.Role;
import swp391.carwash.entity.UserRole;
import swp391.carwash.enums.AuthProvider;
import swp391.carwash.enums.RecordStatus;
import swp391.carwash.enums.RoleName;
import swp391.carwash.enums.UserStatus;
import swp391.carwash.repository.AppUserRepository;
import swp391.carwash.repository.RoleRepository;
import swp391.carwash.repository.UserRoleRepository;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private OtpService otpService;
    @Mock
    private TokenService tokenService;
    @Mock
    private GoogleAuthService googleAuthService;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(appUserRepository, roleRepository, userRoleRepository,
                passwordEncoder, otpService, tokenService, googleAuthService, transactionManager);
        org.mockito.Mockito.lenient()
                .when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        ReflectionTestUtils.setField(authService, "loginMaxFailedAttempts", 3);
        ReflectionTestUtils.setField(authService, "loginLockMinutes", 15L);
    }

    @Test
    void registerCreatesPendingUserAndRequestsEmailOtp() {
        RegisterRequest request = new RegisterRequest("USER@example.com", "secret123", "User", "0911111111", null, null);
        when(passwordEncoder.encode("secret123")).thenReturn("hash");
        when(roleRepository.findByRoleName(RoleName.CUSTOMER)).thenReturn(Optional.of(Role.builder()
                .id(1)
                .roleName(RoleName.CUSTOMER)
                .status(RecordStatus.ACTIVE)
                .build()));


        OtpResponse response = authService.register(request);

        assertEquals("user@example.com", response.identifier());
        verify(appUserRepository).save(org.mockito.ArgumentMatchers.argThat(user ->
                user.getStatus() == UserStatus.PENDING_VERIFY
                        && user.getEmail().equals("user@example.com")
                        && user.getPasswordHash().equals("hash")));
        verify(otpService).requestOtp("user@example.com");
    }

    @Test
    void registerRejectsRequestedRole() {
        RegisterRequest request = new RegisterRequest(
                "user@example.com",
                "secret123",
                "User",
                "0911111111",
                "ADMIN",
                List.of("STAFF"));

        ApiException exception = assertThrows(ApiException.class, () -> authService.register(request));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("Đăng ký công khai chỉ cho phép với vai trò CUSTOMER", exception.getMessage());
    }

    @Test
    void loginRejectsPendingVerificationUser() {
        AppUser user = AppUser.builder()
                .id(10)
                .email("user@example.com")
                .passwordHash("hash")
                .status(UserStatus.PENDING_VERIFY)
                .failedLoginCount(0)
                .build();
        when(appUserRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123", "hash")).thenReturn(true);

        ApiException exception = assertThrows(ApiException.class,
                () -> authService.login(new LoginRequest("user@example.com", null, "secret123")));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertEquals("Tài khoản cần được xác thực", exception.getMessage());
    }

    @Test
    void loginWithEmailOrPhoneIssuesTokensForActiveUser() {
        AppUser user = AppUser.builder()
                .id(10)
                .email("user@example.com")
                .phone("0911111111")
                .passwordHash("hash")
                .status(UserStatus.ACTIVE)
                .failedLoginCount(2)
                .build();
        AuthResponse expected = new AuthResponse("access", "refresh", "Bearer", 3600, null);
        when(appUserRepository.findByPhone("0911111111")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123", "hash")).thenReturn(true);
        when(tokenService.issueTokens(10)).thenReturn(expected);

        AuthResponse response = authService.login(new LoginRequest("0911111111", null, "secret123"));

        assertEquals(expected, response);
        assertEquals(0, user.getFailedLoginCount());
        verify(tokenService).issueTokens(10);
    }

    @Test
    void verifyOtpActivatesPendingUserAndIssuesTokens() {
        AppUser user = AppUser.builder()
                .id(10)
                .email("user@example.com")
                .status(UserStatus.PENDING_VERIFY)
                .build();
        AuthResponse expected = new AuthResponse("access", "refresh", "Bearer", 3600, null);
        when(otpService.verifyOtp("user@example.com", "123456")).thenReturn("user@example.com");
        when(appUserRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(tokenService.issueTokens(10)).thenReturn(expected);

        AuthResponse response = authService.verifyOtp(new OtpVerifyRequest("user@example.com", null, null, "123456", null));

        assertEquals(expected, response);
        assertEquals(UserStatus.ACTIVE, user.getStatus());
    }

    @Test
    void googleLoginActivatesPendingUserAndIssuesTokens() {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setEmail("USER@example.com");
        payload.setEmailVerified(true);
        AppUser user = AppUser.builder()
                .id(10)
                .email("user@example.com")
                .status(UserStatus.PENDING_VERIFY)
                .failedLoginCount(1)
                .build();
        AuthResponse expected = new AuthResponse("access", "refresh", "Bearer", 3600, null);
        when(googleAuthService.verifyIdToken("google-id-token")).thenReturn(payload);
        when(appUserRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(tokenService.issueTokens(10)).thenReturn(expected);

        AuthResponse response = authService.loginWithGoogle(new GoogleLoginRequest("google-id-token"));

        assertEquals(expected, response);
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertEquals(0, user.getFailedLoginCount());
        org.junit.jupiter.api.Assertions.assertNotNull(user.getLastLoginAt());
        verify(appUserRepository).save(user);
    }

    @Test
    void failedLoginLocksAccountAfterConfiguredAttempts() {
        AppUser user = AppUser.builder()
                .id(10)
                .email("user@example.com")
                .passwordHash("hash")
                .status(UserStatus.ACTIVE)
                .failedLoginCount(2)
                .build();
        when(appUserRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(appUserRepository.findById(10)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        ApiException exception = assertThrows(ApiException.class,
                () -> authService.login(new LoginRequest("user@example.com", null, "wrong")));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        assertEquals(3, user.getFailedLoginCount());
        org.junit.jupiter.api.Assertions.assertNotNull(user.getLockedUntil());
    }

    @Test
    void loginRejectsWhileAccountStillLocked() {
        AppUser user = AppUser.builder()
                .id(10)
                .email("user@example.com")
                .passwordHash("hash")
                .status(UserStatus.ACTIVE)
                .failedLoginCount(3)
                .lockedUntil(java.time.OffsetDateTime.now().plusMinutes(10))
                .build();
        when(appUserRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));

        ApiException exception = assertThrows(ApiException.class,
                () -> authService.login(new LoginRequest("user@example.com", null, "secret123")));

        assertEquals(HttpStatus.LOCKED, exception.getStatus());
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void loginRejectsSqlInjectionIdentifierWithoutPasswordCheck() {
        String payload = "admin@example.com' OR '1'='1";
        when(appUserRepository.findByEmailIgnoreCase(payload.toLowerCase(Locale.ROOT))).thenReturn(Optional.empty());

        ApiException exception = assertThrows(ApiException.class,
                () -> authService.login(new LoginRequest(payload, null, "anything")));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void googleLoginLinksExistingLocalAccountWithoutDuplicatingRole() {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setEmail("USER@example.com");
        payload.setEmailVerified(true);
        AppUser localUser = AppUser.builder()
                .id(10)
                .email("user@example.com")
                .fullName("Local User")
                .passwordHash("hash")
                .provider(AuthProvider.LOCAL)
                .status(UserStatus.ACTIVE)
                .failedLoginCount(2)
                .build();
        AuthResponse expected = new AuthResponse("access", "refresh", "Bearer", 3600, null);
        when(googleAuthService.verifyIdToken("google-id-token")).thenReturn(payload);
        when(appUserRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(localUser));
        when(tokenService.issueTokens(10)).thenReturn(expected);

        AuthResponse response = authService.loginWithGoogle(new GoogleLoginRequest("google-id-token"));

        assertEquals(expected, response);
        assertEquals(AuthProvider.LOCAL, localUser.getProvider());
        assertEquals(0, localUser.getFailedLoginCount());
        assertNotNull(localUser.getLastLoginAt());
        verify(appUserRepository).save(localUser);
        verify(userRoleRepository, never()).save(any(UserRole.class));
    }

    @Test
    void googleLoginRejectsInvalidIdToken() {
        when(googleAuthService.verifyIdToken("fake-token"))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "Invalid Google ID token"));

        ApiException exception = assertThrows(ApiException.class,
                () -> authService.loginWithGoogle(new GoogleLoginRequest("fake-token")));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        verify(appUserRepository, never()).findByEmailIgnoreCase(any());
    }

    // ── Lockout: reset counter, không cho bypass, gỡ khóa hợp lệ ──────────────

    /**
     * Hết hạn khóa thì counter phải đếm lại từ đầu. Trước đây counter chỉ reset khi
     * đăng nhập THÀNH CÔNG, nên sau lần khóa đầu tiên user chỉ cần sai 1 lần là bị
     * khóa lại ngay, không bao giờ được thử đủ số lượt cho phép.
     */
    @Test
    void failedLoginRestartsCounterAfterLockWindowExpired() {
        AppUser user = AppUser.builder()
                .id(10)
                .email("user@example.com")
                .passwordHash("hash")
                .status(UserStatus.ACTIVE)
                .failedLoginCount(3)
                .lockedUntil(java.time.OffsetDateTime.now().minusMinutes(1))
                .build();
        when(appUserRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(appUserRepository.findById(10)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        ApiException exception = assertThrows(ApiException.class,
                () -> authService.login(new LoginRequest("user@example.com", null, "wrong")));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        assertEquals(1, user.getFailedLoginCount());
        org.junit.jupiter.api.Assertions.assertNull(user.getLockedUntil());
    }

    /** Tài khoản đang bị khóa thì OTP cũng không được cấp token — nếu không thì lockout bị bypass. */
    @Test
    void verifyOtpRejectsWhileAccountStillLocked() {
        AppUser user = AppUser.builder()
                .id(10)
                .email("user@example.com")
                .status(UserStatus.ACTIVE)
                .failedLoginCount(3)
                .lockedUntil(java.time.OffsetDateTime.now().plusMinutes(10))
                .build();
        when(otpService.verifyOtp("user@example.com", "123456")).thenReturn("user@example.com");
        when(appUserRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));

        ApiException exception = assertThrows(ApiException.class,
                () -> authService.verifyOtp(
                        new OtpVerifyRequest("user@example.com", null, null, "123456", null)));

        assertEquals(HttpStatus.LOCKED, exception.getStatus());
        verify(tokenService, never()).issueTokens(any());
    }

    /** Đặt lại mật khẩu qua OTP là đường thoát hợp lệ khỏi lockout -> phải gỡ khóa. */
    @Test
    void resetPasswordClearsLoginLock() {
        AppUser user = AppUser.builder()
                .id(10)
                .email("user@example.com")
                .passwordHash("old-hash")
                .status(UserStatus.ACTIVE)
                .failedLoginCount(3)
                .lockedUntil(java.time.OffsetDateTime.now().plusMinutes(10))
                .build();
        AuthResponse expected = new AuthResponse("access", "refresh", "Bearer", 3600, null);
        when(otpService.verifyOtp("user@example.com", "123456")).thenReturn("user@example.com");
        when(appUserRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newsecret123")).thenReturn("new-hash");
        when(tokenService.issueTokens(10)).thenReturn(expected);

        AuthResponse response = authService.resetPassword(
                new ResetPasswordRequest("user@example.com", "123456", "newsecret123"));

        assertEquals(expected, response);
        assertEquals("new-hash", user.getPasswordHash());
        assertEquals(0, user.getFailedLoginCount());
        org.junit.jupiter.api.Assertions.assertNull(user.getLockedUntil());
    }

    @Test
    void googleLoginRejectsUnverifiedEmail() {
        when(googleAuthService.verifyIdToken("unverified-token"))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "Google email is not verified"));

        ApiException exception = assertThrows(ApiException.class,
                () -> authService.loginWithGoogle(new GoogleLoginRequest("unverified-token")));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        verify(appUserRepository, never()).findByEmailIgnoreCase(any());
    }
}
