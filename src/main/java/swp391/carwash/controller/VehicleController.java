package swp391.carwash.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import swp391.carwash.dto.request.vehicles.CreateMyVehicleRequest;
import swp391.carwash.dto.request.vehicles.CreateVehicleRequest;
import swp391.carwash.dto.request.vehicles.UpdateVehicleRequest;
import swp391.carwash.dto.response.vehicles.VehicleResponse;
import swp391.carwash.security.AppUserDetails;
import swp391.carwash.service.VehicleService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/vehicles")
@RequiredArgsConstructor
@Tag(name = "Vehicle Management", description = "APIs quản lý thông tin phương tiện/xe của khách hàng")
public class VehicleController {

    private final VehicleService vehicleService;

    // Endpoint này nhận userId tuỳ ý từ body -> chỉ nhân viên/quản trị được dùng.
    // Khách hàng tự thêm xe qua POST /my-vehicles (lấy userId từ token).
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'STAFF', 'MANAGER')")
    @Operation(summary = "Thêm mới xe dựa vào id_user (Chỉ Admin/Staff)", description = "Tạo mới thông tin phương tiện gắn liền với mã người dùng")
    public ResponseEntity<VehicleResponse> createVehicle(@Valid @RequestBody CreateVehicleRequest request) {
        VehicleResponse response = vehicleService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')") // 🔒 CHỈ ADMIN HOẶC STAFF MỚI ĐƯỢC LẤY HẾT XE
    @Operation(summary = "Tìm kiếm tất cả xe có trong hệ thống (Chỉ Admin/Staff)", description = "Lấy danh sách toàn bộ phương tiện của tất cả người dùng")
    public ResponseEntity<List<VehicleResponse>> getAllVehicles() {
        return ResponseEntity.ok(vehicleService.getAll());
    }

    // ⭐ THAY THẾ CHO API THEO USERID CŨ
    @GetMapping("/my-vehicles")
    @PreAuthorize("hasRole('CUSTOMER')") // 🔒 Chỉ khách hàng mới gọi API này
    @Operation(summary = "Lấy danh sách xe của tôi (Dựa vào Token)", description = "Tự động trích xuất thông tin người dùng từ Token để lấy danh sách phương tiện sở hữu")
    public ResponseEntity<List<VehicleResponse>> getMyVehicles(Authentication authentication) {
        // Lấy username/email của người đang đăng nhập từ Token
        String currentUserEmail = authentication.getName();

        // Bạn sẽ cần vào VehicleService viết thêm hàm getByEmail(currentUserEmail) nhé
        List<VehicleResponse> responses = vehicleService.getByEmail(currentUserEmail);

        return ResponseEntity.ok(responses);
    }

    @PostMapping("/my-vehicles")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<VehicleResponse> createMyVehicle(
            @Valid @RequestBody CreateMyVehicleRequest request, // Dùng DTO không có userId
            @AuthenticationPrincipal AppUserDetails principal
            ) {
        Integer currentUserId = principal.getId();
        VehicleResponse response = vehicleService.createVehicleForUser(currentUserId, request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PutMapping("/{vehicleId}")
    @Operation(summary = "Cập nhật thông tin xe bằng ID của xe", description = "Chỉnh sửa thông tin chi tiết của một phương tiện theo mã xe")
    public ResponseEntity<VehicleResponse> updateVehicle(
            @PathVariable Integer vehicleId,
            @Valid @RequestBody UpdateVehicleRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        VehicleResponse response = vehicleService.update(vehicleId, request, principal);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{vehicleId}")
    @Operation(summary = "Xóa xe theo ID của xe", description = "Xóa bỏ một phương tiện ra khỏi hệ thống dựa theo mã xe")
    public ResponseEntity<Void> deleteVehicle(
            @PathVariable Integer vehicleId,
            @AuthenticationPrincipal AppUserDetails principal) {
        vehicleService.delete(vehicleId, principal);
        return ResponseEntity.noContent().build();
    }

}