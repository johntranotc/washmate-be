package swp391.carwash.controller;


import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import swp391.carwash.dto.request.Garages.CreateGarageRequest;
import swp391.carwash.dto.request.Garages.UpdateGarageRequest;
import swp391.carwash.dto.response.Garages.GarageResponse;
import swp391.carwash.entity.Garage;
import swp391.carwash.service.GarageService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/garages")
@Tag(name = "Garage Management", description = "APIs quản lý danh sách và thông tin các chi nhánh Garage rửa xe")
public class GarageController {

    private final GarageService garageService;

    public GarageController(GarageService garageService) {
        this.garageService = garageService;
    }

    // 1. API Tạo mới một Garage (Hứng CreateGarageRequest, trả GarageResponse)
    // POST http://localhost:8080/api/v1/garages
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    @Operation(summary = "Tạo mới một Garage", description = "Thêm mới một chi nhánh garage vào hệ thống kèm dữ liệu validation")
    public ResponseEntity<GarageResponse> createGarage(@Valid @RequestBody CreateGarageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(garageService.createGarage(request));
    }

    // 2. API Lấy danh sách toàn bộ Garage
    // GET http://localhost:8080/api/v1/garages
    @GetMapping
    @Operation(summary = "Lấy danh sách toàn bộ Garage", description = "Trả về danh sách tất cả các chi nhánh garage hiện có")
    public ResponseEntity<List<Garage>> getAllGarages() {
        return ResponseEntity.ok(garageService.getAllGarages());
    }

    // 3. API Lấy chi tiết 1 Garage theo ID
    // GET http://localhost:8080/api/v1/garages/{id}
    @GetMapping("/{id}")
    @Operation(summary = "Lấy chi tiết 1 Garage theo ID", description = "Tìm kiếm thông tin chi tiết chi nhánh garage theo ID")
    public ResponseEntity<Garage> getGarageById(@PathVariable Integer id) {
        return garageService.getGarageById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // 4. API Cập nhật thông tin Garage

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    @Operation(summary = "Cập nhật thông tin Garage")
    public ResponseEntity<GarageResponse> updateGarage(
            @PathVariable Integer id,
            @Valid @RequestBody UpdateGarageRequest request) {
        try {
            return ResponseEntity.ok(garageService.updateGarage(id, request));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // 5. API Xóa một Garage
    // DELETE http://localhost:8080/api/v1/garages/{id}
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    @Operation(summary = "Xóa một Garage", description = "Xóa thông tin chi nhánh garage ra khỏi hệ thống theo ID")
    public ResponseEntity<Void> deleteGarage(@PathVariable Integer id) {
        try {
            garageService.deleteGarage(id);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
