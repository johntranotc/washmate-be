package swp391.carwash.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
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
import swp391.carwash.repository.BookingRepository;
import swp391.carwash.repository.InvoiceRepository;
import swp391.carwash.repository.PaymentRepository;
import swp391.carwash.repository.PaymentTransactionRepository;
import swp391.carwash.security.AppUserDetails;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class VnpayServiceTest {
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private AppUserDetails principal;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    private final VnpaySigner signer = new VnpaySigner();
    private VnpayService vnpayService;
    private Payment payment;
    private Booking booking;
    private PaymentTransaction attempt;
    private VnpayProperties properties;

    @BeforeEach
    void setUp() {
        properties = new VnpayProperties();
        properties.setEnabled(true);
        properties.setTmnCode("TESTCODE");
        properties.setHashSecret("test-hash-secret");
        properties.setPayUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        properties.setReturnUrl("https://api.example.com/api/payments/vnpay/return");
        properties.setFrontendResultUrl("https://app.example.com/payment/result");
        properties.setTimeoutMinutes(15);

        PaymentSettlementService settlementService = new PaymentSettlementService(invoiceRepository, org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
        vnpayService = new VnpayService(
                properties,
                signer,
                paymentRepository,
                paymentTransactionRepository,
                settlementService,
                new ObjectMapper(),
                transactionManager,
                bookingRepository);

        booking = swp391.carwash.testutil.TestData.pendingBooking("BKG100");
        payment = swp391.carwash.testutil.TestData.pendingPayment(booking, PaymentMethod.VNPAY);
        attempt = PaymentTransaction.builder()
                .id(300)
                .payment(payment)
                .provider("VNPAY")
                .merchantTxnRef("P200TEST")
                .amount(payment.getAmount())
                .status(PaymentTransactionStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusMinutes(15))
                .build();

        org.mockito.Mockito.lenient()
                .when(bookingRepository.findDetailedByIdForUpdate(100))
                .thenReturn(Optional.of(booking));

        org.mockito.Mockito.lenient()
                .when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
    }

    @Test
    void createPaymentUrlSwitchesCashPaymentToVnpayAndAllowsConfirmedBooking() {
        booking.setStatus(BookingStatus.CONFIRMED);
        payment.setMethod(PaymentMethod.CASH);
        when(principal.getId()).thenReturn(10);
        when(principal.getRoleNames()).thenReturn(List.of("CUSTOMER"));
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
        when(paymentTransactionRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        VnpayPaymentUrlResponse response = vnpayService.createPaymentUrl(200, principal, "127.0.0.1");

        assertEquals(200, response.paymentId());
        assertEquals(PaymentMethod.VNPAY, payment.getMethod());
        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
        assertNotNull(response.expiresAt());
        assertTrue(response.paymentUrl().contains("vnp_Amount=5000000"));
        assertTrue(response.paymentUrl().contains("vnp_SecureHash="));
        assertTrue(response.paymentUrl().contains("vnp_TxnRef=P200"));
        verify(paymentTransactionRepository).save(any(PaymentTransaction.class));
    }

    @Test
    void createPaymentUrlRejectsAnotherCustomer() {
        when(principal.getId()).thenReturn(999);
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));

        ApiException exception = assertThrows(ApiException.class,
                () -> vnpayService.createPaymentUrl(200, principal, "127.0.0.1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    void successfulIpnSettlesPaymentBookingTransactionAndInvoice() {
        Map<String, String> callback = signedCallback("5000000", "00", "00");
        when(paymentTransactionRepository.findByProviderAndMerchantTxnRef("VNPAY", "P200TEST"))
                .thenReturn(Optional.of(attempt));
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
        when(paymentTransactionRepository.findByProviderAndProviderTxnId("VNPAY", "900001"))
                .thenReturn(Optional.empty());
        when(invoiceRepository.findByBookingId(100)).thenReturn(Optional.empty());
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentTransactionRepository.findByPaymentIdAndStatus(200, PaymentTransactionStatus.PENDING))
                .thenReturn(List.of(attempt));

        VnpayIpnResponse response = vnpayService.handleIpn(callback);

        assertEquals("00", response.rspCode());
        assertEquals(PaymentStatus.PAID, payment.getStatus());
        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
        assertEquals(PaymentTransactionStatus.SUCCESS, attempt.getStatus());
        assertEquals("900001", attempt.getProviderTxnId());
        assertNotNull(attempt.getRawResponse());
        verify(invoiceRepository).save(any(Invoice.class));
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void successfulIpnPreservesInProgressBookingStatus() {
        booking.setStatus(BookingStatus.WASHING);
        Map<String, String> callback = signedCallback("5000000", "00", "00");
        when(paymentTransactionRepository.findByProviderAndMerchantTxnRef("VNPAY", "P200TEST"))
                .thenReturn(Optional.of(attempt));
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
        when(paymentTransactionRepository.findByProviderAndProviderTxnId("VNPAY", "900001"))
                .thenReturn(Optional.empty());
        when(invoiceRepository.findByBookingId(100)).thenReturn(Optional.empty());
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentTransactionRepository.findByPaymentIdAndStatus(200, PaymentTransactionStatus.PENDING))
                .thenReturn(List.of(attempt));

        VnpayIpnResponse response = vnpayService.handleIpn(callback);

        assertEquals("00", response.rspCode());
        assertEquals(PaymentStatus.PAID, payment.getStatus());
        assertEquals(BookingStatus.WASHING, booking.getStatus());
        assertEquals(PaymentTransactionStatus.SUCCESS, attempt.getStatus());
    }

    @Test
    void failedIpnKeepsPaymentAndBookingPendingForRetry() {
        Map<String, String> callback = signedCallback("5000000", "24", "02");
        when(paymentTransactionRepository.findByProviderAndMerchantTxnRef("VNPAY", "P200TEST"))
                .thenReturn(Optional.of(attempt));
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
        when(paymentTransactionRepository.findByProviderAndProviderTxnId("VNPAY", "900001"))
                .thenReturn(Optional.empty());

        VnpayIpnResponse response = vnpayService.handleIpn(callback);

        assertEquals("00", response.rspCode());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertEquals(BookingStatus.PENDING, booking.getStatus());
        assertEquals(PaymentTransactionStatus.CANCELLED, attempt.getStatus());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void invalidSignatureReturns97WithoutDatabaseAccess() {
        Map<String, String> callback = signedCallback("5000000", "00", "00");
        callback.put("vnp_SecureHash", "invalid");

        VnpayIpnResponse response = vnpayService.handleIpn(callback);

        assertEquals("97", response.rspCode());
        verify(paymentTransactionRepository, never()).findByProviderAndMerchantTxnRef(any(), any());
    }

    @Test
    void amountMismatchReturns04WithoutSettlement() {
        Map<String, String> callback = signedCallback("4900000", "00", "00");
        when(paymentTransactionRepository.findByProviderAndMerchantTxnRef("VNPAY", "P200TEST"))
                .thenReturn(Optional.of(attempt));
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));

        VnpayIpnResponse response = vnpayService.handleIpn(callback);

        assertEquals("04", response.rspCode());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void duplicateIpnReturns02() {
        attempt.setStatus(PaymentTransactionStatus.SUCCESS);
        payment.setStatus(PaymentStatus.PAID);
        Map<String, String> callback = signedCallback("5000000", "00", "00");
        when(paymentTransactionRepository.findByProviderAndMerchantTxnRef("VNPAY", "P200TEST"))
                .thenReturn(Optional.of(attempt));
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));

        VnpayIpnResponse response = vnpayService.handleIpn(callback);

        assertEquals("02", response.rspCode());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void paymentNotFoundReturns01() {
        Map<String, String> callback = signedCallback("5000000", "00", "00");
        when(paymentTransactionRepository.findByProviderAndMerchantTxnRef("VNPAY", "P200TEST"))
                .thenReturn(Optional.empty());

        VnpayIpnResponse response = vnpayService.handleIpn(callback);

        assertEquals("01", response.rspCode());
    }

    @Test
    void duplicateProviderTxnIdReturns02() {
        Map<String, String> callback = signedCallback("5000000", "00", "00");
        when(paymentTransactionRepository.findByProviderAndMerchantTxnRef("VNPAY", "P200TEST"))
                .thenReturn(Optional.of(attempt));
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
        PaymentTransaction duplicate = PaymentTransaction.builder().id(999).build();
        when(paymentTransactionRepository.findByProviderAndProviderTxnId("VNPAY", "900001"))
                .thenReturn(Optional.of(duplicate));

        VnpayIpnResponse response = vnpayService.handleIpn(callback);

        assertEquals("02", response.rspCode());
        assertEquals("Order already confirmed", response.message());
    }

    @Test
    void returnUrlRedirectsWithoutMutatingPayment() {
        Map<String, String> callback = signedCallback("5000000", "00", "00");
        when(paymentTransactionRepository.findByProviderAndMerchantTxnRef("VNPAY", "P200TEST"))
                .thenReturn(Optional.of(attempt));

        URI redirect = vnpayService.buildReturnRedirect(callback);

        assertEquals(
                "https://app.example.com/payment/result?result=success&paymentId=200",
                redirect.toString());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void returnUrlReportsCancelledWhenCustomerCancelsOnGateway() {
        Map<String, String> callback = signedCallback("5000000", "24", "02");
        when(paymentTransactionRepository.findByProviderAndMerchantTxnRef("VNPAY", "P200TEST"))
                .thenReturn(Optional.of(attempt));

        URI redirect = vnpayService.buildReturnRedirect(callback);

        assertEquals(
                "https://app.example.com/payment/result?result=cancelled&paymentId=200",
                redirect.toString());
    }

    // Hồi quy: VNPAY từ chối vnp_TxnRef trùng (Error.html?code=01).
    // Mỗi lần tạo URL phải sinh merchantTxnRef mới, kể cả khi attempt cũ còn PENDING.
    @Test
    void createPaymentUrlAlwaysIssuesNewTxnRefSoRetryAfterCancelWorks() {
        when(principal.getId()).thenReturn(10);
        when(principal.getRoleNames()).thenReturn(List.of("CUSTOMER"));
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
        when(paymentTransactionRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String firstTxnRef = extractTxnRef(vnpayService.createPaymentUrl(200, principal, "127.0.0.1"));
        String secondTxnRef = extractTxnRef(vnpayService.createPaymentUrl(200, principal, "127.0.0.1"));

        assertNotEquals(firstTxnRef, secondTxnRef);
        verify(paymentTransactionRepository, times(2)).save(any(PaymentTransaction.class));
    }

    private String extractTxnRef(VnpayPaymentUrlResponse response) {
        return UriComponentsBuilder.fromUriString(response.paymentUrl())
                .build()
                .getQueryParams()
                .getFirst("vnp_TxnRef");
    }

    private Map<String, String> signedCallback(String amount, String responseCode, String transactionStatusValue) {
        Map<String, String> callback = new HashMap<>();
        callback.put("vnp_TmnCode", "TESTCODE");
        callback.put("vnp_TxnRef", "P200TEST");
        callback.put("vnp_Amount", amount);
        callback.put("vnp_TransactionNo", "900001");
        callback.put("vnp_ResponseCode", responseCode);
        callback.put("vnp_TransactionStatus", transactionStatusValue);
        callback.put("vnp_SecureHash", signer.sign(signer.canonicalize(callback), properties.getHashSecret()));
        return callback;
    }
}
