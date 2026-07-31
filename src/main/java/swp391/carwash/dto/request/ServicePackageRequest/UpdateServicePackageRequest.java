package swp391.carwash.dto.request.ServicePackageRequest;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * Xem ghi chú ở {@link CreateServicePackageRequest}. Riêng {@code status} trước đây bị
 * {@code RecordStatus.valueOf(null)} gây NPE 500 khi FE không truyền — nay bắt buộc và
 * chỉ nhận ACTIVE/INACTIVE (xoá phải dùng endpoint DELETE).
 */
@Getter
@Setter
public class UpdateServicePackageRequest {

    @NotBlank(message = "Tên gói dịch vụ không được để trống")
    @Size(max = 255, message = "Tên gói dịch vụ không được vượt quá 255 ký tự")
    private String name;

    @Size(max = 2000, message = "Mô tả không được vượt quá 2000 ký tự")
    private String description;

    @NotNull(message = "Giá không được để trống")
    @DecimalMin(value = "0.00", message = "Giá không được âm")
    @Digits(integer = 8, fraction = 2, message = "Giá không hợp lệ (tối đa 8 chữ số phần nguyên, 2 số thập phân)")
    private BigDecimal price;

    @NotNull(message = "Thời lượng không được để trống")
    @Min(value = 1, message = "Thời lượng phải lớn hơn 0 phút")
    private Integer durationMinutes;

    @NotBlank(message = "Trạng thái không được để trống")
    @Pattern(regexp = "^(ACTIVE|INACTIVE)$", message = "Trạng thái chỉ nhận ACTIVE hoặc INACTIVE")
    private String status; // Bật/tắt gói dịch vụ. Xoá dùng DELETE /api/v1/services/{id}
}
