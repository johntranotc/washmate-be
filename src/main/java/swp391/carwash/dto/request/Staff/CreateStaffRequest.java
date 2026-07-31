package swp391.carwash.dto.request.Staff;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Admin/Owner tạo tài khoản vận hành (STAFF hoặc MANAGER) và gán vào chi nhánh.
 * Bắt buộc có ít nhất 1 garage: trigger validate_assigned_staff_role trong DB yêu cầu
 * nhân viên được gán cho booking phải là STAFF ACTIVE CÙNG garage, nên staff không
 * thuộc chi nhánh nào thì không thao tác được gì.
 */
public record CreateStaffRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(min = 6, max = 100) String password,
        @NotBlank String fullName,
        @NotBlank @Pattern(regexp = "^[0-9+]{9,20}$") String phone,
        @NotNull String role,
        @NotEmpty List<Integer> garageIds
) {
}
