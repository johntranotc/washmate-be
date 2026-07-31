package swp391.carwash.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import swp391.carwash.dto.response.vehicles.BehavioralLogResponse;
import swp391.carwash.dto.response.vehicles.CustomerSegmentResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics & Dashboard", description = "APIs báo cáo dữ liệu kinh doanh, log hành vi và phân khúc")
public class AnalyticsController {

    @GetMapping("/garage-owner/dashboard")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER','MANAGER')")
    @Operation(summary = "Lấy các số liệu tổng quan (Metrics) cho chủ Garage",
            description = "Bao gồm: Doanh thu ngày/tháng, số lượng Booking theo trạng thái, slot thịnh hành")
//    public ResponseEntity<GarageDashboardMetrics> getGarageDashboard(
//            @RequestParam Integer garageId,
//            @RequestParam String monthYear // Định dạng YYYY-MM
//    ) {
//        // Gọi sang analyticsService.getGarageMetrics(garageId, monthYear)
//        return ResponseEntity.ok(new GarageDashboardMetrics());
//    }
    public ResponseEntity<Map<String, Object>> getGarageDashboard() {
        Map<String, Object> response = new HashMap<>();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/admin/behavioral-logs")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    @Operation(summary = "Admin xem dữ liệu log hành vi đặt lịch hàng tháng của người dùng")
    public ResponseEntity<List<BehavioralLogResponse>> getBehavioralLogs(
            @RequestParam(required = false) Integer userId,
            @RequestParam(required = false) String monthYear) {
        // Gọi sang analyticsService.getMonthlyBehaviorLogs(userId, monthYear)
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/admin/customer-segments")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    @Operation(summary = "Admin xem phân khúc khách hàng (RFM) tính toán theo tháng")
    public ResponseEntity<List<CustomerSegmentResponse>> getCustomerSegments(
            @RequestParam(required = false) String segmentName,
            @RequestParam(required = false) String monthYear) {
        // Gọi sang analyticsService.getCustomerSegments(segmentName, monthYear)
        return ResponseEntity.ok(List.of());
    }
}
