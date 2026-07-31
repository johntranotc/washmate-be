package swp391.carwash.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import swp391.carwash.config.GeminiProperties;
import swp391.carwash.dto.insight.AIInsightEnrichResponse;
import swp391.carwash.dto.insight.AIInsightResult;
import swp391.carwash.dto.insight.InsightContext;
import swp391.carwash.entity.BusinessInsight;
import swp391.carwash.entity.InsightAIEnrichment;
import swp391.carwash.enums.InsightStatus;
import swp391.carwash.enums.InsightSource;
import swp391.carwash.repository.BusinessInsightRepository;
import swp391.carwash.repository.InsightAIEnrichmentRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIInsightService {
    private final BusinessInsightRepository businessInsightRepository;
    private final InsightAIEnrichmentRepository aiEnrichmentRepository;
    private final InsightMetricAggregator insightMetricAggregator;
    private final AIPromptBuilderService promptBuilderService;
    private final GeminiClient geminiClient;
    private final AIResponseValidatorService validatorService;
    private final GeminiProperties geminiProperties;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${washmate.insight.ai.regenerate-cooldown-seconds:60}")
    private long regenerateCooldownSeconds;

    public AIInsightEnrichResponse enrichInsight(Integer insightId) {
        BusinessInsight insight = businessInsightRepository.findById(insightId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Insight not found"));

        Optional<InsightAIEnrichment> existing = aiEnrichmentRepository.findByBusinessInsightId(insightId);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        return generateAndSaveEnrichment(insight, new InsightAIEnrichment());
    }

    public AIInsightEnrichResponse regenerateInsight(Integer insightId) {
        BusinessInsight insight = businessInsightRepository.findById(insightId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Insight not found"));

        InsightAIEnrichment enrichment = aiEnrichmentRepository.findByBusinessInsightId(insightId)
                .orElse(new InsightAIEnrichment());

        // Cooldown chống spam regenerate (mỗi call là 1 lần gọi Gemini = tốn quota).
        if (enrichment.getGeneratedAt() != null) {
            OffsetDateTime nextAllowedAt = enrichment.getGeneratedAt()
                    .plusSeconds(regenerateCooldownSeconds);
            if (OffsetDateTime.now().isBefore(nextAllowedAt)) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Vui lòng chờ trước khi tạo lại phân tích AI cho insight này");
            }
        }

        return generateAndSaveEnrichment(insight, enrichment);
    }

    public AIInsightEnrichResponse getAIEnrichmentByInsightId(Integer insightId) {
        return aiEnrichmentRepository.findByBusinessInsightId(insightId)
                .map(this::toResponse)
                .orElse(null);
    }

    private AIInsightEnrichResponse generateAndSaveEnrichment(BusinessInsight insight, InsightAIEnrichment enrichment) {
        validateInsightBeforeAI(insight);

        InsightContext insightContext = insightMetricAggregator.buildContext(insight);
        String contextJson = promptBuilderService.buildContextJson(insightContext);
        String prompt = promptBuilderService.buildPrompt(contextJson);

        try {
            String rawResponse = geminiClient.generateContent(prompt);
            AIInsightResult result = validatorService.parseAndValidate(rawResponse);

            enrichment.setBusinessInsight(insight);
            enrichment.setAiSummary(result.getAiSummary());
            enrichment.setAiExplanation(result.getAiExplanation());
            enrichment.setAiRecommendation(objectMapper.writeValueAsString(result.getAiRecommendation()));
            enrichment.setAiCampaignSuggestion(objectMapper.writeValueAsString(result.getAiCampaignSuggestion()));
            enrichment.setConfidenceScore(result.getConfidenceScore());
            enrichment.setAiModel(geminiProperties.getModel());
            enrichment.setPromptVersion(geminiProperties.getPrompt().getVersion());
            enrichment.setSource(InsightSource.RULE_BASED);
            enrichment.setEvidenceJson(ruleBasedEvidence(insight));
            // verified = "số liệu AI đưa ra ĐÃ được backend đối chiếu với metric thật".
            // Luồng enrich này KHÔNG chạy AiInsightVerifier (chỉ validate cấu trúc + từ khoá),
            // nên không được khai báo là đã kiểm chứng. Chỉ luồng deep-analysis mới verify thật.
            enrichment.setVerified(false);
            enrichment.setGeneratedAt(OffsetDateTime.now());

            InsightAIEnrichment saved = aiEnrichmentRepository.save(enrichment);
            return toResponse(saved);
        } catch (IllegalArgumentException e) {
            log.error("Validation error for Gemini response: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.valueOf(422), "Gemini response invalid: " + e.getMessage());
        } catch (JsonProcessingException e) {
            log.error("Error serializing AI objects", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error saving AI enrichment data");
        } catch (DataIntegrityViolationException e) {
            log.warn("AI enrichment already exists for insight {}", insight.getId());
            return aiEnrichmentRepository.findByBusinessInsightId(insight.getId())
                    .map(this::toResponse)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "AI enrichment already exists"));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error generating AI insight", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Error connecting to AI service");
        }
    }

    private void validateInsightBeforeAI(BusinessInsight insight) {
        if (!StringUtils.hasText(insight.getTitle()) ||
            !StringUtils.hasText(insight.getSummary()) ||
            !StringUtils.hasText(insight.getEvidence())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Insight data is incomplete");
        }
        if (insight.getStatus() == InsightStatus.DISMISSED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot enrich a dismissed insight");
        }
        if (insight.getFromDate() == null || insight.getToDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Insight missing date range");
        }
    }

    private Map<String, Object> ruleBasedEvidence(BusinessInsight insight) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("ruleCode", insight.getRuleCode());
        evidence.put("relatedMetric", insight.getRelatedMetric());
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("from", insight.getFromDate().toString());
        period.put("to", insight.getToDate().toString());
        evidence.put("period", period);
        return evidence;
    }

    private AIInsightEnrichResponse toResponse(InsightAIEnrichment enrichment) {
        try {
            java.util.List<String> recommendation = objectMapper.readValue(enrichment.getAiRecommendation(),
                objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
            swp391.carwash.dto.insight.AICampaignSuggestion campaignSuggestion = objectMapper.readValue(enrichment.getAiCampaignSuggestion(),
                swp391.carwash.dto.insight.AICampaignSuggestion.class);

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
            log.error("Error mapping InsightAIEnrichment to Response", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error reading AI enrichment data");
        }
    }
}
