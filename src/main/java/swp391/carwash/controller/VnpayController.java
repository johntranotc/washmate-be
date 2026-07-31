package swp391.carwash.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import swp391.carwash.dto.VnpayIpnResponse;
import swp391.carwash.dto.VnpayPaymentUrlResponse;
import swp391.carwash.security.AppUserDetails;
import swp391.carwash.service.VnpayService;

@RestController
@RequiredArgsConstructor
public class VnpayController {
    private final VnpayService vnpayService;

    @PostMapping("/api/payments/{id}/vnpay/create-url")
    public VnpayPaymentUrlResponse createPaymentUrl(
            @PathVariable Integer id,
            @AuthenticationPrincipal AppUserDetails principal,
            HttpServletRequest request) {
        return vnpayService.createPaymentUrl(id, principal, resolveClientIp(request));
    }

    // Sau reverse proxy/load balancer, getRemoteAddr() là IP của proxy.
    // Ưu tiên IP client thật ở đầu chuỗi X-Forwarded-For nếu có.
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @GetMapping("/api/payments/vnpay/ipn")
    public VnpayIpnResponse handleIpn(@RequestParam Map<String, String> parameters) {
        return vnpayService.handleIpn(parameters);
    }

    @GetMapping("/api/payments/vnpay/return")
    public ResponseEntity<Void> handleReturn(@RequestParam Map<String, String> parameters) {
        URI redirect = vnpayService.buildReturnRedirect(parameters);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, redirect.toString())
                .build();
    }
}
