package swp391.carwash.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.dto.PaymentActionRequest;
import swp391.carwash.dto.PaymentConfirmRequest;
import swp391.carwash.dto.PaymentResponse;
import swp391.carwash.entity.Booking;
import swp391.carwash.entity.Invoice;
import swp391.carwash.entity.Payment;
import swp391.carwash.entity.PaymentTransaction;
import swp391.carwash.enums.BookingStatus;
import swp391.carwash.enums.InvoiceStatus;
import swp391.carwash.enums.PaymentMethod;
import swp391.carwash.enums.PaymentStatus;
import swp391.carwash.repository.BookingRepository;
import swp391.carwash.repository.InvoiceRepository;
import swp391.carwash.repository.PaymentRepository;
import swp391.carwash.repository.PaymentTransactionRepository;
import swp391.carwash.security.AppUserDetails;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private LoyaltyService loyaltyService;
    @Mock
    private AppUserDetails principal;

    private PaymentService paymentService;
    private Booking booking;
    private Payment payment;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                bookingRepository,
                invoiceRepository,
                paymentRepository,
                paymentTransactionRepository,
                loyaltyService,
                new PaymentSettlementService(invoiceRepository, org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class)),
                new swp391.carwash.security.GarageAccessEvaluator());

        booking = swp391.carwash.testutil.TestData.pendingBooking("BKG-TEST");
        payment = swp391.carwash.testutil.TestData.pendingPayment(booking, PaymentMethod.CASH);

        lenient().when(principal.getRoleNames()).thenReturn(List.of("OWNER"));
        lenient().when(paymentRepository.findById(200)).thenReturn(Optional.of(payment));
    }

    @Test
    void confirmPaymentRejectsAmountMismatch() {
        payment.setAmount(new BigDecimal("49000.00"));
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));

        ApiException exception = assertThrows(ApiException.class,
                () -> paymentService.confirmPayment(200, new PaymentConfirmRequest(PaymentMethod.CASH, "MANUAL", "TXN-1"), principal));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("Payment amount does not match booking final amount", exception.getMessage());
        verify(paymentTransactionRepository, never()).save(any(PaymentTransaction.class));
    }

    @Test
    void confirmPaymentRejectsDuplicateProviderTransaction() {
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));
        when(paymentTransactionRepository.existsByProviderAndProviderTxnId("MANUAL", "TXN-1")).thenReturn(true);

        ApiException exception = assertThrows(ApiException.class,
                () -> paymentService.confirmPayment(200, new PaymentConfirmRequest(PaymentMethod.CASH, "MANUAL", "TXN-1"), principal));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("Provider transaction already exists", exception.getMessage());
        verify(paymentTransactionRepository, never()).save(any(PaymentTransaction.class));
    }

    @Test
    void refundPaidCompletedBookingKeepsBookingCompletedAndRefundsInvoice() {
        booking.setStatus(BookingStatus.COMPLETED);
        payment.setStatus(PaymentStatus.PAID);
        Invoice invoice = Invoice.builder()
                .id(300)
                .invoiceCode("INV-TEST")
                .booking(booking)
                .payment(payment)
                .garage(booking.getGarage())
                .subtotal(booking.getTotalAmount())
                .discount(BigDecimal.ZERO)
                .penaltyTotal(BigDecimal.ZERO)
                .totalAmount(booking.getFinalAmount())
                .status(InvoiceStatus.PAID)
                .build();

        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));
        when(paymentTransactionRepository.existsByProviderAndProviderTxnId("MANUAL", "REF-1")).thenReturn(false);
        when(invoiceRepository.findByBookingId(100)).thenReturn(Optional.of(invoice));

        paymentService.refundPayment(200, new PaymentActionRequest("MANUAL", "REF-1", "Customer requested refund"), principal);

        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
        assertEquals(InvoiceStatus.REFUNDED, invoice.getStatus());
        assertEquals(BookingStatus.COMPLETED, booking.getStatus());
        assertSame(invoice, invoiceRepository.findByBookingId(100).orElseThrow());
        verify(loyaltyService).rollbackEarnedPointsForBooking(booking);
    }

    @Test
    void manualConfirmRejectsVnpayPayment() {
        payment.setMethod(PaymentMethod.VNPAY);
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));

        ApiException exception = assertThrows(ApiException.class,
                () -> paymentService.confirmPayment(
                        200,
                        new PaymentConfirmRequest(PaymentMethod.VNPAY, "VNPAY", "VNPAY-1"),
                        principal));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("VNPAY payment can only be confirmed by a verified IPN", exception.getMessage());
        verify(paymentTransactionRepository, never()).save(any(PaymentTransaction.class));
    }

    @Test
    void getPaymentByBookingReturnsPaymentForBookingOwner() {
        when(principal.getId()).thenReturn(10);
        when(paymentRepository.findByBookingId(100)).thenReturn(Optional.of(payment));
        when(paymentTransactionRepository.findByPaymentIdOrderByCreatedAtDesc(200)).thenReturn(List.of());

        PaymentResponse response = paymentService.getPaymentByBooking(100, principal);

        assertEquals(200, response.id());
        assertEquals(100, response.bookingId());
        assertEquals(PaymentStatus.PENDING.name(), response.status());
    }

    @Test
    void confirmPaymentAllowsConfirmedBookingAndPreservesStatus() {
        booking.setStatus(BookingStatus.CONFIRMED);
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));
        when(paymentTransactionRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(invoiceRepository.findByBookingId(100)).thenReturn(Optional.empty());
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        paymentService.confirmPayment(200, new PaymentConfirmRequest(PaymentMethod.CASH, "MANUAL", "TXN-1"), principal);

        assertEquals(PaymentStatus.PAID, payment.getStatus());
        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
        verify(invoiceRepository).save(any(Invoice.class));
    }

    @Test
    void confirmPaymentRejectsCompletedBooking() {
        booking.setStatus(BookingStatus.COMPLETED);
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));

        ApiException exception = assertThrows(ApiException.class,
                () -> paymentService.confirmPayment(200, new PaymentConfirmRequest(PaymentMethod.CASH, "MANUAL", "TXN-1"), principal));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("Only active booking can be paid", exception.getMessage());
        verify(paymentTransactionRepository, never()).save(any(PaymentTransaction.class));
    }

    @Test
    void confirmPaymentRejectsWhenPaymentNotPending() {
        payment.setStatus(PaymentStatus.PAID);
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));

        ApiException exception = assertThrows(ApiException.class,
                () -> paymentService.confirmPayment(200, new PaymentConfirmRequest(PaymentMethod.CASH, "MANUAL", "TXN-1"), principal));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("Only PENDING payment can be confirmed", exception.getMessage());
        verify(paymentTransactionRepository, never()).save(any(PaymentTransaction.class));
    }

    @Test
    void refundPaymentRejectsWhenNotPaid() {
        payment.setStatus(PaymentStatus.PENDING);
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));

        ApiException exception = assertThrows(ApiException.class,
                () -> paymentService.refundPayment(200, new PaymentActionRequest("MANUAL", "REF-1", "Customer requested refund"), principal));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("Only PAID payment can be refunded", exception.getMessage());
    }

    @Test
    void refundPaymentRejectsVnpay() {
        payment.setMethod(PaymentMethod.VNPAY);
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));

        ApiException exception = assertThrows(ApiException.class,
                () -> paymentService.refundPayment(200, new PaymentActionRequest("VNPAY", "REF-1", "Refund request"), principal));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("VNPAY refund API is not implemented", exception.getMessage());
    }

    @Test
    void confirmPaymentRollsBackOnError() {
        when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));
        when(paymentTransactionRepository.save(any(PaymentTransaction.class))).thenThrow(new RuntimeException("Database error"));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> paymentService.confirmPayment(200, new PaymentConfirmRequest(PaymentMethod.CASH, "MANUAL", "TXN-1"), principal));

        assertEquals("Database error", exception.getMessage());
        verify(invoiceRepository, never()).save(any());
    }
}
