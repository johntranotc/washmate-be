package swp391.carwash.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
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

    @Override
    public BookingSlotResponse createSlot(Integer garageId, BookingSlotCreateRequest request) {
        Garage garage = findActiveGarage(garageId);
        validateTimeRange(request.startTime(), request.endTime());
        validateNoOverlap(garageId, request.startTime(), request.endTime(), null);

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

        List<BookingSlot> slots = bookingSlotRepository
                .findByGarageIdAndStatusOrderByStartTimeAsc(garageId, RecordStatus.ACTIVE);
        Map<Integer, Long> bookedCountBySlot = bookedCountBySlot(garageId, date);

        // Nếu là hôm nay, ẩn các slot đã qua giờ bắt đầu (khớp với validateSlotNotStarted khi đặt),
        // để hiển thị không lệch với khả năng đặt thực tế.
        boolean isToday = date.isEqual(LocalDate.now(TimeZones.VIETNAM));
        LocalTime nowTime = LocalTime.now(TimeZones.VIETNAM);

        return slots.stream()
                .filter(slot -> !(isToday
                        && slot.getStartTime() != null
                        && !slot.getStartTime().isAfter(nowTime)))
                .map(slot -> mapToResponse(slot, bookedCountBySlot.getOrDefault(slot.getId(), 0L)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingSlot> getSlotsByGarageId(Integer garageId) {
        findActiveGarage(garageId);
        return bookingSlotRepository.findByGarageIdAndStatusOrderByStartTimeAsc(garageId, RecordStatus.ACTIVE);
    }

    @Override
    public BookingSlotResponse updateMaxCapacity(Integer slotId, Integer newMaxCapacity) {
        // Khóa bi quan dòng slot trước khi đọc số đơn đang giữ chỗ và cập nhật capacity,
        // tránh đua với booking mới khiến capacity bị hạ xuống dưới số đơn đang có.
        BookingSlot slot = findActiveSlotForUpdate(slotId);
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
    public void deleteSlot(Integer slotId) {
        BookingSlot slot = findActiveSlot(slotId);
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

    private Garage findActiveGarage(Integer garageId) {
        return garageRepository.findById(garageId)
                .filter(garage -> garage.getStatus() == GarageStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Garage not found"));
    }

    private BookingSlot findActiveSlot(Integer slotId) {
        return bookingSlotRepository.findById(slotId)
                .filter(slot -> slot.getStatus() == RecordStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Booking slot not found"));
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
