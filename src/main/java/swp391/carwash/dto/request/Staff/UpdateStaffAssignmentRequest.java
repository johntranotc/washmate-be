package swp391.carwash.dto.request.Staff;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Đổi vai trò vận hành và/hoặc danh sách chi nhánh của một tài khoản đã tồn tại.
 * garageIds là danh sách THAY THẾ toàn bộ (không phải thêm dồn).
 */
public record UpdateStaffAssignmentRequest(
        @NotNull String role,
        @NotEmpty List<Integer> garageIds
) {
}
