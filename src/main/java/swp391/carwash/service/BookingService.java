package swp391.carwash.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swp391.carwash.common.TimeZones;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.dto.*;
import swp391.carwash.entity.*;
import swp391.carwash.enums.*;
import swp391.carwash.repository.*;
import swp391.carwash.security.AppUserDetails;

@Service
@RequiredArgsConstructor
public class BookingService {
    private static final List<BookingStatus> CAPACITY_BLOCKING_STATUSES = List.of(
            BookingStatus.PENDING,
            BookingStatus.CONFIRMED,
            BookingStatus.CHECKED_IN,
            BookingStatus.WASHING);

    private final AppUserRepository appUserRepository;
    private final BookingRepository bookingRepository;
    private final BookingSlotRepository bookingSlotRepository;
    private final GarageRepository garageRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final LoyaltyAccountRepository loyaltyAccountRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final ServicePackageRepository servicePackageRepository;
    private final VehicleRepository vehicleRepository;
    private final LoyaltyService loyaltyService;
    private final PromotionRepository promotionRepository;
    private final NotificationRepository notificationRepository;
    private final PromotionUsageRepository promotionUsageRepository;
    private final RewardRedemptionRepository rewardRedemptionRepository;
    private final PromotionReleaseService promotionReleaseService;
    private final swp391.carwash.security.GarageAccessEvaluator garageAccessEvaluator;

    @Value("${washmate.payment.vnpay.timeout-minutes:15}")
    private int paymentTimeoutMinutes;

    @Value("${washmate.booking.max-advance-days:30}")
    private int maxAdvanceDays;

    @Transactional
    public BookingResponse createBooking(BookingCreateRequest request, AppUserDetails principal) {
        requireCustomer(principal);
        AppUser customer = appUserRepository.findById(principal.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User not found"));
        Garage garage = garageRepository.findById(request.garageId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Garage not found"));
        BookingSlot slot = bookingSlotRepository.findByIdForUpdate(request.slotId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Booking slot not found"));
        ServicePackage service = servicePackageRepository.findById(request.serviceId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Service package not found"));
        Vehicle vehicle = vehicleRepository.findById(request.vehicleId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Vehicle not found"));

        validateBookingInputs(request, customer, garage, slot, service, vehicle);

        BigDecimal totalAmount = service.getPrice();

        // Khóa dòng promotion NGAY từ đầu (nếu có) để việc validate + tăng usedCount + insert usage
        // diễn ra tuần tự trong cùng transaction, tránh vượt usageLimit và double-use.
        Promotion promotion = null;
        if (request.promotionId() != null) {
            promotion = promotionRepository.findByIdForUpdate(request.promotionId())
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.NOT_FOUND, "Mã khuyến mãi không tồn tại"));
        }

        BigDecimal promotionDiscount = calculateDiscount(
                customer.getId(),
                garage,
                service,
                promotion
        );

        BigDecimal tierDiscount = calculateTierDiscount(
                customer.getId(),
                garage.getId(),
                totalAmount
        );

        BigDecimal discountAmount = promotionDiscount.add(tierDiscount);

        if (discountAmount.compareTo(totalAmount) > 0) {
            discountAmount = totalAmount;
        }

        BigDecimal finalAmount = totalAmount.subtract(discountAmount);

        Booking booking = bookingRepository.save(Booking.builder()
                .bookingCode(generateBookingCode())
                .user(customer)
                .garage(garage)
                .slot(slot)
                .service(service)
                .vehicle(vehicle)
                .bookingDate(request.bookingDate())
                .totalAmount(totalAmount)
                .discountAmount(discountAmount)
                .finalAmount(finalAmount)
                .status(BookingStatus.PENDING)
                .build());
        bookingRepository.flush();

        if (promotion != null) {
            // promotion đã được khóa bi quan ở trên -> increment an toàn với request đồng thời.
            promotionUsageRepository.save(
                    PromotionUsage.builder()
                            .promotion(promotion)
                            .user(customer)
                            .booking(booking)
                            .build()
            );
            promotion.setUsedCount(promotion.getUsedCount() + 1);
            promotionRepository.save(promotion);
        }


        //notification khi đặt lịch chờ xác nhận
        notificationRepository.save(Notification.builder()
                .userId(customer.getId())
                .bookingId(booking.getId())
                .title("Đặt lịch thành công")
                .content(String.format("Yêu cầu đặt lịch %s tại %s đang chờ xác nhận.", booking.getBookingCode(), garage.getName()))
                .type("BOOKING_CONFIRMATION")
                .channel("IN_APP")
                .status("PENDING")
                .build());

        PaymentMethod paymentMethod = request.paymentMethod() == null ? PaymentMethod.CASH : request.paymentMethod();
        Payment payment = paymentRepository.findByBookingId(booking.getId())
                .orElseGet(() -> paymentRepository.save(Payment.builder()
                        .booking(booking)
                        .garage(garage)
                        .amount(finalAmount)
                        .method(paymentMethod)
                        .status(PaymentStatus.PENDING)
                        // expiresAt chỉ áp dụng cho VNPAY (cửa sổ thanh toán online).
                        // CASH thu tiền tại chỗ nên không có hạn thanh toán -> để null.
                        .expiresAt(resolvePaymentExpiresAt(paymentMethod))
                        .build()));
        if (request.paymentMethod() != null && payment.getMethod() != request.paymentMethod()) {
            payment.setMethod(request.paymentMethod());
            payment.setExpiresAt(resolvePaymentExpiresAt(request.paymentMethod()));
            payment.setUpdatedAt(OffsetDateTime.now());
        }

        return BookingResponse.from(booking, payment, null);
    }

    private OffsetDateTime resolvePaymentExpiresAt(PaymentMethod method) {
        return method == PaymentMethod.VNPAY
                ? OffsetDateTime.now().plusMinutes(paymentTimeoutMinutes)
                : null;
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getMyBookings(AppUserDetails principal) {
        requireCustomer(principal);
        List<Booking> bookings = bookingRepository.findByUserIdOrderByCreatedAtDesc(principal.getId());
        if (bookings.isEmpty()) {
            return List.of();
        }

        List<Integer> bookingIds = bookings.stream().map(Booking::getId).toList();

        Map<Integer, Payment> paymentMap = paymentRepository.findByBookingIdIn(bookingIds).stream()
                .collect(Collectors.toMap(p -> p.getBooking().getId(), p -> p, (p1, p2) -> p1));

        Map<Integer, Invoice> invoiceMap = invoiceRepository.findByBookingIdIn(bookingIds).stream()
                .collect(Collectors.toMap(i -> i.getBooking().getId(), i -> i, (i1, i2) -> i1));

        return bookings.stream()
                .map(booking -> BookingResponse.from(
                        booking,
                        paymentMap.get(booking.getId()),
                        invoiceMap.get(booking.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<BookingResponse> getBookings(BookingStatus status, Integer garageId, LocalDate fromDate, LocalDate toDate, Pageable pageable, AppUserDetails principal) {
        List<Integer> garageIds = null;
        List<String> roles = principal.getRoleNames();
        if (!roles.contains("ADMIN") && !roles.contains("OWNER")) {
            garageIds = principal.getGarageIds();
            if (garageIds.isEmpty()) {
                return Page.empty(pageable);
            }
        }

        Page<Booking> bookingPage = bookingRepository.findBookingsWithFilters(status, garageId, fromDate, toDate, garageIds, pageable);
        if (bookingPage.isEmpty()) {
            return Page.empty(pageable);
        }

        List<Integer> bookingIds = bookingPage.getContent().stream().map(Booking::getId).toList();

        Map<Integer, Payment> paymentMap = paymentRepository.findByBookingIdIn(bookingIds).stream()
                .collect(Collectors.toMap(p -> p.getBooking().getId(), p -> p, (p1, p2) -> p1));

        Map<Integer, Invoice> invoiceMap = invoiceRepository.findByBookingIdIn(bookingIds).stream()
                .collect(Collectors.toMap(i -> i.getBooking().getId(), i -> i, (i1, i2) -> i1));

        return bookingPage.map(booking -> BookingResponse.from(
                booking,
                paymentMap.get(booking.getId()),
                invoiceMap.get(booking.getId())));
    }

    @Transactional
    public BookingResponse updateBooking(Integer bookingId, BookingUpdateRequest request, AppUserDetails principal) {
        // Khóa row booking: updateBooking sửa đơn + payment dựa trên trạng thái hiện tại,
        // cần chặn race với confirm/cancel/update đồng thời.
        Booking booking = findDetailedBookingForUpdate(bookingId);

        boolean isOwner = booking.getUser().getId().equals(principal.getId());
        boolean isStaff = canOperateGarage(booking, principal);
        if (!isOwner && !isStaff) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You cannot update this booking");
        }

        if (!isOwner && isStaff && !booking.getGarage().getId().equals(request.garageId())) {
            List<String> roles = principal.getRoleNames();
            if (!roles.contains("ADMIN") && !roles.contains("OWNER")) {
                if (!principal.getGarageIds().contains(request.garageId())) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "You cannot transfer booking to a garage you don't manage");
                }
            }
        }

        if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new ApiException(HttpStatus.CONFLICT, "Only PENDING or CONFIRMED booking can be updated");
        }

        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payment not found"));
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot update booking because payment is not PENDING");
        }

        boolean garageChanged = !booking.getGarage().getId().equals(request.garageId());
        boolean serviceChanged = !booking.getService().getId().equals(request.serviceId());

        Garage newGarage = !garageChanged ? booking.getGarage() :
                garageRepository.findById(request.garageId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Garage not found"));
        // Luôn khóa bi quan dòng slot (kể cả khi giữ nguyên slot) để đếm capacity nhất quán,
        // tránh đua với các booking tạo mới đồng thời trên cùng slot.
        BookingSlot newSlot = bookingSlotRepository.findByIdForUpdate(request.slotId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Booking slot not found"));
        ServicePackage newService = booking.getService().getId().equals(request.serviceId()) ? booking.getService() :
                servicePackageRepository.findById(request.serviceId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Service package not found"));
        Vehicle newVehicle = booking.getVehicle().getId().equals(request.vehicleId()) ? booking.getVehicle() :
                vehicleRepository.findById(request.vehicleId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Vehicle not found"));

        if (newGarage.getStatus() != GarageStatus.ACTIVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "New garage is not active");
        }
        if (newSlot.getStatus() != RecordStatus.ACTIVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "New slot is not active");
        }
        if (newService.getStatus() != RecordStatus.ACTIVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "New service is not active");
        }
        if (newVehicle.getStatus() != RecordStatus.ACTIVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "New vehicle is not active");
        }
        if (!newSlot.getGarage().getId().equals(newGarage.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Slot does not belong to garage");
        }
        if (!newService.getGarage().getId().equals(newGarage.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Service does not belong to garage");
        }
        if (!newVehicle.getUser().getId().equals(booking.getUser().getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Vehicle does not belong to the customer");
        }

        validateBookingDate(request.bookingDate(), resolveAdvanceWindowDays(booking.getUser().getId(), newGarage.getId()));
        validateSlotNotStarted(request.bookingDate(), newSlot);
        if (bookingRepository.existsActiveBookingForUserAndSlot(
                booking.getUser().getId(), newSlot.getId(), request.bookingDate(), CAPACITY_BLOCKING_STATUSES, booking.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Khách hàng đã có lịch đặt cho khung giờ này");
        }
        long activeBookings = bookingRepository.countActiveBookingsForUpdate(newSlot.getId(), newGarage.getId(), request.bookingDate(), CAPACITY_BLOCKING_STATUSES, booking.getId());
        if (activeBookings >= newSlot.getMaxCapacity()) {
            throw new ApiException(HttpStatus.CONFLICT, "New booking slot is full");
        }

        booking.setGarage(newGarage);
        booking.setSlot(newSlot);
        booking.setService(newService);
        booking.setVehicle(newVehicle);
        booking.setBookingDate(request.bookingDate());

        BigDecimal newTotal = newService.getPrice();
        // Khi đổi garage hoặc service thì bối cảnh giảm giá thay đổi:
        // - promotion gắn theo garage & dùng-một-lần, và payload update không mang promotionId
        //   -> KHÔNG áp lại promotion cũ (tránh mang mã không hợp lệ sang garage/giá mới).
        // - tier discount tính lại theo garage & giá mới.
        // Nếu giữ nguyên garage và service -> giữ nguyên discount cũ.
        BigDecimal newDiscount;
        if (garageChanged || serviceChanged) {
            newDiscount = calculateTierDiscount(booking.getUser().getId(), newGarage.getId(), newTotal);
        } else {
            newDiscount = booking.getDiscountAmount();
        }
        if (newDiscount.compareTo(newTotal) > 0) {
            newDiscount = newTotal;
        }
        BigDecimal newFinal = newTotal.subtract(newDiscount);
        booking.setTotalAmount(newTotal);
        booking.setDiscountAmount(newDiscount);
        booking.setFinalAmount(newFinal);

        payment.setAmount(newFinal);
        payment.setGarage(newGarage);
        payment.setUpdatedAt(OffsetDateTime.now());

        notificationRepository.save(Notification.builder()
                .userId(booking.getUser().getId())
                .bookingId(booking.getId())
                .title("Cập nhật đặt lịch thành công")
                .content(String.format("Đơn đặt lịch %s của bạn đã được cập nhật thành công.", booking.getBookingCode()))
                .type("BOOKING_CONFIRMATION")
                .channel("IN_APP")
                .status("PENDING")
                .build());

        return BookingResponse.from(booking, payment, invoiceRepository.findByBookingId(bookingId).orElse(null));
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(Integer bookingId, AppUserDetails principal) {
        Booking booking = findDetailedBooking(bookingId);
        authorizeBookingRead(booking, principal);
        Payment payment = paymentRepository.findByBookingId(bookingId).orElse(null);
        Invoice invoice = invoiceRepository.findByBookingId(bookingId).orElse(null);
        return BookingResponse.from(booking, payment, invoice);
    }

    @Transactional
    public BookingResponse confirmBooking(Integer bookingId, AppUserDetails principal) {
        // Khóa bi quan row booking trước khi kiểm tra trạng thái để tránh 2 request
        // đồng thời cùng xác nhận một đơn (double-processing).
        Booking booking = findDetailedBookingForUpdate(bookingId);
        authorizeGarageOperation(booking, principal);
        requireStatus(booking, BookingStatus.PENDING, "Only PENDING booking can be confirmed");
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setConfirmedAt(OffsetDateTime.now());

        notificationRepository.save(Notification.builder()
                .userId(booking.getUser().getId())
                .bookingId(booking.getId())
                .title("Đơn đặt lịch được xác nhận")
                .content(String.format("Lịch rửa xe %s đã được xác nhận thành công bởi %s.", booking.getBookingCode(), booking.getGarage().getName()))
                .type("BOOKING_CONFIRMATION")
                .channel("IN_APP")
                .status("PENDING")
                .build());

        return responseWithPaymentAndInvoice(booking);

    }

    @Transactional
    public BookingResponse rejectBooking(Integer bookingId, BookingRejectRequest request, AppUserDetails principal) {
        Booking booking = findDetailedBookingForUpdate(bookingId);
        authorizeGarageOperation(booking, principal);
        requireStatus(booking, BookingStatus.PENDING, "Only PENDING booking can be rejected");

        OffsetDateTime now = OffsetDateTime.now();
        booking.setStatus(BookingStatus.REJECTED);
        booking.setCancelledAt(now);
        booking.setRejectionReason(request.reason());

        // Garage từ chối đơn -> khách không được mất mã khuyến mãi đã dùng.
        promotionReleaseService.releaseForBooking(booking.getId());

        Payment payment = paymentRepository.findByBookingId(bookingId).orElse(null);
        if (payment != null && payment.getStatus() == PaymentStatus.PENDING) {
            payment.setStatus(PaymentStatus.CANCELLED);
            payment.setUpdatedAt(now);
            recordPaymentTransaction(payment, PaymentTransactionStatus.CANCELLED, "MANUAL", null);
        }
        notificationRepository.save(Notification.builder()
                .userId(booking.getUser().getId())
                .bookingId(booking.getId())
                .title("Đơn đặt lịch bị từ chối")
                .content(String.format("Rất tiếc, đơn đặt lịch %s đã bị từ chối. Lý do: %s", booking.getBookingCode(), request.reason()))
                .type("BOOKING_CONFIRMATION")
                .channel("IN_APP")
                .status("PENDING")
                .build());

        Invoice invoice = invoiceRepository.findByBookingId(bookingId).orElse(null);
        return BookingResponse.from(booking, payment, invoice);
    }

    @Transactional
    public BookingResponse checkIn(Integer bookingId, AppUserDetails principal) {
        Booking booking = findDetailedBookingForUpdate(bookingId);
        authorizeGarageOperation(booking, principal);
        requireStatus(booking, BookingStatus.CONFIRMED, "Only CONFIRMED booking can be checked in");
        booking.setStatus(BookingStatus.CHECKED_IN);
        booking.setCheckinTime(OffsetDateTime.now());
        return responseWithPaymentAndInvoice(booking);
    }

    @Transactional
    public BookingResponse startWashing(Integer bookingId, AppUserDetails principal) {
        Booking booking = findDetailedBookingForUpdate(bookingId);
        authorizeGarageOperation(booking, principal);
        requireStatus(booking, BookingStatus.CHECKED_IN, "Only CHECKED_IN booking can start washing");
        booking.setStatus(BookingStatus.WASHING);
        booking.setServiceStartTime(OffsetDateTime.now());

        notificationRepository.save(Notification.builder()
                .userId(booking.getUser().getId())
                .bookingId(booking.getId())
                .title("Xe của bạn đang được rửa")
                .content(String.format("Garage %s đã bắt đầu tiến hành dịch vụ rửa xe cho mã đơn %s.", booking.getGarage().getName(), booking.getBookingCode()))
                .type("BOOKING_CONFIRMATION")
                .channel("IN_APP")
                .status("PENDING")
                .build());

        return responseWithPaymentAndInvoice(booking);
    }

    @Transactional
    public BookingResponse complete(Integer bookingId, AppUserDetails principal) {
        Booking booking = findDetailedBookingForUpdate(bookingId);
        authorizeGarageOperation(booking, principal);
        requireStatus(booking, BookingStatus.WASHING, "Only WASHING booking can be completed");
        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Payment not found for booking"));
        if (payment.getStatus() != PaymentStatus.PAID) {
            throw new ApiException(HttpStatus.CONFLICT, "Booking can only be completed after payment is PAID");
        }
        booking.setStatus(BookingStatus.COMPLETED);
        booking.setCompletedTime(OffsetDateTime.now());

        loyaltyService.accruePoints(booking);

        Invoice invoice = invoiceRepository.findByBookingId(bookingId).orElse(null);
        return BookingResponse.from(booking, payment, invoice);
    }

    @Transactional
    public BookingResponse cancelBooking(Integer bookingId, AppUserDetails principal) {
        Booking booking = findDetailedBookingForUpdate(bookingId);
        authorizeBookingCancel(booking, principal);
        if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new ApiException(HttpStatus.CONFLICT, "Only PENDING or CONFIRMED booking can be cancelled");
        }

        Payment payment = paymentRepository.findByBookingId(bookingId).orElse(null);
        if (payment != null && payment.getStatus() == PaymentStatus.PAID) {
            throw new ApiException(HttpStatus.CONFLICT, "Paid booking must be refunded instead of cancelled");
        }

        OffsetDateTime now = OffsetDateTime.now();
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(now);

        if (payment != null && payment.getStatus() == PaymentStatus.PENDING) {
            payment.setStatus(PaymentStatus.CANCELLED);
            payment.setUpdatedAt(now);
            recordPaymentTransaction(payment, PaymentTransactionStatus.CANCELLED, "MANUAL", null);
        }

        // Huỷ đơn -> nhả mã khuyến mãi để khách dùng lại được và không đốt quota campaign.
        promotionReleaseService.releaseForBooking(booking.getId());

        notificationRepository.save(Notification.builder()
                .userId(booking.getUser().getId())
                .bookingId(booking.getId())
                .title("Hủy lịch thành công")
                .content(String.format("Đơn đặt lịch %s của bạn đã được hủy bỏ thành công.", booking.getBookingCode()))
                .type("BOOKING_CONFIRMATION")
                .channel("IN_APP")
                .status("PENDING")
                .build());

        Invoice invoice = invoiceRepository.findByBookingId(bookingId).orElse(null);
        return BookingResponse.from(booking, payment, invoice);
    }

    @Transactional
    public BookingResponse markNoShow(Integer bookingId, AppUserDetails principal) {
        Booking booking = findDetailedBookingForUpdate(bookingId);
        authorizeGarageOperation(booking, principal);
        requireStatus(booking, BookingStatus.CONFIRMED, "Only CONFIRMED booking can be marked as NO_SHOW");

        OffsetDateTime now = OffsetDateTime.now();
        booking.setStatus(BookingStatus.NO_SHOW);
        booking.setNoShowAt(now);

        // Khách không đến → đóng payment PENDING kèm theo (nếu có)
        Payment payment = paymentRepository.findByBookingId(bookingId).orElse(null);
        if (payment != null && payment.getStatus() == PaymentStatus.PENDING) {
            payment.setStatus(PaymentStatus.CANCELLED);
            payment.setUpdatedAt(now);
            recordPaymentTransaction(payment, PaymentTransactionStatus.CANCELLED, "MANUAL", null);
        }

        // Nhả mã khuyến mãi: đơn không được thực hiện nên mã chưa thực sự được tiêu.
        promotionReleaseService.releaseForBooking(booking.getId());

        Invoice invoice = invoiceRepository.findByBookingId(bookingId).orElse(null);
        return BookingResponse.from(booking, payment, invoice);
    }

    private void validateBookingInputs(BookingCreateRequest request, AppUser customer, Garage garage, BookingSlot slot,
            ServicePackage service, Vehicle vehicle) {
        if (garage.getStatus() != GarageStatus.ACTIVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Garage is not active");
        }
        if (slot.getStatus() != RecordStatus.ACTIVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Booking slot is not active");
        }
        if (service.getStatus() != RecordStatus.ACTIVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Service package is not active");
        }
        if (vehicle.getStatus() != RecordStatus.ACTIVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Vehicle is not active");
        }
        if (!slot.getGarage().getId().equals(garage.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Slot does not belong to garage");
        }
        if (!service.getGarage().getId().equals(garage.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Service package does not belong to garage");
        }
        if (!vehicle.getUser().getId().equals(customer.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Vehicle does not belong to current user");
        }
        validateBookingDate(request.bookingDate(), resolveAdvanceWindowDays(customer.getId(), garage.getId()));
        validateSlotNotStarted(request.bookingDate(), slot);
        if (bookingRepository.existsActiveBookingForUserAndSlot(
                customer.getId(), slot.getId(), request.bookingDate(), CAPACITY_BLOCKING_STATUSES, null)) {
            throw new ApiException(HttpStatus.CONFLICT, "Bạn đã có lịch đặt cho khung giờ này");
        }
        long activeBookings = bookingRepository.countActiveBookings(slot.getId(), garage.getId(), request.bookingDate(),
                CAPACITY_BLOCKING_STATUSES);
        if (activeBookings >= slot.getMaxCapacity()) {
            throw new ApiException(HttpStatus.CONFLICT, "Booking slot is full");
        }
    }

    // Chặn đặt khung giờ mà thời điểm bắt đầu đã trôi qua (đặc biệt là slot trong ngày hôm nay).
    private void validateSlotNotStarted(LocalDate bookingDate, BookingSlot slot) {
        if (slot.getStartTime() == null) {
            return;
        }
        if (!LocalDateTime.of(bookingDate, slot.getStartTime())
                .isAfter(LocalDateTime.now(TimeZones.VIETNAM))) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Khung giờ đã bắt đầu hoặc đã qua, vui lòng chọn khung giờ khác");
        }
    }

    private void validateBookingDate(LocalDate bookingDate, int advanceWindowDays) {
        LocalDate today = LocalDate.now(TimeZones.VIETNAM);
        if (bookingDate.isBefore(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Ngày đặt lịch không được ở quá khứ");
        }
        if (bookingDate.isAfter(today.plusDays(advanceWindowDays))) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Hạng thành viên của bạn chỉ được đặt lịch trước tối đa " + advanceWindowDays + " ngày");
        }
    }

    /**
     * Cửa sổ đặt trước theo hạng thành viên: hạng cao đặt xa hơn nên ưu tiên vào slot sớm hơn.
     * Không có tài khoản/hạng hoặc hạng chưa cấu hình window → dùng mặc định hệ thống.
     */
    private int resolveAdvanceWindowDays(Integer userId, Integer garageId) {
        return loyaltyAccountRepository.findByUserIdAndGarageId(userId, garageId)
                .map(LoyaltyAccount::getTier)
                .map(MembershipTier::getAdvanceBookingDays)
                .filter(days -> days > 0)
                .orElse(maxAdvanceDays);
    }

    private Booking findDetailedBooking(Integer bookingId) {
        return bookingRepository.findDetailedById(bookingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Booking not found"));
    }

    private Booking findDetailedBookingForUpdate(Integer bookingId) {
        return bookingRepository.findDetailedByIdForUpdate(bookingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Booking not found"));
    }

    private BookingResponse responseWithPaymentAndInvoice(Booking booking) {
        Payment payment = paymentRepository.findByBookingId(booking.getId()).orElse(null);
        Invoice invoice = invoiceRepository.findByBookingId(booking.getId()).orElse(null);
        return BookingResponse.from(booking, payment, invoice);
    }

    private void requireStatus(Booking booking, BookingStatus status, String message) {
        if (booking.getStatus() != status) {
            throw new ApiException(HttpStatus.CONFLICT, message);
        }
    }

    private void requireCustomer(AppUserDetails principal) {
        if (!principal.getRoleNames().contains("CUSTOMER")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only CUSTOMER can create booking");
        }
    }

    private void authorizeBookingRead(Booking booking, AppUserDetails principal) {
        if (booking.getUser().getId().equals(principal.getId()) || canOperateGarage(booking, principal)) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "You cannot access this booking");
    }

    private void authorizeGarageOperation(Booking booking, AppUserDetails principal) {
        if (!canOperateGarage(booking, principal)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You cannot operate this booking");
        }
    }

    private void authorizeBookingCancel(Booking booking, AppUserDetails principal) {
        if (booking.getUser().getId().equals(principal.getId()) || canOperateGarage(booking, principal)) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "You cannot cancel this booking");
    }

    private boolean canOperateGarage(Booking booking, AppUserDetails principal) {
        return garageAccessEvaluator.canOperate(booking.getGarage().getId(), principal);
    }

    private String generateBookingCode() {
        // Sinh mã tới khi không trùng (kết hợp unique constraint DB làm chốt chặn cuối).
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = "BKG-" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 16).toUpperCase();
            if (!bookingRepository.existsByBookingCode(code)) {
                return code;
            }
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Không thể tạo mã đặt lịch, vui lòng thử lại");
    }

    private void recordPaymentTransaction(Payment payment, PaymentTransactionStatus status, String provider,
            String providerTxnId) {
        paymentTransactionRepository.save(PaymentTransaction.builder()
                .payment(payment)
                .provider(provider)
                .providerTxnId(providerTxnId)
                .amount(payment.getAmount())
                .status(status)
                .build());
    }
    private BigDecimal calculateDiscount(
            Integer customerId,
            Garage garage,
            ServicePackage service,
            Promotion promotion) {

        BigDecimal totalAmount = service.getPrice();
        BigDecimal discount = BigDecimal.ZERO;

        if (promotion == null) {
            return discount;
        }

        OffsetDateTime now = OffsetDateTime.now();

        if (promotionUsageRepository.existsByUser_IdAndPromotion_PromotionId(
                customerId,
                promotion.getPromotionId())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Bạn đã sử dụng mã khuyến mãi này.");
        }

        if (!PromotionStatus.ACTIVE.equals(promotion.getStatus())
                || now.isBefore(promotion.getStartDate())
                || now.isAfter(promotion.getEndDate())
                || (promotion.getUsageLimit() != null
                && promotion.getUsedCount() >= promotion.getUsageLimit())
                || totalAmount.compareTo(promotion.getMinOrderValue()) < 0) {

            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Mã khuyến mãi không hợp lệ hoặc đã hết hạn");
        }

        if (!promotion.getGarageId().equals(garage.getId())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Mã khuyến mãi không áp dụng cho garage này");
        }

        requirePromotionOwnership(customerId, promotion);

        if (DiscountType.PERCENTAGE.equals(promotion.getDiscountType())) {

            discount = totalAmount.multiply(promotion.getDiscountValue())
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

            // maxDiscount chỉ là mức chặn khi > 0. null HOẶC 0 đều nghĩa là "không giới hạn"
            // -> dữ liệu cũ bị lưu maxDiscount = 0 (do normalize null->0 trước đây) vẫn giảm đúng
            // thay vì bị ép về 0đ.
            if (promotion.getMaxDiscount() != null
                    && promotion.getMaxDiscount().compareTo(BigDecimal.ZERO) > 0
                    && discount.compareTo(promotion.getMaxDiscount()) > 0) {

                discount = promotion.getMaxDiscount();
            }

        } else if (DiscountType.FIXED_AMOUNT.equals(promotion.getDiscountType())) {

            discount = promotion.getDiscountValue();
        }

        if (discount.compareTo(totalAmount) > 0) {
            discount = totalAmount;
        }

        return discount;
    }
    /**
     * Voucher sinh ra từ ĐỔI ĐIỂM là tài sản riêng của người đã tiêu điểm, nhưng
     * {@code Promotion} không có cột chủ sở hữu — quan hệ đó nằm ở {@code RewardRedemption}.
     *
     * <p>Trước đây ràng buộc này CHỈ tồn tại trong query liệt kê
     * ({@code PromotionRepository.findAvailablePromotions}), nên khách chỉ cần đoán
     * {@code promotionId} (số nguyên tăng dần) là dùng được voucher người khác vừa đổi.
     *
     * <p>Nhận diện voucher cá nhân bằng "có RewardRedemption trỏ tới promotion này" thay vì
     * dựa vào {@code usageLimit == 1} — để không chặn oan mã công khai mà admin cố ý giới hạn
     * 1 lượt (kiểu ai nhanh hơn thì được).
     */
    private void requirePromotionOwnership(Integer customerId, Promotion promotion) {
        Integer promotionId = promotion.getPromotionId();

        if (!rewardRedemptionRepository.existsByPromotion_PromotionId(promotionId)) {
            // Không phải voucher đổi điểm -> mã công khai/campaign, không có chủ sở hữu.
            return;
        }

        boolean ownedByCustomer = rewardRedemptionRepository
                .existsByPromotion_PromotionIdAndLoyaltyAccount_User_IdAndStatus(
                        promotionId, customerId, "COMPLETED");

        if (!ownedByCustomer) {
            // Trả 404-style message để không tiết lộ rằng mã có tồn tại và thuộc về ai.
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Mã khuyến mãi không hợp lệ hoặc không thuộc về bạn");
        }
    }

    private BigDecimal calculateTierDiscount(
            Integer userId,
            Integer garageId,
            BigDecimal totalAmount) {

        LoyaltyAccount account = loyaltyAccountRepository
                .findByUserIdAndGarageId(userId, garageId)
                .orElse(null);

        if (account == null) {
            return BigDecimal.ZERO;
        }

        MembershipTier tier = account.getTier();

        if (tier == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal discountPercentage = tier.getDiscountPercentage();

        if (discountPercentage == null
                || discountPercentage.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        return totalAmount
                .multiply(discountPercentage)
                .divide(
                        BigDecimal.valueOf(100),
                        2,
                        RoundingMode.HALF_UP
                );
    }
}
