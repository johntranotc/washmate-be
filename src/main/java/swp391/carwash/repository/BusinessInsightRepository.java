package swp391.carwash.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import swp391.carwash.entity.BusinessInsight;
import swp391.carwash.enums.InsightStatus;
import swp391.carwash.enums.InsightType;

public interface BusinessInsightRepository extends JpaRepository<BusinessInsight, Integer> {

    /**
     * Tra insight theo ĐÚNG phạm vi. {@code garageId == null} nghĩa là insight toàn hệ thống,
     * không phải "bỏ qua điều kiện garage" — nếu bỏ qua thì deep-analysis của garage này sẽ
     * ghi đè insight của garage khác (xem V10__business_insight_garage_scope.sql).
     */
    @Query("""
            select insight from BusinessInsight insight
            where insight.ruleCode = :ruleCode
              and insight.fromDate = :fromDate
              and insight.toDate = :toDate
              and (
                    (:garageId is null and insight.garageId is null)
                 or insight.garageId = :garageId
              )
            """)
    Optional<BusinessInsight> findByRuleCodeAndScopeAndPeriod(
            @Param("ruleCode") String ruleCode,
            @Param("garageId") Integer garageId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /**
     * Danh sách insight của một kỳ. {@code garageId == null} ở đây là FILTER tuỳ chọn của
     * người xem: null = xem tất cả phạm vi (giữ nguyên hành vi cũ của API).
     */
    @Query("""
            select insight from BusinessInsight insight
            where insight.fromDate = :fromDate
              and insight.toDate = :toDate
              and (:type is null or insight.type = :type)
              and (:status is null or insight.status = :status)
              and (:garageId is null or insight.garageId = :garageId)
            order by insight.severity asc, insight.createdAt desc
            """)
    List<BusinessInsight> findForOwnerInsights(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("type") InsightType type,
            @Param("status") InsightStatus status,
            @Param("garageId") Integer garageId);
}
