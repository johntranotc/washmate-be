package swp391.carwash.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import swp391.carwash.dto.insight.AIDeepAnalysisResult;
import swp391.carwash.dto.insight.AIDetectedInsight;
import swp391.carwash.dto.insight.AIInsightResult;
import swp391.carwash.enums.InsightSeverity;

@Service
@Slf4j
@RequiredArgsConstructor
public class AIResponseValidatorService {
    private final ObjectMapper objectMapper;

    public AIInsightResult parseAndValidate(String rawGeminiResponse) {
        try {
            String jsonContent = extractJsonContent(rawGeminiResponse);
            AIInsightResult result = objectMapper.readValue(jsonContent.trim(), AIInsightResult.class);

            if (!StringUtils.hasText(result.getAiSummary())) {
                throw new IllegalArgumentException("aiSummary must not be empty");
            }
            if (!StringUtils.hasText(result.getAiExplanation())) {
                throw new IllegalArgumentException("aiExplanation must not be empty");
            }
            if (result.getAiRecommendation() == null || result.getAiRecommendation().isEmpty()) {
                throw new IllegalArgumentException("aiRecommendation must have at least 1 item");
            }

            if (result.getAiCampaignSuggestion() != null) {
                if (!StringUtils.hasText(result.getAiCampaignSuggestion().getCampaignName()) ||
                    !StringUtils.hasText(result.getAiCampaignSuggestion().getTargetCustomers()) ||
                    !StringUtils.hasText(result.getAiCampaignSuggestion().getOffer()) ||
                    !StringUtils.hasText(result.getAiCampaignSuggestion().getDuration()) ||
                    !StringUtils.hasText(result.getAiCampaignSuggestion().getGoal())) {
                    throw new IllegalArgumentException("aiCampaignSuggestion is missing required fields");
                }
            } else {
                throw new IllegalArgumentException("aiCampaignSuggestion must not be null");
            }

            if (result.getConfidenceScore() == null ||
                result.getConfidenceScore().compareTo(BigDecimal.ZERO) < 0 ||
                result.getConfidenceScore().compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("confidenceScore must be between 0 and 1");
            }

            assertNoForbiddenTerminology(result.getAiSummary() + " " + result.getAiExplanation() + " "
                    + String.join(" ", result.getAiRecommendation()) + " "
                    + result.getAiCampaignSuggestion().toString());

            return result;
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) e;
            }
            log.error("Failed to parse and validate AI response", e);
            throw new IllegalArgumentException("Invalid AI response format: " + e.getMessage(), e);
        }
    }

    public AIDeepAnalysisResult parseAndValidateDetectedInsights(String rawGeminiResponse) {
        try {
            String jsonContent = extractJsonContent(rawGeminiResponse);
            AIDeepAnalysisResult result = objectMapper.readValue(jsonContent.trim(), AIDeepAnalysisResult.class);
            List<AIDetectedInsight> insights = result.insights() == null ? List.of() : result.insights();

            for (AIDetectedInsight insight : insights) {
                if (!StringUtils.hasText(insight.type())) {
                    throw new IllegalArgumentException("insight.type must not be empty");
                }
                if (!StringUtils.hasText(insight.severity())) {
                    throw new IllegalArgumentException("insight.severity must not be empty");
                }
                parseSeverity(insight.severity());
                if (!StringUtils.hasText(insight.claim())) {
                    throw new IllegalArgumentException("insight.claim must not be empty");
                }
                if (!StringUtils.hasText(insight.suggestedAction())) {
                    throw new IllegalArgumentException("insight.suggested_action must not be empty");
                }
                if (insight.evidence() == null) {
                    throw new IllegalArgumentException("insight.evidence must not be null");
                }
                if (!StringUtils.hasText(insight.evidence().metric())) {
                    throw new IllegalArgumentException("insight.evidence.metric must not be empty");
                }
                if (insight.evidence().value() == null) {
                    throw new IllegalArgumentException("insight.evidence.value must not be null");
                }
                assertNoForbiddenTerminology(insight.type() + " " + insight.claim() + " " + insight.suggestedAction());
            }

            return new AIDeepAnalysisResult(List.copyOf(insights));
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) e;
            }
            log.error("Failed to parse and validate AI deep-analysis response", e);
            throw new IllegalArgumentException("Invalid AI deep-analysis response format: " + e.getMessage(), e);
        }
    }

    // Chặn thuật ngữ kinh doanh cấm (AutoWash là hệ thống nội bộ, không phải platform nhiều đối tác).
    // Áp cho cả luồng enrich lẫn deep-analysis để nhất quán.
    private void assertNoForbiddenTerminology(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        if (lower.contains("platform") || lower.contains("marketplace")
                || lower.contains("hoa hồng nền tảng") || lower.contains("đối tác cửa hàng")
                || lower.contains("nhà cung cấp độc lập") || lower.contains("doanh thu toàn sàn")) {
            throw new IllegalArgumentException("Response contains forbidden business terminology");
        }
    }

    private InsightSeverity parseSeverity(String severity) {
        try {
            return InsightSeverity.valueOf(severity.trim().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("insight.severity is invalid");
        }
    }

    private String extractJsonContent(String rawGeminiResponse) {
        if (!StringUtils.hasText(rawGeminiResponse)) {
            throw new IllegalArgumentException("AI response must not be empty");
        }

        String jsonContent = rawGeminiResponse.trim();
        // Clean up possible markdown code blocks around json.
        if (jsonContent.startsWith("```json")) {
            jsonContent = jsonContent.substring(7);
        } else if (jsonContent.startsWith("```")) {
            jsonContent = jsonContent.substring(3);
        }
        jsonContent = jsonContent.trim();
        if (jsonContent.endsWith("```")) {
            jsonContent = jsonContent.substring(0, jsonContent.length() - 3);
        }
        return jsonContent.trim();
    }
}
