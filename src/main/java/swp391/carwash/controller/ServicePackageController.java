package swp391.carwash.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import swp391.carwash.dto.request.ServicePackageRequest.CreateServicePackageRequest;
import swp391.carwash.dto.request.ServicePackageRequest.UpdateServicePackageRequest;
import swp391.carwash.dto.response.ServicePackage.ServicePackageResponse;
import swp391.carwash.service.ServicePackageService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/services")
@RequiredArgsConstructor
@Tag(name = "Service Package Management", description = "APIs quản lý danh mục gói dịch vụ rửa xe của các garage")
public class ServicePackageController {

    private final ServicePackageService servicePackageService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    @Operation(summary = "Tạo gói dịch vụ mới")
    public ResponseEntity<ServicePackageResponse> createService(
            @Valid @RequestBody CreateServicePackageRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            swp391.carwash.security.AppUserDetails principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(servicePackageService.createService(request, principal));
    }

    @GetMapping("/garage/{garageId}")
    @Operation(summary = "Lấy toàn bộ gói dịch vụ của một Garage cụ thể")
    public ResponseEntity<List<ServicePackageResponse>> getServicesByGarageId(@PathVariable Long garageId) {
        return ResponseEntity.ok(servicePackageService.getServicesByGarageId(garageId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Lấy thông tin chi tiết của 1 gói dịch vụ")
    public ResponseEntity<ServicePackageResponse> getServiceById(@PathVariable Long id) {
        return ResponseEntity.ok(servicePackageService.getServiceById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    @Operation(summary = "Chỉnh sửa thông tin gói dịch vụ")
    public ResponseEntity<ServicePackageResponse> updateService(
            @PathVariable Long id,
            @Valid @RequestBody UpdateServicePackageRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            swp391.carwash.security.AppUserDetails principal) {
        return ResponseEntity.ok(servicePackageService.updateService(id, request, principal));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    @Operation(summary = "Xóa gói dịch vụ")
    public ResponseEntity<Void> deleteService(
            @PathVariable Long id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            swp391.carwash.security.AppUserDetails principal) {
        servicePackageService.deleteService(id, principal);
        return ResponseEntity.noContent().build();
    }
}
