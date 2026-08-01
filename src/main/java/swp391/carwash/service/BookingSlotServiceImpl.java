package swp391.carwash.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swp391.carwash.common.TimeZones;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.dto.request.BookingSlotCreateRequest;
import swp391.carwash.dto.response.BookingSlotResponse;
import swp391.carwash.entity.BookingSlot;
import swp391.carwash.entity.Garage;
import swp391.carwash.enums.BookingStatus;
import swp391.carwash.enums.GarageStatus;
import swp391.carwash.enums.RecordStatus;
import swp391.carwash.repository.BookingRepository;
import swp391.carwash.repository.BookingSlotRepository;
import swp391.carwash.repository.GarageRepository;
import swp391.carwash.security.AppUserDetails;

@Service
@RequiredArgsConstructor
@Transactional
public class BookingSlotServiceImpl implements BookingSlotService {

    private static final EnumSet<BookingStatus> OCCUPYING_STATUSES = EnumSet.of(
            BookingStatus.PENDING,
            BookingStatus.CONFIRMED,
            BookingStatus.CHECKED_IN,
            BookingStatus.WASHING);

    private final BookingSlotRepository bookingSlotRepository;
    private final BookingRepository bookingRepository;
    private final GarageRepository garageRepository;
    private final swp391.carwash.security.GarageAccessEvaluator garageAccessEvaluator;

    // Dùng chung với BookingService.validateSlotNotStarted — đổi một chỗ là cả hai cùng đổi.
    @Value("${washmate.booking.min-lead-minutes:30}")
    private int minLeadMinutes;

    @Override
    public BookingSlotResponse createSlot(Integer garageId, BookingSlotCreateRequest request,
            AppUserDetails principal) {
        authorizeGarage(garageId, principal);
        Garage garage = findActiveGarage(garageId);
        // Khoá dòng garage để việc "đọc hết slot -> kiểm chồng giờ -> chèn slot mới" diễn ra
        // tuần tự. Khoá từng slot không giải quyết được vì slot sắp tạo chưa tồn tại để khoá;
        // hai request tạo hai khung giờ chồng nhau (nhưng không trùng khít) sẽ cùng qua được
        // validateNoOverlap, mà UNIQUE của DB chỉ bắt trường hợp trùng khít.
        garageRepository.findByIdForUpdate(garageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Garage not found"));

        validateTimeRange(request.startTime(), request.endTime());
        validateNoOverlap(garageId, request.startTime(), request.endTime(), null);

        // deleteSlot chỉ xoá MỀM (status = DELETED) và giữ nguyên khung giờ, trong khi DB có
        // UNIQUE (garage_id, start_time, end_time) không kèm điều kiện status. Nên tạo lại đúng
        // khung giờ vừa xoá sẽ lọt qua validateNoOverlap (chỉ soi slot ACTIVE) rồi vỡ ở tầng DB
        // thành lỗi 500 khó hiểu.
        //
        // Hồi sinh slot cũ thay vì chèn dòng mới: booking lịch sử vẫn trỏ vào slot_id đó, tạo
        // slot mới sẽ làm lịch sử tham chiếu tới một khung giờ "đã chết" trong khi khung giờ
        // đang bán lại nằm ở id khác.
        BookingSlot existing = bookingSlotRepository
                .findByGarageIdAndStartTimeAndEndTime(garageId, request.startTime(), request.endTime())
                .orElse(null);
        if (existing != null) {
            existing.setMaxCapacity(request.maxCapacity());
            existing.setStatus(RecordStatus.ACTIVE);
            return mapToResponse(bookingSlotRepository.save(existing), 0L);
        }

        BookingSlot slot = BookingSlot.builder()
                .garage(garage)
                .startTime(request.startTime())
                .endTime(request.endTime())
                .maxCapacity(request.maxCapacity())
                .status(RecordStatus.ACTIVE)
                .build();

        return mapToResponse(bookingSlotRepository.save(slot), 0L);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingSlotResponse> getSlotsByGarageAndDate(Integer garageId, LocalDate date) {
        findActiveGarage(garageId);

        LocalDate today = LocalDate.now(TimeZones.VIETNAM);

        // Ngày đã qua thì KHÔNG còn slot nào đặt được -> trả rỗng.
        // Trước đây chỉ lọc khi date == hôm nay, nên hỏi ngày quá khứ vẫn trả về đủ slot
        // kèm available=true; khách bấm đặt mới ăn 400 từ validateBookingDate.
        if (date.isBefore(today)) {
            return List.of();
        }

        List<BookingSlot> slots = bookingSlotRepository
                .findByGarageIdAndStatusOrderByStartTimeAsc(garageId, RecordStatus.ACTIVE);
        Map<Integer, Long> bookedCountBySlot = bookedCountBySlot(garageId, date);

        // Nếu là hôm nay, ẩn các slot không còn đặt được. Ngưỡng phải KHỚP với
        // BookingService.validateSlotNotStarted (cùng property min-lead-minutes), nếu không
        // danh sách sẽ hiện slot mà bấm vào lại bị từ chối.
        boolean isToday = date.isEqual(today);
        LocalDateTime earliestBookable = LocalDateTime.now(TimeZones.VIETNAM).plusMinutes(minLeadMinutes);

        return slots.stream()
                .filter(slot -> !isToday
                        || slot.getStartTime() == null
                        || LocalDateTime.of(date, slot.getStartTime()).isAfter(earliestBookable))
                .map(slot -> mapToResponse(slot, bookedCountBySlot.getOrDefault(slot.getId(), 0L)))
                .toList();
    }

    @Override
    public BookingSlotResponse updateMaxCapacity(Integer slotId, Integer newMaxCapacity,
            AppUserDetails principal) {
        // Khóa bi quan dòng slot trước khi đọc số đơn đang giữ chỗ và cập nhật capacity,
        // tránh đua với booking mới khiến capacity bị hạ xuống dưới số đơn đang có.
        BookingSlot slot = findActiveSlotForUpdate(slotId);
        // Kiểm quyền SAU khi load slot vì phải biết slot thuộc garage nào mới kiểm được.
        authorizeGarage(slot.getGarage().getId(), principal);
        Long maxBookedCount = bookingRepository
                .countActiveBookingsByDateForSlotFromDate(slotId, LocalDate.now(TimeZones.VIETNAM), OCCUPYING_STATUSES)
                .stream()
                .max(Long::compareTo)
                .orElse(0L);

        if (newMaxCapacity < maxBookedCount) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Max capacity cannot be lower than existing active bookings");
        }

        slot.setMaxCapacity(newMaxCapacity);
        BookingSlot savedSlot = bookingSlotRepository.save(slot);
        long todayBookedCount = bookingRepository.countActiveBookings(
                savedSlot.getId(),
                savedSlot.getGarage().getId(),
                LocalDate.now(TimeZones.VIETNAM),
                OCCUPYING_STATUSES);
        return mapToResponse(savedSlot, todayBookedCount);
    }

    @Override
    public void deleteSlot(Integer slotId, AppUserDetails principal) {
        // Khoá bi quan dòng slot — CÙNG ổ khoá mà BookingService.createBooking dùng
        // (bookingSlotRepository.findByIdForUpdate). Không khoá thì có cửa sổ race giữa lúc
        // đếm đơn và lúc set DELETED: khách đặt chen vào giữa sẽ có booking gắn vào slot đã
        // bị xoá, và trigger check_booking_slot_capacity sẽ ném lỗi thô ra cho khách.
        BookingSlot slot = findActiveSlotForUpdate(slotId);
        authorizeGarage(slot.getGarage().getId(), principal);
        Long futureActiveBookings = bookingRepository
                .countActiveBookingsByDateForSlotFromDate(slotId, LocalDate.now(TimeZones.VIETNAM), OCCUPYING_STATUSES)
                .stream()
                .mapToLong(Long::longValue)
                .sum();

        if (futureActiveBookings > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot delete slot with active bookings");
        }

        slot.setStatus(RecordStatus.DELETED);
        bookingSlotRepository.save(slot);
    }

    /**
     * Chặn STAFF của garage này thao tác lên slot của garage khác.
     *
     * <p>{@code @PreAuthorize("hasAnyRole('ADMIN','OWNER','STAFF')")} ở controller chỉ trả lời
     * được "người này có phải nhân viên không", KHÔNG trả lời được "nhân viên của garage nào".
     * Thiếu lớp kiểm này thì bất kỳ STAFF nào cũng tạo/sửa/XOÁ được slot của mọi garage trong
     * hệ thống. Dùng đúng GarageAccessEvaluator mà BookingService/PaymentService đang dùng:
     * OWNER/ADMIN toàn quyền, STAFF chỉ trong các garage được phân công (garageIds ở JWT).
     */
    private void authorizeGarage(Integer garageId, AppUserDetails principal) {
        if (!garageAccessEvaluator.canOperate(garageId, principal)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Bạn không có quyền quản lý khung giờ của garage này");
        }
    }

    private Garage findActiveGarage(Integer garageId) {
        return garageRepository.findById(garageId)
                .filter(garage -> garage.getStatus() == GarageStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Garage not found"));
    }

    private BookingSlot findActiveSlotForUpdate(Integer slotId) {
        return bookingSlotRepository.findByIdForUpdate(slotId)
                .filter(slot -> slot.getStatus() == RecordStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Booking slot not found"));
    }

    private Map<Integer, Long> bookedCountBySlot(Integer garageId, LocalDate date) {
        return bookingRepository.countActiveBookingsGroupBySlot(garageId, date, OCCUPYING_STATUSES)
                .stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row[0]).intValue(),
                        row -> ((Number) row[1]).longValue()));
    }

    private void validateTimeRange(LocalTime startTime, LocalTime endTime) {
        if (!startTime.isBefore(endTime)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Start time must be before end time");
        }
    }

    private void validateNoOverlap(Integer garageId, LocalTime startTime, LocalTime endTime, Integer excludedSlotId) {
        boolean overlaps = bookingSlotRepository
                .findByGarageIdAndStatusOrderByStartTimeAsc(garageId, RecordStatus.ACTIVE)
                .stream()
                .filter(slot -> excludedSlotId == null || !slot.getId().equals(excludedSlotId))
                .anyMatch(slot -> startTime.isBefore(slot.getEndTime()) && endTime.isAfter(slot.getStartTime()));

        if (overlaps) {
            throw new ApiException(HttpStatus.CONFLICT, "Booking slot time overlaps an existing slot");
        }
    }

    private BookingSlotResponse mapToResponse(BookingSlot slot, Long bookedCapacity) {
        long availableCapacity = Math.max(0L, slot.getMaxCapacity().longValue() - bookedCapacity);
        return new BookingSlotResponse(
                slot.getId(),
                slot.getGarage().getId(),
                slot.getStartTime(),
                slot.getEndTime(),
                slot.getMaxCapacity(),
                bookedCapacity,
                availableCapacity,
                availableCapacity > 0);
    }
}
