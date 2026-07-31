package swp391.carwash.controller;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.dto.insight.AutoWashInsightsResponse;
import swp391.carwash.dto.insight.BusinessInsightResponse;
import swp391.carwash.dto.insight.InsightGenerateRequest;
import swp391.carwash.dto.insight.InsightGenerateResponse;
import swp391.carwash.dto.insight.InsightRuleConfigResponse;
import swp391.carwash.dto.insight.InsightRuleConfigUpdateRequest;
import swp391.carwash.dto.insight.InsightStatusUpdateRequest;
import swp391.carwash.enums.InsightStatus;
import swp391.carwash.enums.InsightType;
import swp391.carwash.service.InsightRuleConfigService;
import swp391.carwash.service.InsightService;
import swp391.carwash.service.AIInsightService;
import swp391.carwash.dto.insight.AIInsightEnrichResponse;
import swp391.carwash.entity.BusinessInsight;

@RestController
@RequiredArgsConstructor
public class OwnerInsightController {
    private final InsightService insightService;
    private final InsightRuleConfigService insightRuleConfigService;
    private final AIInsightService aiInsightService;

    @GetMapping("/api/owner/insights")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public AutoWashInsightsResponse getInsights(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) InsightType type,
            @RequestParam(required = false) String status,
            // Bỏ trống = xem mọi phạm vi (toàn hệ thống + của từng garage). Truyền garageId
            // để chỉ xem insight riêng của garage đó (do AI deep-analysis sinh ra).
            @RequestParam(required = false) Integer garageId) {
        return insightService.getInsights(fromDate, toDate, type, parseStatusFilter(status), garageId);
    }

    @GetMapping("/api/owner/insights/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public BusinessInsightResponse getInsightDetail(@PathVariable Integer id) {
        BusinessInsight insight = insightService.getInsightById(id);
        AIInsightEnrichResponse aiEnrichment = aiInsightService.getAIEnrichmentByInsightId(id);
        return BusinessInsightResponse.from(insight, aiEnrichment);
    }

    @PostMapping("/api/owner/insights/generate")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public InsightGenerateResponse generateInsights(@Valid @RequestBody InsightGenerateRequest request) {
        return insightService.generateInsights(request.fromDate(), request.toDate());
    }

    @PatchMapping("/api/owner/insights/{id}/status")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public BusinessInsightResponse updateInsightStatus(
            @PathVariable Integer id,
            @Valid @RequestBody InsightStatusUpdateRequest request) {
        return insightService.updateStatus(id, request.status());
    }

    @GetMapping("/api/owner/insight-rules")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public List<InsightRuleConfigResponse> getInsightRules() {
        return insightRuleConfigService.getRuleConfigs();
    }

    @PatchMapping("/api/owner/insight-rules/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public InsightRuleConfigResponse updateInsightRule(
            @PathVariable Integer id,
            @Valid @RequestBody InsightRuleConfigUpdateRequest request) {
        return insightRuleConfigService.updateRuleConfig(id, request);
    }

    private InsightStatus parseStatusFilter(String status) {
        if (!StringUtils.hasText(status) || "ALL".equalsIgnoreCase(status.trim())) {
            return null;
        }
        try {
            return InsightStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid insight status filter");
        }
    }
}
