package swp391.carwash.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import swp391.carwash.entity.LoyaltyAccount;
import swp391.carwash.enums.RecordStatus;

@Repository
public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, Integer> {
    @EntityGraph(attributePaths = {"garage", "tier"})
    List<LoyaltyAccount> findByUserIdOrderByGarageNameAsc(Integer userId);

    List<LoyaltyAccount> findByStatusAndAvailablePointsGreaterThan(
            RecordStatus status,
            Integer availablePoints
    );

    Optional<LoyaltyAccount> findByUserIdAndGarageId(Integer userId, Integer garageId);

    Boolean existsByTierId(Integer tierId);

    Optional<LoyaltyAccount> findByUserId(Integer userId);

    @Query("""
            SELECT COUNT(account)
            FROM LoyaltyAccount account
            WHERE account.status = :status
              AND account.availablePoints > 0
            """)
    long countAccountsWithAvailablePoints(
            @Param("status") RecordStatus status
    );

    @Query("""
            SELECT COUNT(account)
            FROM LoyaltyAccount account
            WHERE account.status = :status
              AND account.availablePoints > 0
              AND (
                    :garageId IS NULL
                    OR account.garage.id = :garageId
              )
            """)
    long countAccountsWithAvailablePointsForScope(
            @Param("status") RecordStatus status,
            @Param("garageId") Integer garageId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT account
        FROM LoyaltyAccount account
        JOIN FETCH account.user
        JOIN FETCH account.garage
        WHERE account.user.id = :userId
          AND account.garage.id = :garageId
          AND account.status = :status
        """)
    Optional<LoyaltyAccount> findForRedeem(
            @Param("userId") Integer userId,
            @Param("garageId") Integer garageId,
            @Param("status") RecordStatus status
    );
    @EntityGraph(attributePaths = {
            "tier",
            "garage",
            "user"
    })
    List<LoyaltyAccount> findByStatus(RecordStatus status);

    @Query("""
            select distinct account.user.email
            from LoyaltyAccount account
            where account.status = :status
              and account.user.email is not null
              and account.availablePoints > 0
              and (:garageId is null or account.garage.id = :garageId)
            """)
    List<String> findEmailsWithAvailablePoints(
            @Param("status") RecordStatus status,
            @Param("garageId") Integer garageId);

    @Query("""
            select distinct account.user.email
            from LoyaltyAccount account
            where account.status = :status
              and account.user.email is not null
              and (:garageId is null or account.garage.id = :garageId)
            """)
    List<String> findActiveLoyaltyEmails(
            @Param("status") RecordStatus status,
            @Param("garageId") Integer garageId);



}
