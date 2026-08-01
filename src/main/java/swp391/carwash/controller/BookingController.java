package swp391.carwash.controller;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import swp391.carwash.dto.BookingAbortRequest;
import swp391.carwash.dto.BookingCreateRequest;
import swp391.carwash.dto.BookingRejectRequest;
import swp391.carwash.dto.BookingResponse;
import swp391.carwash.dto.BookingUpdateRequest;
import swp391.carwash.enums.BookingStatus;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import swp391.carwash.security.AppUserDetails;
import swp391.carwash.service.BookingService;

@RestController
@RequiredArgsConstructor
public class BookingController {
    private final BookingService bookingService;

    /**
     * Trả về dạng phân trang (Page): JSON có {@code content}, {@code totalElements},
     * {@code totalPages}... Client cũ đọc thẳng mảng cần đổi sang đọc {@code content}.
     */
    @GetMapping("/api/bookings/me")
    public Page<BookingResponse> getMyBookings(
            Pageable pageable,
            @AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.getMyBookings(principal, pageable);
    }

    @PostMapping("/api/bookings")
    public BookingResponse createBooking(
            @Valid @RequestBody BookingCreateRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.createBooking(request, principal);
    }

    @GetMapping("/api/bookings")
    public Page<BookingResponse> getBookings(
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) Integer garageId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            Pageable pageable,
            @AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.getBookings(status, garageId, fromDate, toDate, pageable, principal);
    }

    @PutMapping("/api/bookings/{id}")
    public BookingResponse updateBooking(
            @PathVariable Integer id,
            @Valid @RequestBody BookingUpdateRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.updateBooking(id, request, principal);
    }

    @GetMapping("/api/bookings/{id}")
    public BookingResponse getBooking(
            @PathVariable Integer id,
            @AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.getBooking(id, principal);
    }

    @PostMapping("/api/bookings/{id}/confirm")
    public BookingResponse confirmBooking(
            @PathVariable Integer id,
            @AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.confirmBooking(id, principal);
    }

    @PostMapping("/api/bookings/{id}/reject")
    public BookingResponse rejectBooking(
            @PathVariable Integer id,
            @Valid @RequestBody BookingRejectRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.rejectBooking(id, request, principal);
    }

    @PostMapping("/api/bookings/{id}/check-in")
    public BookingResponse checkIn(
            @PathVariable Integer id,
            @AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.checkIn(id, principal);
    }

    @PostMapping("/api/bookings/{id}/start-washing")
    public BookingResponse startWashing(
            @PathVariable Integer id,
            @AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.startWashing(id, principal);
    }

    @PostMapping("/api/bookings/{id}/complete")
    public BookingResponse complete(
            @PathVariable Integer id,
            @AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.complete(id, principal);
    }

    @PostMapping("/api/bookings/{id}/cancel")
    public BookingResponse cancelBooking(
            @PathVariable Integer id,
            @AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.cancelBooking(id, principal);
    }

    @PostMapping("/api/bookings/{id}/no-show")
    public BookingResponse markNoShow(
            @PathVariable Integer id,
            @AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.markNoShow(id, principal);
    }

    /** Garage dừng đơn ĐANG PHỤC VỤ (CHECKED_IN/WASHING) — khách bỏ về, xe không đủ điều kiện... */
    @PostMapping("/api/bookings/{id}/abort")
    public BookingResponse abortBooking(
            @PathVariable Integer id,
            @Valid @RequestBody BookingAbortRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.abortBooking(id, request, principal);
    }

    /**
     * Gỡ án vắng mặt cho một đơn (NO_SHOW -> CANCELLED). Đường kêu oan cho khách bị chặn
     * đặt lịch do lỡ hẹn có lý do chính đáng hoặc bị staff bấm nhầm.
     */
    @PostMapping("/api/bookings/{id}/excuse-no-show")
    public BookingResponse excuseNoShow(
            @PathVariable Integer id,
            @Valid @RequestBody BookingAbortRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        return bookingService.excuseNoShow(id, request, principal);
    }
}
