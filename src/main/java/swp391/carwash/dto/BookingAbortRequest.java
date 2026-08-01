package swp391.carwash.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Lý do garage phải dừng một đơn ĐANG PHỤC VỤ (CHECKED_IN/WASHING): khách bỏ về giữa
 * chừng, xe không đủ điều kiện, thiết bị hỏng... Bắt buộc có lý do vì đây là thao tác
 * đóng đơn ngoài quy trình bình thường và cần vết để đối soát.
 */
public record BookingAbortRequest(
        @NotBlank
        @Size(max = 500)
        String reason
) {
}
