package swp391.carwash.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
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
import swp391.carwash.entity.OtpCode;
import swp391.carwash.enums.OtpChannel;
import swp391.carwash.repository.AppUserRepository;
import swp391.carwash.repository.OtpCodeRepository;
import swp391.carwash.service.otp.OtpSender;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {
    @Mock
    private OtpCodeRepository otpCodeRepository;
    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private OtpSender otpSender;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    private OtpService otpService;

    @BeforeEach
    void setUp() {
        otpService = new OtpService(
                otpCodeRepository, appUserRepository, passwordEncoder, otpSender, transactionManager);
        org.mockito.Mockito.lenient()
                .when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        ReflectionTestUtils.setField(otpService, "mockOtp", "123456");
        ReflectionTestUtils.setField(otpService, "exposeMockOtp", true);
        ReflectionTestUtils.setField(otpService, "otpTtlMinutes", 5L);
        ReflectionTestUtils.setField(otpService, "otpMaxAttempts", 2);
        ReflectionTestUtils.setField(otpService, "otpResendCooldownSeconds", 60L);
    }

    @Test
    void requestOtpStoresHashAndDoesNotSendEmailInMockMode() {
        when(otpCodeRepository.findByChannelAndIdentifier(OtpChannel.EMAIL, "user@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("123456")).thenReturn("otp-hash");

        otpService.requestOtp("user@example.com");
        verify(otpCodeRepository).save(org.mockito.ArgumentMatchers.argThat(otp ->
                otp.getChannel() == OtpChannel.EMAIL
                        && otp.getIdentifier().equals("user@example.com")
                        && otp.getCode().equals("otp-hash")));
        org.mockito.Mockito.verify(otpSender, org.mockito.Mockito.never()).send(any(), any());
    }

    @Test
    void requestOtpStoresHashAndSendsEmailInRealMode() {
        ReflectionTestUtils.setField(otpService, "mockOtp", "");
        when(otpCodeRepository.findByChannelAndIdentifier(OtpChannel.EMAIL, "user@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any(String.class))).thenReturn("otp-hash");

        otpService.requestOtp("user@example.com");
        verify(otpCodeRepository).save(org.mockito.ArgumentMatchers.argThat(otp ->
                otp.getChannel() == OtpChannel.EMAIL
                        && otp.getIdentifier().equals("user@example.com")
                        && otp.getCode().equals("otp-hash")));
        org.mockito.Mockito.verify(otpSender).send(org.mockito.ArgumentMatchers.eq("user@example.com"), any(String.class));
    }



    @Test
    void verifyOtpDeletesExpiredOtp() {
        OtpCode otp = OtpCode.builder()
                .id(1)
                .channel(OtpChannel.EMAIL)
                .identifier("user@example.com")
                .code("hash")
                .expiresAt(OffsetDateTime.now().minusMinutes(1))
                .createdAt(OffsetDateTime.now().minusMinutes(10))
                .failedAttempts(0)
                .build();
        when(otpCodeRepository.findByChannelAndIdentifier(OtpChannel.EMAIL, "user@example.com")).thenReturn(Optional.of(otp));

        ApiException exception = assertThrows(ApiException.class, () -> otpService.verifyOtp("user@example.com", "123456"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        verify(otpCodeRepository).delete(otp);
    }

    @Test
    void verifyOtpDeletesAfterMaxFailedAttempts() {
        OtpCode otp = OtpCode.builder()
                .id(1)
                .channel(OtpChannel.EMAIL)
                .identifier("user@example.com")
                .code("hash")
                .expiresAt(OffsetDateTime.now().plusMinutes(5))
                .createdAt(OffsetDateTime.now())
                .failedAttempts(1)
                .build();
        when(otpCodeRepository.findByChannelAndIdentifier(OtpChannel.EMAIL, "user@example.com")).thenReturn(Optional.of(otp));
        when(otpCodeRepository.findById(1)).thenReturn(Optional.of(otp));
        when(passwordEncoder.matches("000000", "hash")).thenReturn(false);

        ApiException exception = assertThrows(ApiException.class, () -> otpService.verifyOtp("user@example.com", "000000"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        verify(otpCodeRepository).delete(otp);
    }
}
