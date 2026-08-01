package swp391.carwash.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.dto.request.BookingSlotCreateRequest;
import swp391.carwash.dto.response.BookingSlotResponse;
import swp391.carwash.entity.BookingSlot;
import swp391.carwash.entity.Garage;
import swp391.carwash.enums.GarageStatus;
import swp391.carwash.enums.RecordStatus;
import swp391.carwash.repository.BookingRepository;
import swp391.carwash.repository.BookingSlotRepository;
import swp391.carwash.repository.GarageRepository;
import swp391.carwash.security.AppUserDetails;

@ExtendWith(MockitoExtension.class)
class BookingSlotServiceImplTest {

    @Mock
    private BookingSlotRepository bookingSlotRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private GarageRepository garageRepository;
    @Mock
    private AppUserDetails principal;

    private BookingSlotServiceImpl service;

    @BeforeEach
    void setUp() {
        // GarageAccessEvaluator là logic thuần, dùng bản thật để test phân quyền có ý nghĩa.
        service = new BookingSlotServiceImpl(
                bookingSlotRepository, bookingRepository, garageRepository,
                new swp391.carwash.security.GarageAccessEvaluator());
    }

    private Garage garage() {
        return Garage.builder()
                .id(1).name("Garage").address("Addr").phone("0900000000")
                .status(GarageStatus.ACTIVE)
                .build();
    }

    /**
     * Hồi quy: deleteSlot chỉ xoá MỀM và giữ nguyên khung giờ, còn DB có
     * UNIQUE (garage_id, start_time, end_time) không kèm status. Tạo lại đúng khung giờ vừa
     * xoá từng lọt qua validate (chỉ soi slot ACTIVE) rồi vỡ ở tầng DB thành lỗi 500.
     */
    @Test
    void createSlotRevivesSoftDeletedSlotInsteadOfInsertingDuplicate() {
        Garage garage = garage();
        BookingSlot deleted = BookingSlot.builder()
                .id(30).garage(garage)
                .startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(9, 0))
                .maxCapacity(4)
                .status(RecordStatus.DELETED)
                .build();

        when(principal.getRoleNames()).thenReturn(List.of("OWNER"));
        when(garageRepository.findById(1)).thenReturn(Optional.of(garage));
        when(garageRepository.findByIdForUpdate(1)).thenReturn(Optional.of(garage));
        // validateNoOverlap chỉ nhìn slot ACTIVE -> không thấy slot đã xoá mềm.
        when(bookingSlotRepository.findByGarageIdAndStatusOrderByStartTimeAsc(1, RecordStatus.ACTIVE))
                .thenReturn(List.of());
        when(bookingSlotRepository.findByGarageIdAndStartTimeAndEndTime(
                1, LocalTime.of(8, 0), LocalTime.of(9, 0)))
                .thenReturn(Optional.of(deleted));
        when(bookingSlotRepository.save(any(BookingSlot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BookingSlotResponse response = service.createSlot(1,
                new BookingSlotCreateRequest(LocalTime.of(8, 0), LocalTime.of(9, 0), 6), principal);

        // Dùng lại đúng slot cũ -> booking lịch sử vẫn trỏ đúng khung giờ đang bán.
        assertEquals(30, response.slotId());
        assertEquals(RecordStatus.ACTIVE, deleted.getStatus());
        assertEquals(6, deleted.getMaxCapacity());
    }

    /**
     * deleteSlot phải khoá bi quan dòng slot — CÙNG ổ khoá mà BookingService.createBooking
     * dùng — nếu không sẽ có cửa sổ race: khách đặt chen vào giữa lúc đếm đơn và lúc set
     * DELETED, tạo ra booking gắn vào slot đã bị xoá.
     */
    @Test
    void deleteSlotLocksSlotRowBeforeChecking() {
        BookingSlot slot = BookingSlot.builder()
                .id(30).garage(garage())
                .startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(9, 0))
                .maxCapacity(4)
                .status(RecordStatus.ACTIVE)
                .build();

        when(principal.getRoleNames()).thenReturn(List.of("OWNER"));
        when(bookingSlotRepository.findByIdForUpdate(30)).thenReturn(Optional.of(slot));
        when(bookingRepository.countActiveBookingsByDateForSlotFromDate(anyInt(), any(), any()))
                .thenReturn(List.of());
        when(bookingSlotRepository.save(any(BookingSlot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.deleteSlot(30, principal);

        assertEquals(RecordStatus.DELETED, slot.getStatus());
        verify(bookingSlotRepository).findByIdForUpdate(30);
        verify(bookingSlotRepository, never()).findById(30);
    }

    /**
     * Hồi quy phân quyền: {@code @PreAuthorize("hasAnyRole(...,'STAFF')")} ở controller chỉ
     * biết người này CÓ PHẢI nhân viên không, không biết nhân viên CỦA GARAGE NÀO. Thiếu lớp
     * kiểm ở service thì STAFF garage 2 xoá được slot của garage 1.
     */
    @Test
    void deleteSlotRejectsStaffFromAnotherGarage() {
        BookingSlot slot = BookingSlot.builder()
                .id(30).garage(garage())          // slot thuộc garage 1
                .startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(9, 0))
                .maxCapacity(4)
                .status(RecordStatus.ACTIVE)
                .build();

        when(bookingSlotRepository.findByIdForUpdate(30)).thenReturn(Optional.of(slot));
        when(principal.getRoleNames()).thenReturn(List.of("STAFF"));
        when(principal.getGarageIds()).thenReturn(List.of(2));   // chỉ phụ trách garage 2

        ApiException exception = assertThrows(ApiException.class,
                () -> service.deleteSlot(30, principal));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertEquals(RecordStatus.ACTIVE, slot.getStatus());
        verify(bookingSlotRepository, never()).save(any(BookingSlot.class));
    }

    /** Tương tự với tạo slot: STAFF garage 2 không được mở khung giờ cho garage 1. */
    @Test
    void createSlotRejectsStaffFromAnotherGarage() {
        when(principal.getRoleNames()).thenReturn(List.of("STAFF"));
        when(principal.getGarageIds()).thenReturn(List.of(2));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.createSlot(1,
                        new BookingSlotCreateRequest(LocalTime.of(8, 0), LocalTime.of(9, 0), 4), principal));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        // Chặn TRƯỚC khi chạm DB.
        verify(garageRepository, never()).findByIdForUpdate(anyInt());
        verify(bookingSlotRepository, never()).save(any(BookingSlot.class));
    }
}
