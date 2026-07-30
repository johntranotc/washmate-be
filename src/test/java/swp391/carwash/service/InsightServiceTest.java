package swp391.carwash.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import swp391.carwash.dto.insight.InsightGenerateResponse;
import swp391.carwash.dto.insight.InsightResponse;
import swp391.carwash.entity.BusinessInsight;
import swp391.carwash.entity.InsightRuleConfig;
import swp391.carwash.enums.BookingStatus;
import swp391.carwash.enums.ComparisonOperator;
import swp391.carwash.enums.InsightSeverity;
import swp391.carwash.enums.InsightStatus;
import swp391.carwash.enums.InsightType;
import swp391.carwash.repository.BusinessInsightRepository;
import swp391.carwash.repository.InsightRuleConfigRepository;
import swp391.carwash.service.insight.InsightAnalysisContext;
import swp391.carwash.service.insight.InsightMetrics;
import swp391.carwash.service.insight.InsightRuleEngine;
import swp391.carwash.service.insight.InsightTestData;
import swp391.carwash.service.insight.rule.RevenueInsightRule;

@ExtendWith(MockitoExtension.class)
class InsightServiceTest {
    @Mock
    private ReportAggregationService reportAggregationService;
    @Mock
    private InsightRuleEngine insightRuleEngine;
    @Mock
    private BusinessInsightRepository businessInsightRepository;
    @Mock
    private InsightRuleConfigRepository insightRuleConfigRepository;

    private InsightService insightService;

    @BeforeEach
    void setUp() {
        insightService = new InsightService(
                reportAggregationService,
                insightRuleEngine,
                businessInsightRepository,
                insightRuleConfigRepository);
    }

    @Test
    void generateInsightsCreatesNewBusinessInsight() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        InsightAnalysisContext context = businessContext(from, to);
        InsightResponse candidate = revenueCandidate();
        when(reportAggregationService.aggregate(from, to)).thenReturn(context);
        when(insightRuleConfigRepository.findAll()).thenReturn(List.of(config(RevenueInsightRule.REVENUE_GROWTH, true, 15)));
        when(insightRuleEngine.generate(any(InsightAnalysisContext.class), any())).thenReturn(List.of(candidate));
        when(businessInsightRepository.findByRuleCodeAndScopeAndPeriod(RevenueInsightRule.REVENUE_GROWTH, null, from, to))
                .thenReturn(Optional.empty());
        when(businessInsightRepository.save(any(BusinessInsight.class))).thenAnswer(invocation -> {
            BusinessInsight insight = invocation.getArgument(0);
            insight.setId(1);
            return insight;
        });

        InsightGenerateResponse response = insightService.generateInsights(from, to);

        assertEquals(1, response.generatedCount());
        assertEquals(1, response.createdCount());
        assertEquals(0, response.updatedCount());
        assertEquals(RevenueInsightRule.REVENUE_GROWTH, response.insights().getFirst().ruleCode());
    }

    @Test
    void generateInsightsUpdatesExistingInsightInsteadOfCreatingDuplicate() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        BusinessInsight existing = BusinessInsight.builder()
                .id(7)
                .ruleCode(RevenueInsightRule.REVENUE_GROWTH)
                .fromDate(from)
                .toDate(to)
                .status(InsightStatus.RESOLVED)
                .build();
        when(reportAggregationService.aggregate(from, to)).thenReturn(businessContext(from, to));
        when(insightRuleConfigRepository.findAll()).thenReturn(List.of(config(RevenueInsightRule.REVENUE_GROWTH, true, 15)));
        when(insightRuleEngine.generate(any(InsightAnalysisContext.class), any())).thenReturn(List.of(revenueCandidate()));
        when(businessInsightRepository.findByRuleCodeAndScopeAndPeriod(RevenueInsightRule.REVENUE_GROWTH, null, from, to))
                .thenReturn(Optional.of(existing));
        when(businessInsightRepository.save(any(BusinessInsight.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InsightGenerateResponse response = insightService.generateInsights(from, to);

        assertEquals(0, response.createdCount());
        assertEquals(1, response.updatedCount());
        assertEquals(InsightStatus.RESOLVED, response.insights().getFirst().status());
    }

    @Test
    void generateInsightsPassesInactiveConfigsToRuleEngine() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        InsightRuleConfig inactiveConfig = config(RevenueInsightRule.REVENUE_DROP, false, 15);
        when(reportAggregationService.aggregate(from, to)).thenReturn(businessContext(from, to));
        when(insightRuleConfigRepository.findAll()).thenReturn(List.of(inactiveConfig));
        when(insightRuleEngine.generate(any(InsightAnalysisContext.class), any())).thenReturn(List.of());

        insightService.generateInsights(from, to);

        ArgumentCaptor<InsightAnalysisContext> contextCaptor = ArgumentCaptor.forClass(InsightAnalysisContext.class);
        verify(insightRuleEngine).generate(contextCaptor.capture(), any());
        assertFalse(contextCaptor.getValue().active(RevenueInsightRule.REVENUE_DROP));
    }

    @Test
    void generateInsightsSkipsWhenDataIsInsufficient() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        InsightMetrics empty = InsightTestData.metrics(from, to, List.of(), List.of(), List.of(), List.of(), List.of(), Set.of(), 0);
        when(reportAggregationService.aggregate(from, to))
                .thenReturn(new InsightAnalysisContext(empty, empty, OffsetDateTime.parse("2026-07-31T10:00:00+07:00")));

        InsightGenerateResponse response = insightService.generateInsights(from, to);

        assertEquals(0, response.generatedCount());
        verify(insightRuleEngine, never()).generate(any(), any());
        verify(businessInsightRepository, never()).save(any());
    }

    @Test
    void updateStatusPersistsNewStatus() {
        BusinessInsight insight = BusinessInsight.builder()
                .id(1)
                .ruleCode(RevenueInsightRule.REVENUE_GROWTH)
                .type(InsightType.REVENUE)
                .severity(InsightSeverity.POSITIVE)
                .title("title")
                .summary("summary")
                .evidence("evidence")
                .meaning("meaning")
                .recommendation("recommendation")
                .status(InsightStatus.NEW)
                .createdAt(OffsetDateTime.parse("2026-07-31T10:00:00+07:00"))
                .build();
        when(businessInsightRepository.findById(1)).thenReturn(Optional.of(insight));
        when(businessInsightRepository.save(any(BusinessInsight.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = insightService.updateStatus(1, InsightStatus.READ);

        assertEquals(InsightStatus.READ, response.status());
    }

    private InsightAnalysisContext businessContext(LocalDate from, LocalDate to) {
        var booking = InsightTestData.booking(1, 1, BookingStatus.COMPLETED, from);
        InsightMetrics current = InsightTestData.metrics(
                from,
                to,
                List.of(booking),
                List.of(InsightTestData.paidInvoice(1, booking, "120000", OffsetDateTime.parse("2026-07-01T10:00:00+07:00"))),
                List.of(),
                List.of(),
                List.of(booking.getService()),
                Set.of(),
                0);
        return new InsightAnalysisContext(current, current, OffsetDateTime.parse("2026-07-31T10:00:00+07:00"));
    }

    private InsightResponse revenueCandidate() {
        return new InsightResponse(
                RevenueInsightRule.REVENUE_GROWTH,
                InsightType.REVENUE,
                InsightSeverity.POSITIVE,
                "Doanh thu tăng",
                "summary",
                "evidence",
                "meaning",
                "recommendation",
                "revenue",
                OffsetDateTime.parse("2026-07-31T10:00:00+07:00"));
    }

    private InsightRuleConfig config(String ruleCode, boolean active, int threshold) {
        return InsightRuleConfig.builder()
                .id(1)
                .ruleCode(ruleCode)
                .ruleName(ruleCode)
                .type(InsightType.REVENUE)
                .thresholdValue(BigDecimal.valueOf(threshold))
                .comparisonOperator(ComparisonOperator.GREATER_THAN)
                .severity(InsightSeverity.POSITIVE)
                .active(active)
                .build();
    }
}
