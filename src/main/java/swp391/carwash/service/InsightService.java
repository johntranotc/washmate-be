package swp391.carwash.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.dto.insight.AutoWashInsightsResponse;
import swp391.carwash.dto.insight.BusinessInsightResponse;
import swp391.carwash.dto.insight.InsightGenerateResponse;
import swp391.carwash.dto.insight.InsightResponse;
import swp391.carwash.entity.BusinessInsight;
import swp391.carwash.entity.InsightRuleConfig;
import swp391.carwash.enums.InsightAnalysisStatus;
import swp391.carwash.enums.InsightStatus;
import swp391.carwash.enums.InsightType;
import swp391.carwash.repository.BusinessInsightRepository;
import swp391.carwash.repository.InsightRuleConfigRepository;
import swp391.carwash.service.insight.InsightAnalysisContext;
import swp391.carwash.service.insight.InsightRuleConfigRegistry;
import swp391.carwash.service.insight.InsightRuleEngine;

@Service
@Slf4j
@RequiredArgsConstructor
public class InsightService {
    private final ReportAggregationService reportAggregationService;
    private final InsightRuleEngine insightRuleEngine;
    private final BusinessInsightRepository businessInsightRepository;
    private final InsightRuleConfigRepository insightRuleConfigRepository;

    @Transactional
    public AutoWashInsightsResponse getInsights(
            LocalDate fromDate,
            LocalDate toDate,
            InsightType type,
            InsightStatus status) {
        return getInsights(fromDate, toDate, type, status, null);
    }

    /**
     * @param garageId filter tuỳ chọn theo phạm vi insight. null = xem mọi phạm vi
     *                 (toàn hệ thống + của từng garage), giữ nguyên hành vi API cũ.
     */
    @Transactional
    public AutoWashInsightsResponse getInsights(
            LocalDate fromDate,
            LocalDate toDate,
            InsightType type,
            InsightStatus status,
            Integer garageId) {
        DateRange range = resolveRange(fromDate, toDate);
        InsightAnalysisContext context = reportAggregationService.aggregate(range.from(), range.to());

        InsightType persistedTypeFilter = normalizeType(type);
        List<BusinessInsight> persisted = businessInsightRepository
                .findForOwnerInsights(range.from(), range.to(), persistedTypeFilter, status, garageId);

        // Auto-generate: nếu kỳ này chưa có insight nào được lưu nhưng có dữ liệu kinh doanh,
        // chạy rule engine + lưu ngay để lần đọc này trả về kết quả (không cần bấm "Làm mới phân tích").
        //
        // Hai request GET đồng thời trên cùng kỳ có thể cùng thấy persisted rỗng và cùng generate
        // -> request chậm hơn sẽ vi phạm uq_business_insight_rule_period. Đó không phải lỗi thật:
        // request kia đã lưu xong, nên chỉ cần đọc lại thay vì để 500 trên endpoint đọc.
        if (persisted.isEmpty() && context.current().hasBusinessData()) {
            try {
                generateAndPersist(range, context);
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                log.info("Một request khác đã generate insight cho kỳ {} - {}, đọc lại kết quả",
                        range.from(), range.to());
            }
            persisted = businessInsightRepository
                    .findForOwnerInsights(range.from(), range.to(), persistedTypeFilter, status, garageId);
        }

        List<BusinessInsightResponse> insights = persisted.stream()
                .sorted(insightComparator())
                .map(BusinessInsightResponse::from)
                .toList();

        InsightAnalysisStatus analysisStatus = context.current().hasBusinessData()
                ? InsightAnalysisStatus.READY
                : InsightAnalysisStatus.INSUFFICIENT_DATA;
        String message = insights.isEmpty()
                ? "Không đủ dữ liệu hoặc chưa có insight đã lưu cho khoảng thời gian này."
                : "Đã tải danh sách insight đã lưu.";

        return new AutoWashInsightsResponse(
                context.current().period(),
                context.current().toSummary(),
                insights,
                analysisStatus,
                message);
    }

    @Transactional
    public InsightGenerateResponse generateInsights(LocalDate fromDate, LocalDate toDate) {
        DateRange range = resolveRange(fromDate, toDate);
        InsightAnalysisContext baseContext = reportAggregationService.aggregate(range.from(), range.to());
        if (!baseContext.current().hasBusinessData()) {
            return new InsightGenerateResponse(
                    baseContext.current().period(),
                    0,
                    0,
                    0,
                    List.of(),
                    "Không đủ dữ liệu để phân tích insight trong khoảng thời gian này.");
        }

        GenerationResult generation = generateAndPersist(range, baseContext);

        List<BusinessInsightResponse> responses = generation.insights().stream()
                .sorted(insightComparator())
                .map(BusinessInsightResponse::from)
                .toList();
        return new InsightGenerateResponse(
                baseContext.current().period(),
                generation.generatedCount(),
                generation.createdCount(),
                generation.updatedCount(),
                responses,
                "Đã generate và lưu AutoWash Insights.");
    }

    /**
     * Chạy rule engine cho kỳ đang xét rồi upsert kết quả vào DB.
     * Dùng chung cho cả {@link #generateInsights} (nút "Làm mới phân tích", scheduler)
     * và {@link #getInsights} (auto-generate khi kỳ chưa có insight nào).
     */
    private GenerationResult generateAndPersist(DateRange range, InsightAnalysisContext baseContext) {
        List<InsightRuleConfig> ruleConfigs = insightRuleConfigRepository.findAll();
        InsightAnalysisContext context = new InsightAnalysisContext(
                baseContext.current(),
                baseContext.previous(),
                OffsetDateTime.now(),
                InsightRuleConfigRegistry.from(ruleConfigs));
        List<InsightResponse> candidates = insightRuleEngine.generate(context, InsightType.ALL);

        int createdCount = 0;
        int updatedCount = 0;
        java.util.ArrayList<BusinessInsight> savedInsights = new java.util.ArrayList<>();
        for (InsightResponse candidate : candidates) {
            UpsertResult result = upsertInsight(candidate, range);
            if (result.created()) {
                createdCount++;
            } else {
                updatedCount++;
            }
            savedInsights.add(result.insight());
        }
        return new GenerationResult(savedInsights, candidates.size(), createdCount, updatedCount);
    }

    @Transactional
    public BusinessInsightResponse updateStatus(Integer id, InsightStatus status) {
        BusinessInsight insight = businessInsightRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Insight not found"));
        insight.setStatus(status);
        insight.setUpdatedAt(OffsetDateTime.now());
        return BusinessInsightResponse.from(businessInsightRepository.save(insight));
    }

    @Transactional(readOnly = true)
    public BusinessInsight getInsightById(Integer id) {
        return businessInsightRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Insight not found"));
    }

    private UpsertResult upsertInsight(InsightResponse candidate, DateRange range) {
        // Rule engine tính trên dữ liệu TOÀN HỆ THỐNG (reportAggregationService.aggregate không
        // truyền garageId) -> phạm vi của insight là null. Phải tra đúng nhánh null để không
        // đụng vào insight per-garage do AI deep-analysis sinh ra trong cùng kỳ.
        BusinessInsight insight = businessInsightRepository
                .findByRuleCodeAndScopeAndPeriod(candidate.id(), null, range.from(), range.to())
                .orElse(null);
        boolean created = insight == null;
        if (created) {
            insight = BusinessInsight.builder()
                    .ruleCode(candidate.id())
                    .garageId(null)
                    .fromDate(range.from())
                    .toDate(range.to())
                    .status(InsightStatus.NEW)
                    .createdAt(candidate.createdAt())
                    .build();
        }

        insight.setType(candidate.type());
        insight.setSeverity(candidate.severity());
        insight.setTitle(candidate.title());
        insight.setSummary(candidate.summary());
        insight.setEvidence(candidate.evidence());
        insight.setMeaning(candidate.meaning());
        insight.setRecommendation(candidate.recommendation());
        insight.setRelatedMetric(candidate.relatedMetric());
        insight.setUpdatedAt(OffsetDateTime.now());
        return new UpsertResult(businessInsightRepository.save(insight), created);
    }

    private DateRange resolveRange(LocalDate fromDate, LocalDate toDate) {
        LocalDate today = LocalDate.now(swp391.carwash.common.TimeZones.VIETNAM);
        LocalDate resolvedTo = toDate != null ? toDate : today;
        LocalDate resolvedFrom = fromDate != null ? fromDate : resolvedTo.withDayOfMonth(1);

        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "fromDate must be before or equal to toDate");
        }

        return new DateRange(resolvedFrom, resolvedTo);
    }

    private InsightType normalizeType(InsightType type) {
        return type == null || type == InsightType.ALL ? null : type;
    }

    private Comparator<BusinessInsight> insightComparator() {
        return Comparator
                .comparingInt((BusinessInsight insight) -> insight.getSeverity().getPriority())
                .thenComparing(BusinessInsight::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }

    private record UpsertResult(BusinessInsight insight, boolean created) {
    }

    private record GenerationResult(
            List<BusinessInsight> insights,
            int generatedCount,
            int createdCount,
            int updatedCount) {
    }
}
