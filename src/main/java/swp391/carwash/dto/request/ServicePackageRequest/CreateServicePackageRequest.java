package swp391.carwash.dto.request.ServicePackageRequest;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * Trước đây class này KHÔNG có annotation validation nào và controller cũng không có {@code @Valid}
 * -> price null gây 409, price ÂM tạo ra booking.totalAmount âm rồi payment.amount âm.
 */
@Getter
@Setter
public class CreateServicePackageRequest {

    @NotNull(message = "garageId không được để trống")
    private Integer garageId; // Ép FE truyền Id cụ thể của chi nhánh

    @NotBlank(message = "Tên gói dịch vụ không được để trống")
    @Size(max = 255, message = "Tên gói dịch vụ không được vượt quá 255 ký tự")
    private String name;

    @Size(max = 2000, message = "Mô tả không được vượt quá 2000 ký tự")
    private String description;

    // Cột DB là DECIMAL(10,2) -> chặn ngay ở đây để không vỡ constraint khi lưu.
    @NotNull(message = "Giá không được để trống")
    @DecimalMin(value = "0.00", message = "Giá không được âm")
    @Digits(integer = 8, fraction = 2, message = "Giá không hợp lệ (tối đa 8 chữ số phần nguyên, 2 số thập phân)")
    private BigDecimal price;

    @NotNull(message = "Thời lượng không được để trống")
    @Min(value = 1, message = "Thời lượng phải lớn hơn 0 phút")
    private Integer durationMinutes;
}
