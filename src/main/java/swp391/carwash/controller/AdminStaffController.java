package swp391.carwash.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import swp391.carwash.dto.MeResponse;
import swp391.carwash.dto.request.Staff.CreateStaffRequest;
import swp391.carwash.dto.request.Staff.UpdateStaffAssignmentRequest;
import swp391.carwash.service.AdminStaffService;

/**
 * Path chứa segment "admin" nên SecurityConfig.isAdminRequest() đã tự giới hạn ADMIN/OWNER.
 */
@RestController
@RequestMapping("/api/admin/staff")
@RequiredArgsConstructor
@Tag(name = "Admin Staff Management", description = "Tạo tài khoản nhân sự vận hành và gán chi nhánh")
public class AdminStaffController {
    private final AdminStaffService adminStaffService;

    @PostMapping
    @Operation(summary = "Tạo tài khoản STAFF/MANAGER và gán vào chi nhánh")
    public ResponseEntity<MeResponse> createStaff(@Valid @RequestBody CreateStaffRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminStaffService.createStaff(request));
    }

    @PutMapping("/{userId}/assignment")
    @Operation(summary = "Đổi vai trò vận hành và danh sách chi nhánh của một tài khoản")
    public ResponseEntity<MeResponse> updateAssignment(
            @PathVariable Integer userId,
            @Valid @RequestBody UpdateStaffAssignmentRequest request) {
        return ResponseEntity.ok(adminStaffService.updateAssignment(userId, request));
    }
}
