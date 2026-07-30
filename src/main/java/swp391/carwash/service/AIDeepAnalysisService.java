package swp391.carwash.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import swp391.carwash.common.exception.ApiException;
import swp391.carwash.config.GeminiProperties;
import swp391.carwash.dto.insight.AICampaignSuggestion;
import swp391.carwash.dto.insight.AIDeepAnalysisRejection;
import swp391.carwash.dto.insight.AIDeepAnalysisRequest;
import swp391.carwash.dto.insight.AIDeepAnalysisResponse;
import swp391.carwash.dto.insight.AIDeepAnalysisResult;
import swp391.carwash.dto.insight.AIDetectedInsight;
import swp391.carwash.dto.insight.AIInsightEnrichResponse;
import swp391.carwash.dto.insight.BusinessInsightResponse;
import swp391.carwash.entity.BusinessInsight;
import swp391.carwash.entity.InsightAIEnrichment;
import swp391.carwash.entity.InsightAnalysisRun;
import swp391.carwash.entity.InsightRuleConfig;
import swp391.carwash.enums.ComparisonOperator;
import swp391.carwash.enums.InsightSeverity;
import swp391.carwash.enums.InsightSource;
import swp391.carwash.enums.InsightStatus;
import swp391.carwash.enums.InsightType;
import swp391.carwash.repository.BusinessInsightRepository;
import swp391.carwash.repository.InsightAIEnrichmentRepository;
import swp391.carwash.repository.InsightAnalysisRunRepository;
import swp391.carwash.repository.InsightRuleConfigRepository;
import swp391.carwash.security.AppUserDetails;
import swp391.carwash.security.GarageAccessEvaluator;
import swp391.carwash.service.insight.MetricSnapshot;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIDeepAnalysisService {
    private final MetricSnapshotBuilder metricSnapshotBuilder;
    private final AiInsightVerifier aiInsightVerifier;
    private final AIPromptBuilderService promptBuilderService;
    private final GeminiClient geminiClient;
    private final AIResponseValidatorService validatorService;
    private final BusinessInsightRepository businessInsightRepository;
    private final InsightRuleConfigRepository insightRuleConfigRepository;
    private final InsightAIEnrichmentRepository aiEnrichmentRepository;
    private final InsightAnalysisRunRepository analysisRunRepository;
    private final GarageAccessEvaluator garageAccessEvaluator;
    private final GeminiProperties geminiProperties;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    // KHÔNG @Transactional ở đây: lời gọi Gemini (HTTP tới ~15s) phải nằm NGOÀI transaction
    // để không giữ kết nối/transaction DB mở suốt thời gian gọi API bên ngoài.
    public AIDeepAnalysisResponse analyze(AIDeepAnalysisRequest request, AppUserDetails principal) {
        AIDeepAnalysisRequest safeRequest = request == null ? new AIDeepAnalysisRequest(null, null, null) : request;
        DateRange range = resolveRange(safeRequest.fromDate(), safeRequest.toDate());
        Integer garageId = resolveGarageScope(safeRequest.garageId(), principal);
        MetricSnapshot snapshot = metricSnapshotBuilder.build(range.from(), range.to(), garageId);

        if (isInsufficient(snapshot)) {
            return new AIDeepAnalysisResponse(
                    snapshot.period(),
                    garageId,
                    null,
                    0,
                    0,
                    0,
                    List.of(),
                    List.of(),
                    "Not enough aggregate data for AI deep analysis.");
        }

        String snapshotJson = promptBuilderService.buildSnapshotJson(snapshot);
        String prompt = promptBuilderService.buildDeepAnalysisPrompt(snapshotJson);

        // 1) Gọi Gemini + validate NGOÀI transaction.
        final String rawResponse;
        final AIDeepAnalysisResult result;
        try {
            rawResponse = geminiClient.generateContent(prompt);
            result = validatorService.parseAndValidateDetectedInsights(rawResponse);
        } catch (IllegalArgumentException e) {
            log.error("Validation error for AI deep-analysis response: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.valueOf(422), "AI deep-analysis response invalid: " + e.getMessage());
        } catch (ResponseStatusException | ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error generating AI deep analysis", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Error connecting to AI service");
        }

        // 2) Chỉ mở transaction NGẮN để verify + ghi DB (không có I/O bên ngoài trong này).
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            return tx.execute(status ->
                    persistAnalysis(result, snapshot, range, garageId, principal.getId(), rawResponse));
        } catch (ResponseStatusException | ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error saving AI deep-analysis data", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error saving AI deep-analysis data");
        }
    }

    private AIDeepAnalysisResponse persistAnalysis(
            AIDeepAnalysisResult result,
            MetricSnapshot snapshot,
            DateRange range,
            Integer garageId,
            Integer requestedBy,
            String rawResponse) {
        List<AIDetectedInsight> candidates = result.insights();
        List<BusinessInsightResponse> savedInsights = new ArrayList<>();
        List<AIDeepAnalysisRejection> rejected = new ArrayList<>();

        for (AIDetectedInsight candidate : candidates) {
            AiInsightVerifier.VerificationResult verification = aiInsightVerifier.verify(candidate, snapshot);
            if (!verification.accepted()) {
                rejected.add(new AIDeepAnalysisRejection(
                        candidate.claim(),
                        candidate.evidence() != null ? candidate.evidence().metric() : null,
                        verification.reason()));
                continue;
            }

            BusinessInsight businessInsight = saveBusinessInsight(candidate, verification, range, garageId);
            InsightAIEnrichment enrichment;
            try {
                enrichment = saveEnrichment(candidate, verification, businessInsight);
            } catch (JsonProcessingException e) {
                log.error("Error serializing AI deep-analysis enrichment", e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error saving AI deep-analysis data");
            }
            savedInsights.add(BusinessInsightResponse.from(businessInsight, toResponse(enrichment)));
        }

        InsightAnalysisRun run = analysisRunRepository.save(InsightAnalysisRun.builder()
                .garageId(garageId)
                .requestedBy(requestedBy)
                .periodFrom(range.from())
                .periodTo(range.to())
                .aiModel(geminiProperties.getModel())
                .promptVersion(geminiProperties.getPrompt().getVersion())
                .rawResponse(rawResponse)
                .totalReturned(candidates.size())
                .totalKept(savedInsights.size())
                .totalRejected(rejected.size())
                .build());

        return new AIDeepAnalysisResponse(
                snapshot.period(),
                garageId,
                run.getId(),
                candidates.size(),
                savedInsights.size(),
                rejected.size(),
                List.copyOf(savedInsights),
                List.copyOf(rejected),
                "AI deep analysis completed with backend-verified evidence.");
    }

    private BusinessInsight saveBusinessInsight(
            AIDetectedInsight candidate,
            AiInsightVerifier.VerificationResult verification,
            DateRange range,
            Integer garageId) {
        String ruleCode = ruleCodeFor(candidate.evidence().metric());
        ensureRuleConfig(ruleCode, candidate, verification.dbValue());

        // Phải tra theo ĐÚNG garageId: metric được tính riêng cho garage này, nếu tra không
        // theo phạm vi thì sẽ ghi đè insight của garage khác trong cùng kỳ.
        BusinessInsight insight = businessInsightRepository
                .findByRuleCodeAndScopeAndPeriod(ruleCode, garageId, range.from(), range.to())
                .orElseGet(() -> BusinessInsight.builder()
                        .ruleCode(ruleCode)
                        .garageId(garageId)
                        .fromDate(range.from())
                        .toDate(range.to())
                        .status(InsightStatus.NEW)
                        .createdAt(OffsetDateTime.now())
                        .build());

        InsightType type = classifyType(candidate.type(), candidate.evidence().metric());
        InsightSeverity severity = InsightSeverity.valueOf(candidate.severity().trim().toUpperCase());
        insight.setType(type);
        insight.setSeverity(severity);
        insight.setTitle(truncate(candidate.claim(), 255));
        insight.setSummary(candidate.claim());
        insight.setEvidence("Verified metric " + candidate.evidence().metric() + " = " + verification.dbValue()
                + " for " + candidate.evidence().period());
        insight.setMeaning("AI-detected pattern from backend aggregate metrics. Evidence value was verified before saving.");
        insight.setRecommendation(candidate.suggestedAction());
        insight.setRelatedMetric(truncate(candidate.evidence().metric(), 100));
        insight.setUpdatedAt(OffsetDateTime.now());
        return businessInsightRepository.save(insight);
    }

    private InsightAIEnrichment saveEnrichment(
            AIDetectedInsight candidate,
            AiInsightVerifier.VerificationResult verification,
            BusinessInsight businessInsight) throws JsonProcessingException {
        InsightAIEnrichment enrichment = aiEnrichmentRepository.findByBusinessInsightId(businessInsight.getId())
                .orElseGet(InsightAIEnrichment::new);

        enrichment.setBusinessInsight(businessInsight);
        enrichment.setAiSummary(candidate.claim());
        enrichment.setAiExplanation("Backend verified " + candidate.evidence().metric()
                + " = " + verification.dbValue() + " before saving this AI-detected insight.");
        enrichment.setAiRecommendation(objectMapper.writeValueAsString(List.of(candidate.suggestedAction())));
        enrichment.setAiCampaignSuggestion(objectMapper.writeValueAsString(AICampaignSuggestion.builder()
                .campaignName("Verified AI insight action")
                .targetCustomers("Customers in the selected aggregate scope")
                .offer(candidate.suggestedAction())
                .duration("Next 7 days")
                .goal("Improve the verified metric: " + candidate.evidence().metric())
                .build()));
        enrichment.setConfidenceScore(new BigDecimal("0.80"));
        enrichment.setAiModel(geminiProperties.getModel());
        enrichment.setPromptVersion(geminiProperties.getPrompt().getVersion());
        enrichment.setSource(InsightSource.AI_DETECTED);
        enrichment.setEvidenceJson(enrichedEvidence(candidate, verification));
        enrichment.setVerified(true);
        enrichment.setGeneratedAt(OffsetDateTime.now());
        return aiEnrichmentRepository.save(enrichment);
    }

    private Map<String, Object> enrichedEvidence(
            AIDetectedInsight candidate,
            AiInsightVerifier.VerificationResult verification) {
        Map<String, Object> evidence = new LinkedHashMap<>(verification.evidenceJson());
        evidence.put("type", candidate.type());
        evidence.put("severity", candidate.severity());
        evidence.put("claim", candidate.claim());
        evidence.put("source", InsightSource.AI_DETECTED.name());
        return evidence;
    }

    private void ensureRuleConfig(String ruleCode, AIDetectedInsight candidate, BigDecimal dbValue) {
        if (insightRuleConfigRepository.findByRuleCode(ruleCode).isPresent()) {
            return;
        }

        insightRuleConfigRepository.save(InsightRuleConfig.builder()
                .ruleCode(ruleCode)
                .ruleName(truncate("AI detected: " + candidate.evidence().metric(), 255))
                .type(classifyType(candidate.type(), candidate.evidence().metric()))
                .thresholdValue(BigDecimal.ZERO)
                .comparisonOperator(ComparisonOperator.EQUAL)
                .severity(InsightSeverity.valueOf(candidate.severity().trim().toUpperCase()))
                .active(false)
                .description("Synthetic inactive rule config used only to satisfy BusinessInsight FK for AI-detected insight. Verified value: " + dbValue)
                .createdAt(OffsetDateTime.now())
                .build());
    }

    private AIInsightEnrichResponse toResponse(InsightAIEnrichment enrichment) {
        try {
            List<String> recommendation = objectMapper.readValue(enrichment.getAiRecommendation(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            AICampaignSuggestion campaignSuggestion = objectMapper.readValue(
                    enrichment.getAiCampaignSuggestion(),
                    AICampaignSuggestion.class);

            return AIInsightEnrichResponse.builder()
                    .insightId(enrichment.getBusinessInsight().getId())
                    .aiSummary(enrichment.getAiSummary())
                    .aiExplanation(enrichment.getAiExplanation())
                    .aiRecommendation(recommendation)
                    .aiCampaignSuggestion(campaignSuggestion)
                    .confidenceScore(enrichment.getConfidenceScore())
                    .aiModel(enrichment.getAiModel())
                    .promptVersion(enrichment.getPromptVersion())
                    .source(enrichment.getSource())
                    .evidence(enrichment.getEvidenceJson())
                    .verified(enrichment.getVerified())
                    .generatedAt(enrichment.getGeneratedAt())
                    .build();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error reading AI enrichment data");
        }
    }

    private Integer resolveGarageScope(Integer requestedGarageId, AppUserDetails principal) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        List<String> roles = principal.getRoleNames();
        if (roles.contains("OWNER") || roles.contains("ADMIN")) {
            return requestedGarageId;
        }
        if (requestedGarageId != null) {
            if (!garageAccessEvaluator.canOperate(requestedGarageId, principal)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "You cannot analyze this garage");
            }
            return requestedGarageId;
        }
        List<Integer> garageIds = principal.getGarageIds();
        if (garageIds.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "No garage scope assigned");
        }
        if (garageIds.size() == 1) {
            return garageIds.get(0);
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "garageId is required when you manage multiple garages");
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

    private boolean isInsufficient(MetricSnapshot snapshot) {
        BigDecimal totalOrders = snapshot.valueOf("total_orders");
        BigDecimal totalRevenue = snapshot.valueOf("total_revenue");
        return (totalOrders == null || totalOrders.compareTo(BigDecimal.ZERO) <= 0)
                && (totalRevenue == null || totalRevenue.compareTo(BigDecimal.ZERO) <= 0);
    }

    private String ruleCodeFor(String metric) {
        String sanitized = metric == null ? "UNKNOWN" : metric.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        String ruleCode = "AI_" + sanitized;
        return truncate(ruleCode, 100);
    }

    private InsightType classifyType(String type, String metric) {
        String value = ((type == null ? "" : type) + "_" + (metric == null ? "" : metric)).toUpperCase(Locale.ROOT);
        if (value.contains("REVENUE")) {
            return InsightType.REVENUE;
        }
        if (value.contains("SERVICE") || value.contains("PACKAGE")) {
            return InsightType.SERVICE;
        }
        if (value.contains("CUSTOMER") || value.contains("CHURN") || value.contains("RETURNING")) {
            return InsightType.CUSTOMER;
        }
        if (value.contains("LOYALTY") || value.contains("POINT") || value.contains("TIER")) {
            return InsightType.LOYALTY;
        }
        return InsightType.ORDER;
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }
}
