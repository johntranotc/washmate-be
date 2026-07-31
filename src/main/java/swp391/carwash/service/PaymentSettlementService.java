package swp391.carwash.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.entity.Booking;
import swp391.carwash.entity.Invoice;
import swp391.carwash.entity.Payment;
import swp391.carwash.entity.PaymentTransaction;
import swp391.carwash.enums.BookingStatus;
import swp391.carwash.enums.InvoiceStatus;
import swp391.carwash.enums.PaymentMethod;
import swp391.carwash.enums.PaymentStatus;
import swp391.carwash.enums.PaymentTransactionStatus;
import swp391.carwash.repository.InvoiceRepository;
import swp391.carwash.event.InvoiceCreatedEvent;
import org.springframework.context.ApplicationEventPublisher;

@Service
@RequiredArgsConstructor
public class PaymentSettlementService {
    private static final EnumSet<BookingStatus> PAYABLE_BOOKING_STATUSES = EnumSet.of(
            BookingStatus.PENDING,
            BookingStatus.CONFIRMED,
            BookingStatus.CHECKED_IN,
            BookingStatus.WASHING);

    private final InvoiceRepository invoiceRepository;
    private final ApplicationEventPublisher eventPublisher;

    public Invoice settle(
            Payment payment,
            Booking booking,
            PaymentTransaction transaction,
            PaymentMethod method,
            OffsetDateTime now) {
        if (!PAYABLE_BOOKING_STATUSES.contains(booking.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only active booking can be paid");
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Only PENDING payment can be confirmed");
        }
        if (payment.getAmount().compareTo(booking.getFinalAmount()) != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Payment amount does not match booking final amount");
        }

        payment.setStatus(PaymentStatus.PAID);
        payment.setMethod(method);
        payment.setPaidAt(now);
        payment.setUpdatedAt(now);

        if (booking.getStatus() == BookingStatus.PENDING) {
            booking.setStatus(BookingStatus.CONFIRMED);
            booking.setConfirmedAt(now);
        }

        transaction.setStatus(PaymentTransactionStatus.SUCCESS);
        transaction.setAmount(payment.getAmount());

        Invoice invoice = invoiceRepository.findByBookingId(booking.getId()).orElseGet(() -> invoiceRepository.save(
                Invoice.builder()
                        .invoiceCode(generateInvoiceCode())
                        .booking(booking)
                        .payment(payment)
                        .garage(booking.getGarage())
                        .subtotal(booking.getTotalAmount())
                        .discount(booking.getDiscountAmount())
                        .penaltyTotal(BigDecimal.ZERO)
                        .totalAmount(booking.getFinalAmount())
                        .status(InvoiceStatus.PAID)
                        .issuedAt(now)
                        .paidAt(now)
                        .build()));

        if (invoice.getPayment() == null) {
            invoice.setPayment(payment);
        } else if (!invoice.getPayment().getId().equals(payment.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Invoice belongs to another payment");
        }
        if (!invoice.getGarage().getId().equals(payment.getGarage().getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Invoice and payment garage do not match");
        }
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setPaidAt(invoice.getPaidAt() == null ? now : invoice.getPaidAt());

        // Publish event to trigger email
        eventPublisher.publishEvent(new InvoiceCreatedEvent(invoice.getId()));

        return invoice;
    }

    private String generateInvoiceCode() {
        // Sinh mã tới khi không trùng (kết hợp unique constraint DB làm chốt chặn cuối).
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = "INV-" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 16).toUpperCase();
            if (!invoiceRepository.existsByInvoiceCode(code)) {
                return code;
            }
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Không thể tạo mã hóa đơn, vui lòng thử lại");
    }
}
