package swp391.carwash.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import swp391.carwash.config.GeminiProperties;
import swp391.carwash.dto.insight.AIDeepAnalysisRequest;
import swp391.carwash.dto.insight.AIDeepAnalysisResponse;
import swp391.carwash.dto.insight.AIDeepAnalysisResult;
import swp391.carwash.dto.insight.AIDetectedInsight;
import swp391.carwash.dto.insight.AiInsightEvidence;
import swp391.carwash.dto.insight.InsightPeriod;
import swp391.carwash.entity.BusinessInsight;
import swp391.carwash.entity.InsightAIEnrichment;
import swp391.carwash.entity.InsightAnalysisRun;
import swp391.carwash.entity.InsightRuleConfig;
import swp391.carwash.repository.BusinessInsightRepository;
import swp391.carwash.repository.InsightAIEnrichmentRepository;
import swp391.carwash.repository.InsightAnalysisRunRepository;
import swp391.carwash.repository.InsightRuleConfigRepository;
import swp391.carwash.security.AppUserDetails;
import swp391.carwash.security.GarageAccessEvaluator;
import swp391.carwash.service.insight.MetricSnapshot;

/**
 * Anti-hallucination test for the deep-analysis pipeline:
 * the AI returns one insight backed by a real backend metric and one with a
 * fabricated value. Only the verified insight may be kept, and the audit run
 * must record total_returned > total_kept.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AIDeepAnalysisServiceTest {

    @Mock
    private MetricSnapshotBuilder metricSnapshotBuilder;
    @Mock
    private GeminiClient geminiClient;
    @Mock
    private AIResponseValidatorService validatorService;
    @Mock
    private BusinessInsightRepository businessInsightRepository;
    @Mock
    private InsightRuleConfigRepository insightRuleConfigRepository;
    @Mock
    private InsightAIEnrichmentRepository aiEnrichmentRepository;
    @Mock
    private InsightAnalysisRunRepository analysisRunRepository;
    @Mock
    private GarageAccessEvaluator garageAccessEvaluator;
    @Mock
    private AppUserDetails principal;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    private AIDeepAnalysisService service;

    private final LocalDate from = LocalDate.of(2026, 7, 1);
    private final LocalDate to = LocalDate.of(2026, 7, 31);

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        GeminiProperties geminiProperties = new GeminiProperties();
        service = new AIDeepAnalysisService(
                metricSnapshotBuilder,
                new AiInsightVerifier(),
                new AIPromptBuilderService(objectMapper),
                geminiClient,
                validatorService,
                businessInsightRepository,
                insightRuleConfigRepository,
                aiEnrichmentRepository,
                analysisRunRepository,
                garageAccessEvaluator,
                geminiProperties,
                objectMapper,
                transactionManager);

        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);

        when(principal.getRoleNames()).thenReturn(List.of("OWNER"));
        when(principal.getId()).thenReturn(7);

        Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        metrics.put("total_revenue", new BigDecimal("85000000.00"));
        metrics.put("total_orders", new BigDecimal("1248.00"));
        metrics.put("revenue_change_percent", new BigDecimal("-23.00"));
        MetricSnapshot snapshot = new MetricSnapshot(
                new InsightPeriod(from, to), null, metrics, Map.of());
        when(metricSnapshotBuilder.build(from, to, null)).thenReturn(snapshot);

        when(geminiClient.generateContent(anyString())).thenReturn("raw-ai-json");

        when(businessInsightRepository.findByRuleCodeAndScopeAndPeriod(anyString(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(businessInsightRepository.save(any(BusinessInsight.class))).thenAnswer(invocation -> {
            BusinessInsight insight = invocation.getArgument(0);
            if (insight.getId() == null) {
                insight.setId(1);
            }
            return insight;
        });
        when(insightRuleConfigRepository.findByRuleCode(anyString()))
                .thenReturn(Optional.of(mock(InsightRuleConfig.class)));
        when(aiEnrichmentRepository.findByBusinessInsightId(anyInt())).thenReturn(Optional.empty());
        when(aiEnrichmentRepository.save(any(InsightAIEnrichment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(analysisRunRepository.save(any(InsightAnalysisRun.class))).thenAnswer(invocation -> {
            InsightAnalysisRun run = invocation.getArgument(0);
            run.setId(42);
            return run;
        });
    }

    @Test
    void analyzeKeepsVerifiedInsightAndRejectsFabricatedValue() {
        AIDetectedInsight verifiedCandidate = new AIDetectedInsight(
                "REVENUE_DROP",
                "WARNING",
                "Doanh thu giam 23% so voi ky truoc",
                new AiInsightEvidence("revenue_change_percent", new BigDecimal("-23.00"), "2026-07"),
                "Ra soat dich vu co doanh thu giam");
        AIDetectedInsight fabricatedCandidate = new AIDetectedInsight(
                "REVENUE_DROP",
                "CRITICAL",
                "Doanh thu giam 50% (so bia)",
                new AiInsightEvidence("revenue_change_percent", new BigDecimal("-50.00"), "2026-07"),
                "Hanh dong khan cap");
        when(validatorService.parseAndValidateDetectedInsights("raw-ai-json"))
                .thenReturn(new AIDeepAnalysisResult(List.of(verifiedCandidate, fabricatedCandidate)));

        AIDeepAnalysisResponse response = service.analyze(
                new AIDeepAnalysisRequest(from, to, null), principal);

        assertEquals(2, response.candidateCount());
        assertEquals(1, response.verifiedCount());
        assertEquals(1, response.rejectedCount());
        assertEquals(1, response.insights().size());
        assertEquals(1, response.rejectedInsights().size());
        assertEquals("revenue_change_percent", response.rejectedInsights().get(0).metric());
        assertTrue(response.verifiedCount() < response.candidateCount(),
                "fabricated insight must be filtered out");

        ArgumentCaptor<InsightAnalysisRun> runCaptor = ArgumentCaptor.forClass(InsightAnalysisRun.class);
        verify(analysisRunRepository).save(runCaptor.capture());
        InsightAnalysisRun run = runCaptor.getValue();
        assertEquals(2, run.getTotalReturned().intValue());
        assertEquals(1, run.getTotalKept().intValue());
        assertEquals(1, run.getTotalRejected().intValue());
        assertEquals(7, run.getRequestedBy().intValue());
        assertEquals("raw-ai-json", run.getRawResponse());
        assertEquals(42, response.analysisRunId().intValue());
    }

    @Test
    void analyzeRejectsAllInsightsWhenEveryValueIsFabricated() {
        AIDetectedInsight fabricated = new AIDetectedInsight(
                "REVENUE_DROP",
                "WARNING",
                "Doanh thu giam manh",
                new AiInsightEvidence("fake_metric", new BigDecimal("-99.00"), "2026-07"),
                "Hanh dong");
        when(validatorService.parseAndValidateDetectedInsights("raw-ai-json"))
                .thenReturn(new AIDeepAnalysisResult(List.of(fabricated)));

        AIDeepAnalysisResponse response = service.analyze(
                new AIDeepAnalysisRequest(from, to, null), principal);

        assertEquals(1, response.candidateCount());
        assertEquals(0, response.verifiedCount());
        assertEquals(1, response.rejectedCount());
        assertTrue(response.insights().isEmpty());
        assertNotNull(response.analysisRunId());
    }
}
