package swp391.carwash.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
import swp391.carwash.entity.Promotion;
import swp391.carwash.entity.ServicePackage;
import swp391.carwash.entity.Vehicle;
import swp391.carwash.enums.BookingStatus;
import swp391.carwash.enums.DiscountType;
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
    private swp391.carwash.repository.RewardRedemptionRepository rewardRedemptionRepository;
    @Mock
    private PromotionReleaseService promotionReleaseService;

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
                rewardRedemptionRepository,
                promotionReleaseService,
                new swp391.carwash.security.GarageAccessEvaluator()
        );
        // @Value không được inject khi khởi tạo bằng constructor trong unit test
        org.springframework.test.util.ReflectionTestUtils.setField(bookingService, "maxAdvanceDays", 30);
        org.springframework.test.util.ReflectionTestUtils.setField(bookingService, "noShowGraceMinutes", 15);
        org.springframework.test.util.ReflectionTestUtils.setField(bookingService, "checkInEarlyMinutes", 30);
        org.springframework.test.util.ReflectionTestUtils.setField(bookingService, "cancelCutoffMinutes", 0);
        org.springframework.test.util.ReflectionTestUtils.setField(bookingService, "maxOpenBookingsPerCustomer", 5);
        org.springframework.test.util.ReflectionTestUtils.setField(bookingService, "maxOpenBookingsPerGaragePerDay", 2);
        org.springframework.test.util.ReflectionTestUtils.setField(bookingService, "maxNoShowsBeforeBlock", 2);
        org.springframework.test.util.ReflectionTestUtils.setField(bookingService, "noShowBlockWindowDays", 90);
        org.springframework.test.util.ReflectionTestUtils.setField(bookingService, "cashSharePerSlot", 0.5d);
        // Mặc định test coi như khách quen; test nào cần tài khoản mới thì bật riêng.
        org.springframework.test.util.ReflectionTestUtils.setField(bookingService, "newAccountMaxOpen", 0);
        org.springframework.test.util.ReflectionTestUtils.setField(bookingService, "newAccountMaxAdvanceDays", 0);
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

    /**
     * Không cho đặt slot sát giờ: phải trước giờ hẹn ít nhất min-lead-minutes.
     * Ngưỡng này dùng chung với bộ lọc hiển thị slot ở BookingSlotServiceImpl.
     */
    @Test
    void createBookingRejectsSlotStartingSoonerThanMinLeadTime() {
        org.springframework.test.util.ReflectionTestUtils.setField(bookingService, "minLeadMinutes", 30);
        when(principal.getRoleNames()).thenReturn(List.of("CUSTOMER"));
        when(principal.getId()).thenReturn(10);

        AppUser customer = AppUser.builder().id(10).fullName("Customer").phone("0911111111").build();
        Garage garage = Garage.builder()
                .id(1)
                .name("Garage")
                .address("Address")
                .phone("0900000000")
                .status(swp391.carwash.enums.GarageStatus.ACTIVE)
                .build();
        // Slot bắt đầu sau 10 phút nữa -> chưa đủ 30 phút lead time.
        java.time.LocalDateTime slotStart = java.time.LocalDateTime
                .now(swp391.carwash.common.TimeZones.VIETNAM).plusMinutes(10);
        BookingSlot slot = BookingSlot.builder()
                .id(30)
                .garage(garage)
                .startTime(slotStart.toLocalTime())
                .endTime(slotStart.toLocalTime().plusHours(1))
                .maxCapacity(5)
                .status(swp391.carwash.enums.RecordStatus.ACTIVE)
                .build();
        ServicePackage service = ServicePackage.builder()
                .id(40)
                .garage(garage)
                .name("Basic Wash")
                .price(new BigDecimal("50000.00"))
                .duration(30)
                .status(swp391.carwash.enums.RecordStatus.ACTIVE)
                .build();
        Vehicle vehicle = Vehicle.builder()
                .id(20)
                .user(customer)
                .licensePlate("59A1-12345")
                .status(swp391.carwash.enums.RecordStatus.ACTIVE)
                .build();

        when(appUserRepository.findById(10)).thenReturn(Optional.of(customer));
        when(garageRepository.findById(1)).thenReturn(Optional.of(garage));
        when(bookingSlotRepository.findByIdForUpdate(30)).thenReturn(Optional.of(slot));
        when(servicePackageRepository.findById(40)).thenReturn(Optional.of(service));
        when(vehicleRepository.findById(20)).thenReturn(Optional.of(vehicle));

        BookingCreateRequest request = new BookingCreateRequest(
                1, 30, 40, 20, slotStart.toLocalDate(), null, PaymentMethod.CASH);

        ApiException exception = assertThrows(ApiException.class,
                () -> bookingService.createBooking(request, principal));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    /**
     * B3: voucher sinh ra từ đổi điểm là tài sản riêng của người đã tiêu điểm.
     * Trước đây ràng buộc này chỉ có trong query LIỆT KÊ, nên đoán promotionId là dùng được
     * voucher của người khác.
     */
    @Test
    void createBookingRejectsRedeemedVoucherOwnedByAnotherCustomer() {
        when(principal.getRoleNames()).thenReturn(List.of("CUSTOMER"));
        when(principal.getId()).thenReturn(10);

        AppUser customer = AppUser.builder().id(10).fullName("Customer").phone("0911111111").build();
        Garage garage = Garage.builder().id(1).name("Garage").address("Address").phone("0900000000").build();
        BookingSlot slot = BookingSlot.builder().id(30).garage(garage).maxCapacity(4).build();
        ServicePackage service = ServicePackage.builder()
                .id(40).garage(garage).name("Basic Wash")
                .price(new BigDecimal("50000.00")).duration(30)
                .build();
        Vehicle vehicle = Vehicle.builder().id(20).user(customer).licensePlate("59A1-12345").build();

        Promotion voucher = Promotion.builder()
                .promotionId(555)
                .garageId(1)
                .promoCode("WM-G1-ABCDEF12")
                .discountType(DiscountType.PERCENTAGE)
                .discountValue(new BigDecimal("20"))
                .minOrderValue(BigDecimal.ZERO)
                .usageLimit(1)
                .usedCount(0)
                .startDate(java.time.OffsetDateTime.now().minusDays(1))
                .endDate(java.time.OffsetDateTime.now().plusDays(7))
                .status(swp391.carwash.enums.PromotionStatus.ACTIVE)
                .build();

        when(appUserRepository.findById(10)).thenReturn(Optional.of(customer));
        when(garageRepository.findById(1)).thenReturn(Optional.of(garage));
        when(bookingSlotRepository.findByIdForUpdate(30)).thenReturn(Optional.of(slot));
        when(servicePackageRepository.findById(40)).thenReturn(Optional.of(service));
        when(vehicleRepository.findById(20)).thenReturn(Optional.of(vehicle));
        when(promotionRepository.findByIdForUpdate(555)).thenReturn(Optional.of(voucher));
        // Là voucher đổi điểm...
        when(rewardRedemptionRepository.existsByPromotion_PromotionId(555)).thenReturn(true);
        // ...nhưng KHÔNG thuộc về khách này.
        when(rewardRedemptionRepository
                .existsByPromotion_PromotionIdAndLoyaltyAccount_User_IdAndStatus(555, 10, "COMPLETED"))
                .thenReturn(false);

        BookingCreateRequest request = new BookingCreateRequest(1, 30, 40, 20, LocalDate.now().plusDays(1), 555, PaymentMethod.CASH);

        ApiException exception = assertThrows(ApiException.class, () -> bookingService.createBooking(request, principal));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    /** B2: huỷ đơn phải nhả lại mã khuyến mãi (không để khách mất mã / đốt quota campaign). */
    @Test
    void cancelBookingReleasesPromotion() {
        Booking booking = detailedBooking(BookingStatus.CONFIRMED);
        when(principal.getRoleNames()).thenReturn(List.of("ADMIN"));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingId(100)).thenReturn(Optional.empty());

        bookingService.cancelBooking(100, principal);

        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
        verify(promotionReleaseService).releaseForBooking(100);
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
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(0, 20);
        when(bookingRepository.findByUserIdOrderByCreatedAtDesc(10, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(booking), pageable, 1));
        when(paymentRepository.findByBookingIdIn(List.of(100))).thenReturn(List.of(payment));
        when(invoiceRepository.findByBookingIdIn(List.of(100))).thenReturn(List.of());

        org.springframework.data.domain.Page<BookingResponse> response =
                bookingService.getMyBookings(principal, pageable);

        assertEquals(1, response.getTotalElements());
        assertEquals(100, response.getContent().get(0).id());
        assertEquals(200, response.getContent().get(0).payment().id());
        verify(bookingRepository, never()).findDetailedById(anyInt());
    }

    /**
     * Đơn đang phục vụ mà chưa thu được tiền từng là ngõ cụt tuyệt đối: complete đòi PAID,
     * cancel chỉ nhận PENDING/CONFIRMED, no-show chỉ nhận CONFIRMED. Đơn kẹt và chiếm slot.
     */
    @Test
    void abortClosesUnpaidWashingBookingAndReleasesSlot() {
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
        when(invoiceRepository.findByBookingId(100)).thenReturn(Optional.empty());

        bookingService.abortBooking(100,
                new swp391.carwash.dto.BookingAbortRequest("Khách bỏ về giữa chừng"), principal);

        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
        assertEquals("Khách bỏ về giữa chừng", booking.getRejectionReason());
        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
        verify(promotionReleaseService).releaseForBooking(100);
    }

    /** Đơn đã thu tiền phải đi đường refund để tiền và trạng thái đơn luôn khớp nhau. */
    @Test
    void abortRejectsPaidBooking() {
        Booking booking = detailedBooking(BookingStatus.WASHING);
        Payment payment = Payment.builder()
                .id(200)
                .booking(booking)
                .garage(booking.getGarage())
                .amount(booking.getFinalAmount())
                .method(PaymentMethod.CASH)
                .status(PaymentStatus.PAID)
                .build();

        when(principal.getRoleNames()).thenReturn(List.of("ADMIN"));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingId(100)).thenReturn(Optional.of(payment));

        ApiException exception = assertThrows(ApiException.class, () -> bookingService.abortBooking(
                100, new swp391.carwash.dto.BookingAbortRequest("Máy hỏng"), principal));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(BookingStatus.WASHING, booking.getStatus());
    }

    /** Check-in đơn của ngày khác làm đơn thoát khỏi tầm quét của BookingNoShowScheduler. */
    @Test
    void checkInRejectsBookingTooFarBeforeSlotStart() {
        // Giờ hẹn còn cách 3 tiếng, cửa sổ check-in sớm chỉ 30 phút.
        Booking booking = bookingWithSlotStartingAt(BookingStatus.CONFIRMED,
                java.time.LocalDateTime.now(swp391.carwash.common.TimeZones.VIETNAM).plusHours(3));

        when(principal.getRoleNames()).thenReturn(List.of("ADMIN"));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));

        ApiException exception = assertThrows(ApiException.class, () -> bookingService.checkIn(100, principal));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
    }

    /** No-show thủ công phải dùng đúng mốc của scheduler (giờ hẹn + grace). */
    @Test
    void markNoShowRejectsBeforeGraceDeadline() {
        Booking booking = bookingWithSlotStartingAt(BookingStatus.CONFIRMED,
                java.time.LocalDateTime.now(swp391.carwash.common.TimeZones.VIETNAM).plusHours(1));

        when(principal.getRoleNames()).thenReturn(List.of("ADMIN"));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));

        ApiException exception = assertThrows(ApiException.class, () -> bookingService.markNoShow(100, principal));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
    }

    /** Khách không tới rồi bấm huỷ sau giờ hẹn = né NO_SHOW. Phải chặn. */
    @Test
    void cancelRejectsCustomerAfterSlotStart() {
        Booking booking = bookingWithSlotStartingAt(BookingStatus.CONFIRMED,
                java.time.LocalDateTime.now(swp391.carwash.common.TimeZones.VIETNAM).minusMinutes(10));

        when(principal.getId()).thenReturn(10);
        when(principal.getRoleNames()).thenReturn(List.of("CUSTOMER"));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));

        ApiException exception = assertThrows(ApiException.class, () -> bookingService.cancelBooking(100, principal));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
    }

    /** Staff vẫn huỷ được sau giờ hẹn để xử lý ngoại lệ tại quầy. */
    @Test
    void cancelAllowsGarageStaffAfterSlotStart() {
        Booking booking = bookingWithSlotStartingAt(BookingStatus.CONFIRMED,
                java.time.LocalDateTime.now(swp391.carwash.common.TimeZones.VIETNAM).minusMinutes(10));

        when(principal.getRoleNames()).thenReturn(List.of("ADMIN"));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingId(100)).thenReturn(Optional.empty());
        when(invoiceRepository.findByBookingId(100)).thenReturn(Optional.empty());

        bookingService.cancelBooking(100, principal);

        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
    }

    /**
     * Trần đơn treo không bắt được kẻ phá kiên trì (đặt -> để trôi giờ -> đặt lại).
     * Án tích vắng mặt mới là thứ chặn vòng lặp đó.
     */
    @Test
    void createBookingBlockedAfterTooManyNoShows() {
        when(principal.getRoleNames()).thenReturn(List.of("CUSTOMER"));
        when(principal.getId()).thenReturn(10);

        AppUser customer = AppUser.builder().id(10).fullName("Customer").phone("0911111111").build();
        Garage garage = Garage.builder()
                .id(1).name("Garage").address("Address").phone("0900000000")
                .status(swp391.carwash.enums.GarageStatus.ACTIVE)
                .build();
        BookingSlot slot = BookingSlot.builder()
                .id(30).garage(garage).maxCapacity(4)
                .status(swp391.carwash.enums.RecordStatus.ACTIVE)
                .build();
        ServicePackage service = ServicePackage.builder()
                .id(40).garage(garage).name("Basic Wash")
                .price(new BigDecimal("50000.00")).duration(30)
                .status(swp391.carwash.enums.RecordStatus.ACTIVE)
                .build();
        Vehicle vehicle = Vehicle.builder()
                .id(20).user(customer).licensePlate("59A1-12345")
                .status(swp391.carwash.enums.RecordStatus.ACTIVE)
                .build();

        when(appUserRepository.findById(10)).thenReturn(Optional.of(customer));
        when(garageRepository.findById(1)).thenReturn(Optional.of(garage));
        when(bookingSlotRepository.findByIdForUpdate(30)).thenReturn(Optional.of(slot));
        when(servicePackageRepository.findById(40)).thenReturn(Optional.of(service));
        when(vehicleRepository.findById(20)).thenReturn(Optional.of(vehicle));
        // Đã vắng mặt đúng ngưỡng 2 lần.
        when(bookingRepository.countRecentNoShows(eq(10), any())).thenReturn(2L);

        BookingCreateRequest request = new BookingCreateRequest(
                1, 30, 40, 20, LocalDate.now().plusDays(1), null, PaymentMethod.CASH);

        ApiException exception = assertThrows(ApiException.class,
                () -> bookingService.createBooking(request, principal));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    /**
     * Lớp duy nhất KHÔNG tính theo tài khoản, nên là lớp duy nhất chống được kiểu tạo hàng
     * loạt tài khoản rác. Slot capacity 4, tỉ lệ 0.5 -> tối đa 2 suất tiền mặt.
     */
    @Test
    void createBookingRejectsCashWhenSlotCashQuotaIsFull() {
        when(principal.getRoleNames()).thenReturn(List.of("CUSTOMER"));
        when(principal.getId()).thenReturn(10);

        AppUser customer = AppUser.builder().id(10).fullName("Customer").phone("0911111111").build();
        Garage garage = Garage.builder()
                .id(1).name("Garage").address("Address").phone("0900000000")
                .status(swp391.carwash.enums.GarageStatus.ACTIVE)
                .build();
        BookingSlot slot = BookingSlot.builder()
                .id(30).garage(garage).maxCapacity(4)
                .status(swp391.carwash.enums.RecordStatus.ACTIVE)
                .build();
        ServicePackage service = ServicePackage.builder()
                .id(40).garage(garage).name("Basic Wash")
                .price(new BigDecimal("50000.00")).duration(30)
                .status(swp391.carwash.enums.RecordStatus.ACTIVE)
                .build();
        Vehicle vehicle = Vehicle.builder()
                .id(20).user(customer).licensePlate("59A1-12345")
                .status(swp391.carwash.enums.RecordStatus.ACTIVE)
                .build();

        when(appUserRepository.findById(10)).thenReturn(Optional.of(customer));
        when(garageRepository.findById(1)).thenReturn(Optional.of(garage));
        when(bookingSlotRepository.findByIdForUpdate(30)).thenReturn(Optional.of(slot));
        when(servicePackageRepository.findById(40)).thenReturn(Optional.of(service));
        when(vehicleRepository.findById(20)).thenReturn(Optional.of(vehicle));
        // 2 suất tiền mặt đã bị giữ -> đầy hạn ngạch CASH của slot.
        when(bookingRepository.countUnpaidCashBookingsOnSlot(eq(30), eq(1), any(), any(), any()))
                .thenReturn(2L);

        BookingCreateRequest request = new BookingCreateRequest(
                1, 30, 40, 20, LocalDate.now().plusDays(1), null, PaymentMethod.CASH);

        ApiException exception = assertThrows(ApiException.class,
                () -> bookingService.createBooking(request, principal));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertTrue(exception.getMessage().contains("thanh toán online"));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    /** Án tích chặn thẳng việc đặt lịch nên bắt buộc phải có đường kêu oan. */
    @Test
    void excuseNoShowClearsStrikeByMovingBookingToCancelled() {
        Booking booking = detailedBooking(BookingStatus.NO_SHOW);

        when(principal.getRoleNames()).thenReturn(List.of("ADMIN"));
        when(bookingRepository.findDetailedByIdForUpdate(100)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingId(100)).thenReturn(Optional.empty());
        when(invoiceRepository.findByBookingId(100)).thenReturn(Optional.empty());

        bookingService.excuseNoShow(100,
                new swp391.carwash.dto.BookingAbortRequest("Khách nhập viện, có giấy tờ"), principal);

        // Không còn NO_SHOW -> rụng khỏi countRecentNoShows.
        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
        assertTrue(booking.getRejectionReason().contains("Khách nhập viện"));
    }

    private Booking bookingWithSlotStartingAt(BookingStatus status, java.time.LocalDateTime slotStart) {
        Booking booking = detailedBooking(status);
        booking.setBookingDate(slotStart.toLocalDate());
        booking.getSlot().setStartTime(slotStart.toLocalTime());
        booking.getSlot().setEndTime(slotStart.toLocalTime().plusMinutes(45));
        return booking;
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
