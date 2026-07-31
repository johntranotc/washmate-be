package swp391.carwash.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.*;
import swp391.carwash.enums.InsightSeverity;
import swp391.carwash.enums.InsightStatus;
import swp391.carwash.enums.InsightType;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
// Khoá duy nhất là expression index (rule_code, COALESCE(garage_id,-1), from_date, to_date)
// — xem V10__business_insight_garage_scope.sql. Không khai báo bằng @UniqueConstraint được
// vì JPA không diễn đạt được COALESCE, mà UNIQUE thường sẽ không chặn trùng khi garage_id NULL.
@Table(name = "business_insight")
public class BusinessInsight {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "insight_id")
    private Integer id;

    @Column(name = "rule_code", nullable = false, length = 100)
    private String ruleCode;

    /**
     * Phạm vi của insight: {@code null} = toàn hệ thống (rule engine),
     * có giá trị = riêng garage đó (AI deep-analysis).
     */
    @Column(name = "garage_id")
    private Integer garageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InsightType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InsightSeverity severity;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String evidence;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String meaning;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String recommendation;

    @Column(name = "related_metric", length = 100)
    private String relatedMetric;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private InsightStatus status = InsightStatus.NEW;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
