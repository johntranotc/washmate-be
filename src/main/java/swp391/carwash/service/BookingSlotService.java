package swp391.carwash.service;

import java.time.LocalDate;
import java.util.List;
import swp391.carwash.dto.request.BookingSlotCreateRequest;
import swp391.carwash.dto.response.BookingSlotResponse;
import swp391.carwash.security.AppUserDetails;

public interface BookingSlotService {

    // Các thao tác ghi đều nhận principal: @PreAuthorize ở controller chỉ kiểm tra ROLE,
    // không biết người đó thuộc garage nào. Phạm vi garage phải được kiểm ở tầng service.
    BookingSlotResponse createSlot(Integer garageId, BookingSlotCreateRequest request, AppUserDetails principal);

    List<BookingSlotResponse> getSlotsByGarageAndDate(Integer garageId, LocalDate date);

    BookingSlotResponse updateMaxCapacity(Integer slotId, Integer newMaxCapacity, AppUserDetails principal);

    void deleteSlot(Integer slotId, AppUserDetails principal);
}
