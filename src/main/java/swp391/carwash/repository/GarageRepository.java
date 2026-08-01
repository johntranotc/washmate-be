package swp391.carwash.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import swp391.carwash.entity.Garage;
import java.util.List;

public interface GarageRepository extends JpaRepository<Garage, Integer> {
    // Tìm các garage đang hoạt động (phục vụ cho khách hàng xem danh sách)
    @Query("SELECT g FROM Garage g WHERE g.status <> 'DELETED'")
    List<Garage> findAllActiveGarages();

    boolean existsByName(String name);

    /**
     * Khoá bi quan dòng garage — dùng khi thao tác phải tuần tự trên TOÀN BỘ tập slot của
     * garage, ví dụ kiểm tra chồng giờ trước khi tạo slot mới. Khoá từng slot không đủ vì
     * slot mới chưa tồn tại để mà khoá.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Garage g where g.id = :id")
    Optional<Garage> findByIdForUpdate(@Param("id") Integer id);
}
