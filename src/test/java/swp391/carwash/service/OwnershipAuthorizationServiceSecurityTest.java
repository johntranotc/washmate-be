package swp391.carwash.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.dto.BookingUpdateRequest;
import swp391.carwash.dto.PaymentConfirmRequest;
import swp391.carwash.entity.AppUser;
import swp391.carwash.entity.Booking;
import swp391.carwash.entity.BookingSlot;
import swp391.carwash.entity.Garage;
import swp391.carwash.entity.Invoice;
import swp391.carwash.entity.Payment;
import swp391.carwash.entity.ServicePackage;
import swp391.carwash.entity.Vehicle;
import swp391.carwash.enums.BookingStatus;
import swp391.carwash.enums.InvoiceStatus;
import swp391.carwash.enums.PaymentMethod;
import swp391.carwash.enums.PaymentStatus;
import swp391.carwash.repository.*;
import swp391.carwash.security.AppUserDetails;

@ExtendWith(MockitoExtension.class)
class OwnershipAuthorizationServiceSecurityTest {
    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingSlotRepository bookingSlotRepository;
    @Mock
    private GarageRepository garageRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;
    @Mock
    private ServicePackageRepository servicePackageRepository;
    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private LoyaltyService loyaltyService;
    @Mock
    private PromotionRepository promotionRepository;
    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private PaymentSettlementService paymentSettlementService;
    @Mock
    private AppUserDetails principal;
    @Mock
    private LoyaltyAccountRepository loyaltyAccountRepository;

    private BookingService bookingService;
    private PaymentService paymentService;
    private InvoiceService invoiceService;

    @Mock
    private PromotionUsageRepository promotionUsageRepository;    @BeforeEach
    void setUp() {
        bookingService = new BookingService(
                appUserRepository,
                bookingRepository,
                bookingSlotRepository,
                garageRepository,
                invoiceRepository,
                paymentRepository,
                loyaltyAccountRepository,
                paymentTransactionRepository,
                servicePackageRepository,
                vehicleRepository,
                loyaltyService,
                promotionRepository,
                notificationRepository,
                promotionUsageRepository,
                new swp391.carwash.security.GarageAccessEvaluator());
        paymentService = new PaymentService(
                bookingRepository,
                invoiceRepository,
                paymentRepository,
                paymentTransactionRepository,
                loyaltyService,
                paymentSettlementService,
                new swp391.carwash.security.GarageAccessEvaluator());
        invoiceService = new InvoiceService(
                bookingRepository,
                invoiceRepository,
                new swp391.carwash.security.GarageAccessEvaluator());
    }

    @Nested
    class BookingOwnership {
        @Test
        void should_returnForbidden_when_customerReadsAnotherCustomersBookingId() {
            Booking booking = bookingOwnedBy(20);
            when(bookingRepository.findDetailedById(100)).thenReturn(Optional.of(booking));
            stubCustomerPrincipal(10);

            ApiException exception = assertThrows(ApiException.class,
                    () -> bookingService.getBooking(100, principal));

            assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        }

        @Test
        void should_returnForbidden_when_customerUpdatesAnotherCustomersBookingId() {
            Booking booking = bookingOwnedBy(20);
            when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));
            stubCustomerPrincipal(10);

            ApiException exception = assertThrows(ApiException.class,
                    () -> bookingService.updateBooking(100, new BookingUpdateRequest(1, 30, 40, 50, LocalDate.now()), principal));

            assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        }

        @Test
        void should_returnForbidden_when_customerCancelsAnotherCustomersBookingId() {
            Booking booking = bookingOwnedBy(20);
            when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));
            stubCustomerPrincipal(10);

            ApiException exception = assertThrows(ApiException.class,
                    () -> bookingService.cancelBooking(100, principal));

            assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        }
    }

    @Nested
    class PaymentOwnership {
        @Test
        void should_returnForbidden_when_customerReadsAnotherCustomersPaymentId() {
            Payment payment = paymentForBookingOwnedBy(20);
            when(paymentRepository.findDetailedById(200)).thenReturn(Optional.of(payment));
            stubCustomerPrincipal(10);

            ApiException exception = assertThrows(ApiException.class,
                    () -> paymentService.getPayment(200, principal));

            assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        }

        @Test
        void should_returnForbidden_when_customerConfirmsAnotherCustomersPaymentId() {
            Payment payment = paymentForBookingOwnedBy(20);
            when(paymentRepository.findById(200)).thenReturn(Optional.of(payment));
            when(paymentRepository.findDetailedByIdForUpdate(200)).thenReturn(Optional.of(payment));
            when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(payment.getBooking()));
            stubCustomerRoleOnly();

            ApiException exception = assertThrows(ApiException.class,
                    () -> paymentService.confirmPayment(200, new PaymentConfirmRequest(PaymentMethod.CASH, null, null), principal));

            assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        }
    }

    @Nested
    class InvoiceOwnership {
        @Test
        void should_returnForbidden_when_customerReadsAnotherCustomersInvoiceId() {
            Invoice invoice = Invoice.builder()
                    .id(300)
                    .booking(bookingOwnedBy(20))
                    .status(InvoiceStatus.ISSUED)
                    .build();
            when(invoiceRepository.findDetailedById(300)).thenReturn(Optional.of(invoice));
            stubCustomerPrincipal(10);

            ApiException exception = assertThrows(ApiException.class,
                    () -> invoiceService.getInvoice(300, principal));

            assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        }
    }

    @Nested
    class VehicleOwnershipGaps {
        @Test
        @Disabled("Out of scope for this auth/JWT review owner: Vehicle module is handled separately.")
        void should_returnForbidden_when_customerUpdatesAnotherCustomersVehicleId() {
            throw new AssertionError("Enable in the Vehicle module test suite if that owner adds ownership/RBAC.");
        }

        @Test
        @Disabled("Out of scope for this auth/JWT review owner: Vehicle module is handled separately.")
        void should_returnForbidden_when_customerDeletesAnotherCustomersVehicleId() {
            throw new AssertionError("Enable in the Vehicle module test suite if that owner adds ownership/RBAC.");
        }
    }

    @Nested
    class ServiceLayerRbacGaps {
        @Test
        @Disabled("Controller-only @PreAuthorize can be bypassed by direct service invocation because services such as BookingSlotService do not receive a principal.")
        void should_returnForbidden_when_controllerPreAuthorizeIsBypassedByCallingServiceDirectly() {
            throw new AssertionError("Enable after service-layer RBAC or method security is added to protected service methods.");
        }
    }

    private void stubCustomerPrincipal(Integer userId) {
        when(principal.getId()).thenReturn(userId);
        when(principal.getRoleNames()).thenReturn(List.of("CUSTOMER"));
    }

    private void stubCustomerRoleOnly() {
        when(principal.getRoleNames()).thenReturn(List.of("CUSTOMER"));
    }

    private Payment paymentForBookingOwnedBy(Integer userId) {
        Booking booking = bookingOwnedBy(userId);
        return Payment.builder()
                .id(200)
                .booking(booking)
                .garage(booking.getGarage())
                .amount(booking.getFinalAmount())
                .method(PaymentMethod.CASH)
                .status(PaymentStatus.PENDING)
                .build();
    }

    private Booking bookingOwnedBy(Integer userId) {
        Garage garage = Garage.builder()
                .id(1)
                .name("Garage")
                .address("Address")
                .phone("0900000000")
                .build();
        AppUser customer = AppUser.builder()
                .id(userId)
                .email("customer" + userId + "@example.com")
                .fullName("Customer " + userId)
                .phone("09111111" + userId)
                .build();
        Vehicle vehicle = Vehicle.builder()
                .id(50)
                .user(customer)
                .licensePlate("59A1-12345")
                .build();
        BookingSlot slot = BookingSlot.builder()
                .id(30)
                .garage(garage)
                .build();
        ServicePackage service = ServicePackage.builder()
                .id(40)
                .garage(garage)
                .name("Basic Wash")
                .price(new BigDecimal("50000.00"))
                .duration(30)
                .build();

        return Booking.builder()
                .id(100)
                .bookingCode("BKG-TEST")
                .user(customer)
                .garage(garage)
                .slot(slot)
                .service(service)
                .vehicle(vehicle)
                .bookingDate(LocalDate.now())
                .totalAmount(new BigDecimal("50000.00"))
                .discountAmount(BigDecimal.ZERO)
                .finalAmount(new BigDecimal("50000.00"))
                .status(BookingStatus.PENDING)
                .build();
    }
}
