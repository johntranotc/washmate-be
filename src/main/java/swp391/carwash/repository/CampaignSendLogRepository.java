package swp391.carwash.repository;

import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import swp391.carwash.entity.CampaignSendLog;

public interface CampaignSendLogRepository extends JpaRepository<CampaignSendLog, Integer> {

    /**
     * Chống gửi trùng: đã có lần gửi THÀNH CÔNG (sentCount > 0) cho insight này
     * sau mốc cutoff chưa. Lần gửi fail toàn bộ không tính, để owner được retry ngay.
     */
    boolean existsByInsightIdAndSentAtAfterAndSentCountGreaterThan(
            Integer insightId, OffsetDateTime cutoff, int sentCount);
}
