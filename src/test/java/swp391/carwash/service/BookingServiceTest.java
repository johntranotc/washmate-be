package swp391.carwash.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.dto.BookingCreateRequest;
import swp391.carwash.dto.BookingResponse;
import swp391.carwash.entity.AppUser;
import swp391.carwash.entity.Booking;
import swp391.carwash.entity.BookingSlot;
import swp391.carwash.entity.Garage;
import swp391.carwash.entity.Payment;
import swp391.carwash.entity.ServicePackage;
import swp391.carwash.entity.Vehicle;
import swp391.carwash.enums.BookingStatus;
import swp391.carwash.enums.PaymentMethod;
import swp391.carwash.enums.PaymentStatus;
import swp391.carwash.repository.*;
import swp391.carwash.security.AppUserDetails;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {
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
    private AppUserDetails principal;

    private BookingService bookingService;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private PromotionUsageRepository promotionUsageRepository;

    @Mock
    private LoyaltyAccountRepository loyaltyAccountRepository;


    @BeforeEach
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
                loyaltyService,promotionRepository,
                notificationRepository,
                promotionUsageRepository,
                new swp391.carwash.security.GarageAccessEvaluator()
        );
        // @Value không được inject khi khởi tạo bằng constructor trong unit test
        org.springframework.test.util.ReflectionTestUtils.setField(bookingService, "maxAdvanceDays", 30);
        org.springframework.test.util.ReflectionTestUtils.setField(bookingService, "paymentTimeoutMinutes", 15);
    }

    @Test
    void createBookingRejectsNonCustomerBeforeLoadingData() {
        when(principal.getRoleNames()).thenReturn(List.of("STAFF"));

        BookingCreateRequest request = new BookingCreateRequest(1, 1, 1, 1, LocalDate.now(), null, PaymentMethod.CASH);

        ApiException exception = assertThrows(ApiException.class, () -> bookingService.createBooking(request, principal));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertEquals("Only CUSTOMER can create booking", exception.getMessage());
        verify(appUserRepository, never()).findById(1);
        verify(bookingRepository, never()).save(org.mockito.ArgumentMatchers.any(Booking.class));
    }

    @Test
    void createBookingRejectsUnknownPromotion() {
        when(principal.getRoleNames()).thenReturn(List.of("CUSTOMER"));
        when(principal.getId()).thenReturn(10);

        AppUser customer = AppUser.builder().id(10).fullName("Customer").phone("0911111111").build();
        Garage garage = Garage.builder().id(1).name("Garage").address("Address").phone("0900000000").build();
        BookingSlot slot = BookingSlot.builder().id(30).garage(garage).build();
        ServicePackage service = ServicePackage.builder()
                .id(40)
                .garage(garage)
                .name("Basic Wash")
                .price(new BigDecimal("50000.00"))
                .duration(30)
                .build();
        Vehicle vehicle = Vehicle.builder().id(20).user(customer).licensePlate("59A1-12345").build();

        when(appUserRepository.findById(10)).thenReturn(Optional.of(customer));
        when(garageRepository.findById(1)).thenReturn(Optional.of(garage));
        when(bookingSlotRepository.findByIdForUpdate(30)).thenReturn(Optional.of(slot));
        when(servicePackageRepository.findById(40)).thenReturn(Optional.of(service));
        when(vehicleRepository.findById(20)).thenReturn(Optional.of(vehicle));

        BookingCreateRequest request = new BookingCreateRequest(1, 30, 40, 20, LocalDate.now(), 999, PaymentMethod.CASH);

        ApiException exception = assertThrows(ApiException.class, () -> bookingService.createBooking(request, principal));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    void completeRejectsUnpaidBooking() {
        Booking booking = detailedBooking(BookingStatus.WASHING);
        Payment payment = Payment.builder()
                .id(200)
                .booking(booking)
                .garage(booking.getGarage())
                .amount(booking.getFinalAmount())
                .method(PaymentMethod.CASH)
                .status(PaymentStatus.PENDING)
                .build();

        when(principal.getRoleNames()).thenReturn(List.of("ADMIN"));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingId(100)).thenReturn(Optional.of(payment));

        ApiException exception = assertThrows(ApiException.class, () -> bookingService.complete(100, principal));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("Booking can only be completed after payment is PAID", exception.getMessage());
        assertEquals(BookingStatus.WASHING, booking.getStatus());
    }

    @Test
    void checkInRejectsWrongStatus() {
        Booking booking = detailedBooking(BookingStatus.PENDING);

        when(principal.getRoleNames()).thenReturn(List.of("ADMIN"));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));

        ApiException exception = assertThrows(ApiException.class, () -> bookingService.checkIn(100, principal));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("Only CONFIRMED booking can be checked in", exception.getMessage());
    }

    @Test
    void getMyBookingsReturnsOnlyCurrentCustomerBookings() {
        Booking booking = detailedBooking(BookingStatus.PENDING);
        Payment payment = Payment.builder()
                .id(200)
                .booking(booking)
                .garage(booking.getGarage())
                .amount(booking.getFinalAmount())
                .method(PaymentMethod.CASH)
                .status(PaymentStatus.PENDING)
                .build();

        when(principal.getRoleNames()).thenReturn(List.of("CUSTOMER"));
        when(principal.getId()).thenReturn(10);
        when(bookingRepository.findByUserIdOrderByCreatedAtDesc(10)).thenReturn(List.of(booking));
        when(paymentRepository.findByBookingIdIn(List.of(100))).thenReturn(List.of(payment));
        when(invoiceRepository.findByBookingIdIn(List.of(100))).thenReturn(List.of());

        List<BookingResponse> response = bookingService.getMyBookings(principal);

        assertEquals(1, response.size());
        assertEquals(100, response.get(0).id());
        assertEquals(200, response.get(0).payment().id());
        verify(bookingRepository, never()).findDetailedById(anyInt());
    }

    private Booking detailedBooking(BookingStatus status) {
        Garage garage = Garage.builder().id(1).name("Garage").address("Address").phone("0900000000").build();
        AppUser customer = AppUser.builder().id(10).fullName("Customer").phone("0911111111").build();
        Vehicle vehicle = Vehicle.builder().id(20).user(customer).licensePlate("59A1-12345").build();
        BookingSlot slot = BookingSlot.builder().id(30).garage(garage).build();
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
                .status(status)
                .build();
    }
}
