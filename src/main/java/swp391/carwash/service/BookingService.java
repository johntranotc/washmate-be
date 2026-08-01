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

    @Value("${washmate.booking.max-advance-days:30}")
    private int maxAdvanceDays;

    // Phải đặt trước giờ hẹn ít nhất bấy nhiêu phút. Giá trị này dùng CHUNG với
    // BookingSlotServiceImpl khi lọc slot hiển thị, để danh sách không lệch với khả năng đặt thật.
    @Value("${washmate.booking.min-lead-minutes:30}")
    private int minLeadMinutes;

    // Dùng CHUNG với BookingNoShowScheduler: API no-show thủ công và scheduler tự động
    // phải cùng một mốc, nếu không staff bấm được no-show cho đơn chưa tới giờ.
    @Value("${washmate.booking.no-show.grace-minutes:15}")
    private int noShowGraceMinutes;

    // Cho phép staff check-in sớm bao nhiêu phút trước giờ hẹn.
    // Số sau dấu ':' chỉ là fallback khi KHÔNG tìm thấy property. Giá trị thực tế đến từ
    // application.properties (mặc định 30) hoặc env BOOKING_CHECK_IN_EARLY_MINUTES —
    // sửa số ở đây không đổi được hành vi.
    @Value("${washmate.booking.check-in.early-minutes:30}")
    private int checkInEarlyMinutes;

    // Khách phải tự huỷ trước giờ hẹn ít nhất bấy nhiêu phút. 0 = chỉ cần trước giờ hẹn.
    @Value("${washmate.booking.cancel-cutoff-minutes:0}")
    private int cancelCutoffMinutes;

    // Trần số đơn CHƯA THANH TOÁN đang mở (PENDING/CONFIRMED, từ hôm nay trở đi) của một khách.
    @Value("${washmate.booking.max-open-per-customer:5}")
    private int maxOpenBookingsPerCustomer;

    // Trần số đơn đang mở của một khách tại cùng một garage trong cùng một ngày.
    @Value("${washmate.booking.max-open-per-garage-per-day:2}")
    private int maxOpenBookingsPerGaragePerDay;

    // Số lần vắng mặt (trong cửa sổ dưới đây) là bị cấm đặt lịch. 0 = tắt.
    @Value("${washmate.booking.no-show.max-before-block:2}")
    private int maxNoShowsBeforeBlock;

    // Cửa sổ tính án tích vắng mặt. Án tự hết hạn khi trôi ra ngoài cửa sổ này.
    @Value("${washmate.booking.no-show.block-window-days:90}")
    private int noShowBlockWindowDays;

    // Tỉ lệ capacity mỗi slot được phép bị đơn CASH chưa thanh toán giữ chỗ (0..1).
    // <=0 hoặc >=1 = tắt.
    @Value("${washmate.booking.cash-share-per-slot:0.5}")
    private double cashSharePerSlot;

    // Tài khoản chưa từng rửa xe xong lần nào bị siết riêng: kẻ phá luôn dùng tài khoản mới,
    // còn khách quen thì không bị ảnh hưởng.
    @Value("${washmate.booking.new-account.max-open:1}")
    private int newAccountMaxOpen;

    @Value("${washmate.booking.new-account.max-advance-days:2}")
    private int newAccountMaxAdvanceDays;

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
                        // expiresAt = hạn của MỘT PHIÊN thanh toán online, KHÔNG phải hạn của đơn.
                        // Nó chỉ được đặt khi khách thực sự bấm thanh toán (VnpayService.createPaymentUrl).
                        // Đặt ở đây là sai: khách đặt lịch cho tuần sau bằng VNPAY sẽ bị
                        // VnpayPaymentTimeoutScheduler huỷ đơn sau đúng timeout-minutes.
                        .expiresAt(null)
                        .build()));
        if (request.paymentMethod() != null && payment.getMethod() != request.paymentMethod()) {
            payment.setMethod(request.paymentMethod());
            payment.setExpiresAt(null);
            payment.setUpdatedAt(OffsetDateTime.now());
        }

        return BookingResponse.from(booking, payment, null);
    }

    /**
     * Lịch sử đặt lịch của khách, CÓ PHÂN TRANG.
     *
     * <p>Trước đây trả về toàn bộ lịch sử trong một lần gọi, kèm EntityGraph nạp 6 quan hệ —
     * khách dùng lâu năm vài trăm đơn thì payload rất nặng trong khi màn hình chỉ hiện được
     * vài đơn đầu. Đây lại là endpoint khách gọi nhiều nhất.
     */
    @Transactional(readOnly = true)
    public Page<BookingResponse> getMyBookings(AppUserDetails principal, Pageable pageable) {
        requireCustomer(principal);
        Page<Booking> bookingPage = bookingRepository.findByUserIdOrderByCreatedAtDesc(principal.getId(), pageable);
        if (bookingPage.isEmpty()) {
            return Page.empty(pageable);
        }

        List<Booking> bookings = bookingPage.getContent();
        List<Integer> bookingIds = bookings.stream().map(Booking::getId).toList();

        Map<Integer, Payment> paymentMap = paymentRepository.findByBookingIdIn(bookingIds).stream()
                .collect(Collectors.toMap(p -> p.getBooking().getId(), p -> p, (p1, p2) -> p1));

        Map<Integer, Invoice> invoiceMap = invoiceRepository.findByBookingIdIn(bookingIds).stream()
                .collect(Collectors.toMap(i -> i.getBooking().getId(), i -> i, (i1, i2) -> i1));

        return bookingPage.map(booking -> BookingResponse.from(
                booking,
                paymentMap.get(booking.getId()),
                invoiceMap.get(booking.getId())));
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
        // Nếu không chặn ở đây, kẻ phá lách được trần CASH bằng cách đặt vào slot còn suất
        // rồi dời đơn sang slot đã đầy suất tiền mặt.
        validateCashShareOnSlot(newSlot, newGarage, request.bookingDate(), payment.getMethod(), booking.getId());

        // Ghi nhận thay đổi về "khi nào / ở đâu" TRƯỚC khi ghi đè, để quyết định có phải
        // xin garage duyệt lại không.
        boolean scheduleChanged = garageChanged
                || !booking.getSlot().getId().equals(newSlot.getId())
                || !booking.getBookingDate().equals(request.bookingDate());

        booking.setGarage(newGarage);
        booking.setSlot(newSlot);
        booking.setService(newService);
        booking.setVehicle(newVehicle);
        booking.setBookingDate(request.bookingDate());

        // Khách tự dời lịch sang slot/ngày/garage khác thì đơn phải quay lại hàng chờ duyệt:
        // garage đã cam kết slot CŨ, không mặc nhiên phục vụ được slot MỚI.
        // Staff tự sửa thì giữ nguyên CONFIRMED vì chính họ là người duyệt.
        if (isOwner && !isStaff && scheduleChanged && booking.getStatus() == BookingStatus.CONFIRMED) {
            booking.setStatus(BookingStatus.PENDING);
            booking.setConfirmedAt(null);
        }

        BigDecimal newTotal = newService.getPrice();
        // Khi đổi garage hoặc service thì bối cảnh giảm giá thay đổi:
        // - promotion gắn theo garage & dùng-một-lần, và payload update không mang promotionId
        //   -> KHÔNG áp lại promotion cũ (tránh mang mã không hợp lệ sang garage/giá mới).
        // - tier discount tính lại theo garage & giá mới.
        // Nếu giữ nguyên garage và service -> giữ nguyên discount cũ.
        BigDecimal newDiscount;
        if (garageChanged || serviceChanged) {
            // Mã khuyến mãi cũ bị gỡ khỏi đơn -> phải TRẢ LẠI cho khách, nếu không khách vừa
            // mất mã (UNIQUE user_id+promotion_id chặn dùng lại) vừa không được giảm giá,
            // và quota usedCount của campaign bị đốt oan.
            promotionReleaseService.releaseForBooking(bookingId);
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
        // Số tiền/garage đã đổi -> phiên thanh toán online đang treo (nếu có) không còn hợp lệ.
        // Xoá hạn cũ để lần bấm thanh toán tiếp theo tạo phiên mới, thay vì kẹt ở hạn đã qua.
        payment.setExpiresAt(null);
        payment.setUpdatedAt(OffsetDateTime.now());

        // Attempt VNPAY đang treo mang số tiền CŨ -> nếu khách hoàn tất nó thì IPN sẽ lệch tiền.
        // Đóng lại để buộc tạo phiên mới với số tiền đúng.
        paymentTransactionRepository
                .findByPaymentIdAndStatus(payment.getId(), PaymentTransactionStatus.PENDING)
                .forEach(attempt -> attempt.setStatus(PaymentTransactionStatus.CANCELLED));

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
        // Xác nhận đơn đã quá giờ là thao tác vô nghĩa: BookingNoShowScheduler sẽ đánh
        // NO_SHOW ngay ở lần quét kế tiếp và khách nhận hai thông báo mâu thuẫn liên tiếp.
        validateSlotNotFinished(booking);
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
        validateCheckInWindow(booking);
        booking.setStatus(BookingStatus.CHECKED_IN);
        booking.setCheckinTime(OffsetDateTime.now());
        return responseWithPaymentAndInvoice(booking);
    }

    /**
     * Chỉ cho check-in quanh khung giờ hẹn. Không có ràng buộc này, staff bấm nhầm đơn của
     * ngày khác là đơn đó nhảy sang CHECKED_IN — thoát khỏi tầm quét của
     * {@code BookingNoShowScheduler} (chỉ quét CONFIRMED) và không còn đường đóng nào
     * ngoài abort, tức là chiếm slot vô thời hạn.
     */
    private void validateCheckInWindow(Booking booking) {
        BookingSlot slot = booking.getSlot();
        if (slot == null || slot.getStartTime() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(TimeZones.VIETNAM);
        LocalDateTime slotStart = LocalDateTime.of(booking.getBookingDate(), slot.getStartTime());

        if (now.isBefore(slotStart.minusMinutes(checkInEarlyMinutes))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Chưa tới giờ check-in, chỉ được check-in sớm tối đa " + checkInEarlyMinutes + " phút");
        }

        LocalDateTime slotEnd = slot.getEndTime() == null
                ? slotStart.plusHours(1)
                : LocalDateTime.of(booking.getBookingDate(), slot.getEndTime());
        if (now.isAfter(slotEnd)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Đã quá khung giờ của đơn này, không thể check-in");
        }
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
        // Khách tự huỷ thì phải trước giờ hẹn; staff/owner xử lý tại quầy nên không bị chặn.
        if (!canOperateGarage(booking, principal)) {
            validateCustomerCancelWindow(booking);
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
        validateNoShowDeadlinePassed(booking);

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

    /**
     * Garage dừng một đơn ĐANG PHỤC VỤ (CHECKED_IN/WASHING): khách bỏ về giữa chừng, xe
     * không đủ điều kiện, thiết bị hỏng...
     *
     * <p>Trước khi có API này, đơn ở CHECKED_IN/WASHING mà chưa thu được tiền là ngõ cụt
     * tuyệt đối: {@code complete} đòi payment PAID, {@code cancelBooking} chỉ nhận
     * PENDING/CONFIRMED, {@code markNoShow} chỉ nhận CONFIRMED, {@code rejectBooking} chỉ
     * nhận PENDING. Đơn kẹt vĩnh viễn mà vẫn bị tính vào capacity của slot (cả ở
     * CAPACITY_BLOCKING_STATUSES lẫn trigger DB check_booking_slot_capacity).
     *
     * <p>Đơn đã thanh toán KHÔNG đi đường này — phải hoàn tiền qua
     * {@code PaymentService.refundPayment} để tiền và trạng thái đơn luôn khớp nhau.
     */
    @Transactional
    public BookingResponse abortBooking(Integer bookingId, BookingAbortRequest request, AppUserDetails principal) {
        Booking booking = findDetailedBookingForUpdate(bookingId);
        authorizeGarageOperation(booking, principal);
        if (booking.getStatus() != BookingStatus.CHECKED_IN && booking.getStatus() != BookingStatus.WASHING) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Chỉ đơn đang phục vụ (CHECKED_IN/WASHING) mới cần dừng giữa chừng");
        }

        Payment payment = paymentRepository.findByBookingId(bookingId).orElse(null);
        if (payment != null && payment.getStatus() == PaymentStatus.PAID) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Đơn đã thanh toán, vui lòng hoàn tiền thay vì dừng đơn");
        }

        OffsetDateTime now = OffsetDateTime.now();
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(now);
        booking.setRejectionReason(request.reason());

        if (payment != null && payment.getStatus() == PaymentStatus.PENDING) {
            payment.setStatus(PaymentStatus.CANCELLED);
            payment.setUpdatedAt(now);
            recordPaymentTransaction(payment, PaymentTransactionStatus.CANCELLED, "MANUAL", null);
        }

        // Dịch vụ không hoàn tất -> mã khuyến mãi chưa thực sự được tiêu.
        promotionReleaseService.releaseForBooking(booking.getId());

        notificationRepository.save(Notification.builder()
                .userId(booking.getUser().getId())
                .bookingId(booking.getId())
                .title("Đơn đặt lịch đã dừng giữa chừng")
                .content(String.format("Đơn đặt lịch %s đã được garage dừng lại. Lý do: %s",
                        booking.getBookingCode(), request.reason()))
                .type("BOOKING_CONFIRMATION")
                .channel("IN_APP")
                .status("PENDING")
                .build());

        Invoice invoice = invoiceRepository.findByBookingId(bookingId).orElse(null);
        return BookingResponse.from(booking, payment, invoice);
    }

    /** Khung giờ của đơn đã trôi qua hoàn toàn -> không còn gì để xác nhận. */
    private void validateSlotNotFinished(Booking booking) {
        BookingSlot slot = booking.getSlot();
        if (slot == null || slot.getStartTime() == null) {
            return;
        }
        LocalDateTime slotEnd = slot.getEndTime() == null
                ? LocalDateTime.of(booking.getBookingDate(), slot.getStartTime()).plusHours(1)
                : LocalDateTime.of(booking.getBookingDate(), slot.getEndTime());
        if (LocalDateTime.now(TimeZones.VIETNAM).isAfter(slotEnd)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Khung giờ của đơn này đã trôi qua, không thể xác nhận");
        }
    }

    /**
     * No-show thủ công phải dùng ĐÚNG mốc của {@code BookingNoShowScheduler}
     * (giờ hẹn + no-show.grace-minutes). Nếu không, staff bấm được no-show cho đơn
     * chưa tới giờ, trong khi scheduler lại chờ đủ ân hạn — hai đường cùng ra NO_SHOW
     * mà luật khác nhau.
     */
    private void validateNoShowDeadlinePassed(Booking booking) {
        BookingSlot slot = booking.getSlot();
        if (slot == null || slot.getStartTime() == null) {
            return;
        }
        LocalDateTime deadline = LocalDateTime.of(booking.getBookingDate(), slot.getStartTime())
                .plusMinutes(noShowGraceMinutes);
        if (deadline.isAfter(LocalDateTime.now(TimeZones.VIETNAM))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Chưa quá giờ hẹn " + noShowGraceMinutes + " phút, chưa thể đánh dấu vắng mặt");
        }
    }

    /**
     * Khách chỉ được tự huỷ TRƯỚC giờ hẹn. Không có ràng buộc này thì khách không tới
     * chỉ cần vào bấm "huỷ" sau giờ hẹn là né được NO_SHOW — lịch sử sạch, còn garage
     * mất chỗ mà không ghi nhận được gì.
     *
     * <p>Staff/owner không bị chặn: họ cần quyền xử lý ngoại lệ tại quầy.
     */
    private void validateCustomerCancelWindow(Booking booking) {
        BookingSlot slot = booking.getSlot();
        if (slot == null || slot.getStartTime() == null) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.of(booking.getBookingDate(), slot.getStartTime())
                .minusMinutes(cancelCutoffMinutes);
        if (!LocalDateTime.now(TimeZones.VIETNAM).isBefore(cutoff)) {
            throw new ApiException(HttpStatus.CONFLICT, cancelCutoffMinutes > 0
                    ? "Chỉ được huỷ trước giờ hẹn ít nhất " + cancelCutoffMinutes
                            + " phút, vui lòng liên hệ garage"
                    : "Đã tới giờ hẹn nên không thể tự huỷ, vui lòng liên hệ garage");
        }
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
        // Tài khoản chưa từng hoàn tất đơn nào -> cửa sổ đặt trước bị rút ngắn, để kẻ phá
        // không giữ được lịch xa hàng tuần bằng tài khoản vừa lập.
        boolean newAccount = isNewAccount(customer.getId());
        int advanceWindow = resolveAdvanceWindowDays(customer.getId(), garage.getId());
        if (newAccount && newAccountMaxAdvanceDays > 0) {
            advanceWindow = Math.min(advanceWindow, newAccountMaxAdvanceDays);
        }

        validateBookingDate(request.bookingDate(), advanceWindow);
        validateSlotNotStarted(request.bookingDate(), slot);
        if (bookingRepository.existsActiveBookingForUserAndSlot(
                customer.getId(), slot.getId(), request.bookingDate(), CAPACITY_BLOCKING_STATUSES, null)) {
            throw new ApiException(HttpStatus.CONFLICT, "Bạn đã có lịch đặt cho khung giờ này");
        }
        validateNoShowStrikes(customer.getId());
        validateOpenBookingQuota(customer.getId(), garage.getId(), request.bookingDate(), newAccount);
        validateCashShareOnSlot(slot, garage, request.bookingDate(),
                request.paymentMethod() == null ? PaymentMethod.CASH : request.paymentMethod(), null);
        long activeBookings = bookingRepository.countActiveBookings(slot.getId(), garage.getId(), request.bookingDate(),
                CAPACITY_BLOCKING_STATUSES);
        if (activeBookings >= slot.getMaxCapacity()) {
            throw new ApiException(HttpStatus.CONFLICT, "Booking slot is full");
        }
    }

    /**
     * Chặn đặt khung giờ đã trôi qua, VÀ chặn đặt sát giờ: phải đặt trước giờ hẹn ít nhất
     * {@code minLeadMinutes} phút để garage kịp chuẩn bị và khách kịp di chuyển.
     */
    private void validateSlotNotStarted(LocalDate bookingDate, BookingSlot slot) {
        if (slot.getStartTime() == null) {
            return;
        }
        LocalDateTime slotStart = LocalDateTime.of(bookingDate, slot.getStartTime());
        LocalDateTime earliestBookable = LocalDateTime.now(TimeZones.VIETNAM).plusMinutes(minLeadMinutes);

        if (!slotStart.isAfter(earliestBookable)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, minLeadMinutes > 0
                    ? "Vui lòng đặt trước giờ hẹn ít nhất " + minLeadMinutes + " phút, hãy chọn khung giờ khác"
                    : "Khung giờ đã bắt đầu hoặc đã qua, vui lòng chọn khung giờ khác");
        }
    }

    /**
     * Giới hạn phần capacity của MỘT SLOT có thể bị đơn CASH chưa thanh toán chiếm.
     *
     * <p>Đây là lớp duy nhất KHÔNG tính theo tài khoản, nên là lớp duy nhất chống được kiểu
     * tấn công tạo hàng loạt tài khoản rác: mọi trần theo khách đều bị phá bằng cách lập thêm
     * khách, còn trần này thì bao nhiêu tài khoản cũng chỉ giành được đúng phần đã định.
     *
     * <p>Chỉ đếm đơn CASH vì đó là thứ duy nhất giữ chỗ MIỄN PHÍ tới hết khung giờ. Đơn VNPAY
     * chưa trả đã tự rụng sau {@code timeout-minutes}, nên kẻ phá muốn vượt qua hàng rào này
     * thì buộc phải thanh toán online thật — phá hoại lập tức tốn tiền.
     *
     * <p>Khách thật không bị chặn oan: khi phần CASH của slot đã đầy, họ vẫn đặt được khung
     * giờ đó bằng thanh toán online.
     */
    private void validateCashShareOnSlot(BookingSlot slot, Garage garage, LocalDate bookingDate,
            PaymentMethod method, Integer excludeBookingId) {
        if (method != PaymentMethod.CASH || cashSharePerSlot <= 0 || cashSharePerSlot >= 1) {
            return; // ngoài khoảng (0,1) = tắt
        }
        // Luôn chừa ít nhất 1 suất CASH để slot capacity nhỏ không bị khoá hoàn toàn.
        int cashQuota = Math.max(1, (int) Math.floor(slot.getMaxCapacity() * cashSharePerSlot));

        // Loại chính đơn đang sửa ngay trong câu query. Trước đó tôi trừ 1 ở tầng Java, nhưng
        // sai khi đơn đó đang nằm ở SLOT KHÁC (dời từ slot A sang slot B) — nó vốn không có
        // trong phép đếm của slot B, trừ đi thành nới lỏng hạn ngạch thêm một suất.
        long cashHeld = bookingRepository.countUnpaidCashBookingsOnSlot(
                slot.getId(), garage.getId(), bookingDate, CAPACITY_BLOCKING_STATUSES, excludeBookingId);

        if (cashHeld >= cashQuota) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Khung giờ này đã hết suất thanh toán tiền mặt. "
                            + "Vui lòng chọn thanh toán online hoặc chọn khung giờ khác.");
        }
    }

    /**
     * Siết riêng tài khoản chưa từng hoàn tất đơn nào.
     *
     * <p>Kẻ phá dùng tài khoản mới theo đúng định nghĩa — tạo hàng loạt rồi vứt. Khách quen
     * đã rửa xe xong ít nhất một lần thì không bị lớp này đụng tới. Nhờ vậy siết được rất
     * mạnh vào đúng nhóm đáng ngờ mà không làm phiền người dùng thật.
     *
     * @return true nếu là tài khoản mới (chưa có đơn COMPLETED nào)
     */
    private boolean isNewAccount(Integer customerId) {
        return bookingRepository.countCompletedBookingsForUser(customerId) == 0;
    }

    /**
     * Cấm đặt lịch khi khách đã vắng mặt quá nhiều lần gần đây.
     *
     * <p>Trần đơn đang mở KHÔNG bắt được kiểu phá hoại kiên trì: đặt 2 đơn, để trôi qua giờ
     * cho scheduler dọn, rồi đặt tiếp — lặp vô hạn mà không bao giờ chạm trần đơn treo, trong
     * khi mỗi vòng vẫn khoá slot của garage suốt cả khung giờ. Án tích vắng mặt mới là thứ
     * chặn được vòng lặp đó.
     *
     * <p>Án TỰ HẾT HẠN khi trôi ra ngoài cửa sổ {@code block-window-days}, nên khách lỡ hẹn
     * thật không bị cấm vĩnh viễn. Cần gỡ sớm hơn thì staff dùng
     * {@link #excuseNoShow(Integer, BookingAbortRequest, AppUserDetails)}.
     */
    private void validateNoShowStrikes(Integer customerId) {
        if (maxNoShowsBeforeBlock <= 0) {
            return; // 0 hoặc âm = tắt cơ chế
        }
        OffsetDateTime since = OffsetDateTime.now().minusDays(noShowBlockWindowDays);
        long noShows = bookingRepository.countRecentNoShows(customerId, since);
        if (noShows >= maxNoShowsBeforeBlock) {
            throw new ApiException(HttpStatus.FORBIDDEN, String.format(
                    "Tài khoản của bạn đã vắng mặt %d lần trong %d ngày gần đây nên tạm thời "
                            + "không thể đặt lịch. Vui lòng liên hệ garage để được hỗ trợ.",
                    noShows, noShowBlockWindowDays));
        }
    }

    /**
     * Gỡ án vắng mặt cho một đơn: chuyển NO_SHOW -> CANCELLED kèm lý do.
     *
     * <p>Cần thiết vì án tích chặn thẳng việc đặt lịch — khách lỡ hẹn vì ốm đau, tai nạn,
     * hay bị staff bấm nhầm mà không có đường kêu thì quá nặng tay. Đơn đã gỡ án rụng khỏi
     * phép đếm của {@code countRecentNoShows} ngay lập tức.
     *
     * <p>Giữ nguyên {@code noShowAt} để còn vết lịch sử là đơn này TỪNG bị đánh vắng mặt.
     */
    @Transactional
    public BookingResponse excuseNoShow(Integer bookingId, BookingAbortRequest request, AppUserDetails principal) {
        Booking booking = findDetailedBookingForUpdate(bookingId);
        authorizeGarageOperation(booking, principal);
        requireStatus(booking, BookingStatus.NO_SHOW, "Chỉ đơn đang ở trạng thái NO_SHOW mới cần gỡ án");

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(OffsetDateTime.now());
        booking.setRejectionReason("Gỡ án vắng mặt: " + request.reason());

        notificationRepository.save(Notification.builder()
                .userId(booking.getUser().getId())
                .bookingId(booking.getId())
                .title("Đã gỡ đánh dấu vắng mặt")
                .content(String.format("Đơn %s đã được gỡ đánh dấu vắng mặt. Lý do: %s",
                        booking.getBookingCode(), request.reason()))
                .type("BOOKING_CONFIRMATION")
                .channel("IN_APP")
                .status("PENDING")
                .build());

        return responseWithPaymentAndInvoice(booking);
    }

    /**
     * Hàng rào chống phá hoại, hai tầng.
     *
     * <p>{@code existsActiveBookingForUserAndSlot} chỉ chặn TRÙNG một slot, nên kẻ phá vẫn
     * rải được hàng chục đơn ở các slot/ngày khác nhau để giữ kín garage mà không trả đồng nào.
     *
     * <ul>
     *   <li><b>Trần tổng theo khách</b> — đếm đơn CHƯA thanh toán. Khách thật trả tiền xong
     *       thì đặt bao nhiêu cũng được; kẻ phá không trả tiền sẽ tắc ở đơn thứ N.</li>
     *   <li><b>Trần theo garage/ngày</b> — chặn dồn toàn bộ hạn mức vào đúng một garage
     *       trong một ngày cao điểm.</li>
     * </ul>
     *
     * <p>Hai tầng này chống được kẻ phá dùng MỘT tài khoản. Với kiểu tạo hàng loạt tài khoản
     * thì chốt chặn nằm ở chỗ khác: đăng ký phải xác thực OTP (UserStatus.PENDING_VERIFY ->
     * ACTIVE) và {@code AuthRateLimitFilter} giới hạn /api/auth/register theo IP.
     */
    private void validateOpenBookingQuota(Integer customerId, Integer garageId, LocalDate bookingDate,
            boolean newAccount) {
        List<BookingStatus> openStatuses = List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED);

        // Tài khoản mới bị siết chặt hơn hẳn: chưa chứng minh được gì thì chưa được giữ nhiều chỗ.
        int openLimit = newAccount && newAccountMaxOpen > 0
                ? Math.min(maxOpenBookingsPerCustomer, newAccountMaxOpen)
                : maxOpenBookingsPerCustomer;

        if (openLimit > 0) {
            long unpaidOpen = bookingRepository.countUnpaidOpenBookingsForUser(
                    customerId, openStatuses, LocalDate.now(TimeZones.VIETNAM));
            if (unpaidOpen >= openLimit) {
                throw new ApiException(HttpStatus.CONFLICT, newAccount
                        ? "Tài khoản mới chỉ được giữ " + openLimit + " lịch chưa thanh toán. "
                                + "Vui lòng hoàn tất lịch hiện tại trước khi đặt thêm."
                        : "Bạn đang có " + unpaidOpen + " lịch chưa thanh toán. "
                                + "Vui lòng thanh toán hoặc huỷ bớt trước khi đặt thêm");
            }
        }

        if (maxOpenBookingsPerGaragePerDay > 0) {
            long sameDay = bookingRepository.countOpenBookingsForUserInGarageOnDate(
                    customerId, garageId, bookingDate, openStatuses);
            if (sameDay >= maxOpenBookingsPerGaragePerDay) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Bạn đã đặt tối đa " + maxOpenBookingsPerGaragePerDay
                                + " lịch tại garage này trong ngày " + bookingDate);
            }
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
