package swp391.carwash.service;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.dto.BookingResponse;
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
import swp391.carwash.enums.PaymentTransactionStatus;
import swp391.carwash.repository.BookingRepository;
import swp391.carwash.repository.InvoiceRepository;
import swp391.carwash.repository.PaymentRepository;
import swp391.carwash.repository.PaymentTransactionRepository;
import swp391.carwash.security.AppUserDetails;

@Service
@RequiredArgsConstructor
public class PaymentService {
    private static final EnumSet<BookingStatus> PAYABLE_BOOKING_STATUSES = EnumSet.of(
            BookingStatus.PENDING,
            BookingStatus.CONFIRMED,
            BookingStatus.CHECKED_IN,
            BookingStatus.WASHING);

    // Chỉ các phương thức thu tại chỗ mới được nhân viên xác nhận thủ công.
    // VNPAY do IPN xử lý; các cổng online khác (nếu thêm sau) mặc định bị chặn.
    private static final EnumSet<PaymentMethod> MANUAL_CONFIRMABLE_METHODS = EnumSet.of(
            PaymentMethod.CASH);

    private final BookingRepository bookingRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final LoyaltyService loyaltyService;
    private final PaymentSettlementService paymentSettlementService;
    private final swp391.carwash.security.GarageAccessEvaluator garageAccessEvaluator;

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(Integer paymentId, AppUserDetails principal) {
        Payment payment = findDetailedPayment(paymentId);
        authorizeBookingRead(payment.getBooking(), principal);
        return PaymentResponse.from(payment, paymentTransactionRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId));
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByBooking(Integer bookingId, AppUserDetails principal) {
        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payment not found"));
        authorizeBookingRead(payment.getBooking(), principal);
        return PaymentResponse.from(payment, paymentTransactionRepository.findByPaymentIdOrderByCreatedAtDesc(payment.getId()));
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse.TransactionInfo> getPaymentTransactions(Integer paymentId, AppUserDetails principal) {
        Payment payment = findDetailedPayment(paymentId);
        authorizeBookingRead(payment.getBooking(), principal);
        return PaymentResponse.from(payment, paymentTransactionRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId)).transactions();
    }

    @Transactional
    public BookingResponse confirmPayment(Integer paymentId, PaymentConfirmRequest request, AppUserDetails principal) {
        Booking booking = lockBookingOfPayment(paymentId);          // khóa booking trước
        Payment payment = findDetailedPaymentForUpdate(paymentId);  // rồi mới khóa payment
        authorizeGarageOperation(booking, principal);

        return confirmPendingPayment(payment, booking, request, principal.getId());
    }

    private BookingResponse confirmPendingPayment(Payment payment, Booking booking, PaymentConfirmRequest request, Integer confirmedByUserId) {
        if (!PAYABLE_BOOKING_STATUSES.contains(booking.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only active booking can be paid");
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Only PENDING payment can be confirmed");
        }
        if (payment.getAmount().compareTo(booking.getFinalAmount()) != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Payment amount does not match booking final amount");
        }
        PaymentMethod method = request == null || request.method() == null ? payment.getMethod() : request.method();
        if (payment.getMethod() == PaymentMethod.VNPAY || method == PaymentMethod.VNPAY) {
            throw new ApiException(HttpStatus.CONFLICT, "VNPAY payment can only be confirmed by a verified IPN");
        }
        if (!MANUAL_CONFIRMABLE_METHODS.contains(method)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Payment method " + method + " cannot be confirmed manually");
        }

        String provider = providerOrManual(request == null ? null : request.provider());
        String providerTxnId = request == null ? null : request.providerTxnId();
        ensureProviderTransactionIsNew(provider, providerTxnId);

        OffsetDateTime now = OffsetDateTime.now();
        PaymentTransaction transaction = recordPaymentTransaction(
                payment, PaymentTransactionStatus.SUCCESS, provider, providerTxnId, confirmedByUserId);
        Invoice invoice = paymentSettlementService.settle(payment, booking, transaction, method, now);

        return BookingResponse.from(booking, payment, invoice);
    }

    @Transactional
    public BookingResponse failPayment(Integer paymentId, PaymentActionRequest request, AppUserDetails principal) {
        return closePendingPayment(paymentId, request, principal, PaymentStatus.FAILED, PaymentTransactionStatus.FAILED, "Only PENDING payment can be failed");
    }

    @Transactional
    public BookingResponse cancelPayment(Integer paymentId, PaymentActionRequest request, AppUserDetails principal) {
        return closePendingPayment(paymentId, request, principal, PaymentStatus.CANCELLED, PaymentTransactionStatus.CANCELLED, "Only PENDING payment can be cancelled");
    }

    @Transactional
    public BookingResponse refundPayment(Integer paymentId, PaymentActionRequest request, AppUserDetails principal) {
        Booking booking = lockBookingOfPayment(paymentId);          // khóa booking trước
        Payment payment = findDetailedPaymentForUpdate(paymentId);  // rồi mới khóa payment
        authorizeGarageOperation(booking, principal);

        if (payment.getMethod() == PaymentMethod.VNPAY) {
            throw new ApiException(HttpStatus.CONFLICT, "VNPAY refund API is not implemented");
        }

        if (payment.getStatus() != PaymentStatus.PAID) {
            throw new ApiException(HttpStatus.CONFLICT, "Only PAID payment can be refunded");
        }

        String provider = providerOrManual(request == null ? null : request.provider());
        String providerTxnId = request == null ? null : request.providerTxnId();
        ensureProviderTransactionIsNew(provider, providerTxnId);

        OffsetDateTime now = OffsetDateTime.now();
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setUpdatedAt(now);
        recordPaymentTransaction(payment, PaymentTransactionStatus.REFUNDED, provider, providerTxnId, principal.getId());

        Invoice invoice = invoiceRepository.findByBookingId(booking.getId()).orElse(null);
        if (invoice != null) {
            invoice.setStatus(InvoiceStatus.REFUNDED);
        }

        if (booking.getStatus() != BookingStatus.COMPLETED) {
            booking.setStatus(BookingStatus.CANCELLED);
            booking.setCancelledAt(now);
        }
        loyaltyService.rollbackEarnedPointsForBooking(booking);

        return BookingResponse.from(booking, payment, invoice);
    }

    private BookingResponse closePendingPayment(
            Integer paymentId,
            PaymentActionRequest request,
            AppUserDetails principal,
            PaymentStatus paymentStatus,
            PaymentTransactionStatus transactionStatus,
            String invalidStatusMessage) {
        Booking booking = lockBookingOfPayment(paymentId);          // khóa booking trước
        Payment payment = findDetailedPaymentForUpdate(paymentId);  // rồi mới khóa payment
        authorizeGarageOperation(booking, principal);

        if (payment.getMethod() == PaymentMethod.VNPAY) {
            throw new ApiException(HttpStatus.CONFLICT, "VNPAY status is managed by IPN and timeout processing");
        }

        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, invalidStatusMessage);
        }
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Only PENDING booking payment can be closed without refund");
        }

        String provider = providerOrManual(request == null ? null : request.provider());
        String providerTxnId = request == null ? null : request.providerTxnId();
        ensureProviderTransactionIsNew(provider, providerTxnId);

        OffsetDateTime now = OffsetDateTime.now();
        payment.setStatus(paymentStatus);
        payment.setUpdatedAt(now);
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(now);
        recordPaymentTransaction(payment, transactionStatus, provider, providerTxnId, principal.getId());

        Invoice invoice = invoiceRepository.findByBookingId(booking.getId()).orElse(null);
        return BookingResponse.from(booking, payment, invoice);
    }

    private Payment findDetailedPayment(Integer paymentId) {
        return paymentRepository.findDetailedById(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payment not found"));
    }

    private Payment findDetailedPaymentForUpdate(Integer paymentId) {
        return paymentRepository.findDetailedByIdForUpdate(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payment not found"));
    }

    /**
     * Khóa aggregate booking↔payment theo thứ tự NHẤT QUÁN: BOOKING trước, PAYMENT sau.
     * Mọi flow sửa trạng thái booking/payment (kể cả bên BookingService) đều phải khóa
     * row booking trước (cùng "ổ khóa") để tránh race chéo giữa hai service. Đọc payment
     * nhẹ chỉ để lấy bookingId (quan hệ FK cố định, an toàn khi chưa khóa).
     */
    private Booking lockBookingOfPayment(Integer paymentId) {
        Payment ref = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payment not found"));
        return bookingRepository.findDetailedByIdForUpdate(ref.getBooking().getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Booking not found"));
    }

    private void authorizeBookingRead(Booking booking, AppUserDetails principal) {
        if (booking.getUser().getId().equals(principal.getId()) || canOperateGarage(booking, principal)) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "You cannot access this payment");
    }

    private void authorizeGarageOperation(Booking booking, AppUserDetails principal) {
        if (!canOperateGarage(booking, principal)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You cannot operate this payment");
        }
    }

    private boolean canOperateGarage(Booking booking, AppUserDetails principal) {
        return garageAccessEvaluator.canOperate(booking.getGarage().getId(), principal);
    }

    private void ensureProviderTransactionIsNew(String provider, String providerTxnId) {
        if (providerTxnId == null || providerTxnId.isBlank()) {
            return;
        }
        if (paymentTransactionRepository.existsByProviderAndProviderTxnId(provider, providerTxnId)) {
            throw new ApiException(HttpStatus.CONFLICT, "Provider transaction already exists");
        }
    }

    private PaymentTransaction recordPaymentTransaction(Payment payment, PaymentTransactionStatus status, String provider, String providerTxnId, Integer confirmedByUserId) {
        try {
            PaymentTransaction transaction = paymentTransactionRepository.save(PaymentTransaction.builder()
                    .payment(payment)
                    .provider(provider)
                    .providerTxnId(providerTxnId)
                    .amount(payment.getAmount())
                    .status(status)
                    .confirmedByUserId(confirmedByUserId)
                    .build());
            paymentTransactionRepository.flush();
            return transaction;
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "Provider transaction already exists");
        }
    }

    private String providerOrManual(String provider) {
        return provider == null || provider.isBlank() ? "MANUAL" : provider;
    }
}
