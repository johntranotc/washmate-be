package swp391.carwash.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import swp391.carwash.entity.LoyaltyPolicy;
import swp391.carwash.enums.RecordStatus;

import java.util.Optional;

public interface LoyaltyPolicyRepository
        extends JpaRepository<LoyaltyPolicy,Integer> {

    Optional<LoyaltyPolicy> findByGarageIdAndStatus(
            Integer garageId,
            RecordStatus status);

    /**
     * Tra policy theo garage BẤT KỂ status.
     * Cần cho create(): cột garage_id có UNIQUE nhưng delete() chỉ soft-delete, nên phải
     * tìm được row đã DELETED để kích hoạt lại thay vì insert row mới (sẽ vi phạm UNIQUE).
     */
    Optional<LoyaltyPolicy> findByGarageId(Integer garageId);

}