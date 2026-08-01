package swp391.carwash.controller;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import swp391.carwash.dto.request.BookingSlotCapacityUpdateRequest;
import swp391.carwash.dto.request.BookingSlotCreateRequest;
import swp391.carwash.dto.response.BookingSlotResponse;
import swp391.carwash.security.AppUserDetails;
import swp391.carwash.service.BookingSlotService;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BookingSlotController {

    private final BookingSlotService bookingSlotService;

    @GetMapping("/garages/{garageId}/slots")
    public ResponseEntity<List<BookingSlotResponse>> getSlotsByGarageAndDate(
            @PathVariable Integer garageId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(bookingSlotService.getSlotsByGarageAndDate(garageId, date));
    }

    // Mở khung giờ là quyết định kinh doanh -> chỉ ADMIN/OWNER. STAFF là nhân viên tại quầy,
    // việc của họ là VẬN HÀNH đơn (xác nhận, check-in, rửa, thu tiền), không phải cấu hình lịch.
    //
    // Vẫn truyền principal xuống service: @PreAuthorize chỉ trả lời được "vai trò gì", không
    // trả lời được "được phép đụng garage nào". Hai câu hỏi khác nhau, cần hai lớp khác nhau.
    @PostMapping("/garages/{garageId}/slots")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ResponseEntity<BookingSlotResponse> createSlot(
            @PathVariable Integer garageId,
            @Valid @RequestBody BookingSlotCreateRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(bookingSlotService.createSlot(garageId, request, principal));
    }

    @PutMapping("/slots/{slotId}/capacity")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ResponseEntity<BookingSlotResponse> updateSlotCapacity(
            @PathVariable Integer slotId,
            @Valid @RequestBody BookingSlotCapacityUpdateRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        return ResponseEntity.ok(
                bookingSlotService.updateMaxCapacity(slotId, request.maxCapacity(), principal));
    }

    @DeleteMapping("/slots/{slotId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ResponseEntity<Void> deleteSlot(
            @PathVariable Integer slotId,
            @AuthenticationPrincipal AppUserDetails principal) {
        bookingSlotService.deleteSlot(slotId, principal);
        return ResponseEntity.noContent().build();
    }
}
