package swp391.carwash.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import swp391.carwash.entity.BookingSlot;
import swp391.carwash.enums.RecordStatus;

@Repository
public interface BookingSlotRepository extends JpaRepository<BookingSlot, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select slot from BookingSlot slot where slot.id = :id")
    Optional<BookingSlot> findByIdForUpdate(@Param("id") Integer id);

    // Tìm toàn bộ khung giờ của một Garage cụ thể
    List<BookingSlot> findByGarageId(Integer garageId);

    // Tìm các khung giờ đang hoạt động (ACTIVE) của một Garage
    List<BookingSlot> findByGarageIdAndStatus(Integer garageId, RecordStatus status);

    List<BookingSlot> findByGarageIdAndStatusOrderByStartTimeAsc(Integer garageId, RecordStatus status);

    /**
     * Tìm slot trùng KHÍT khung giờ, KHÔNG lọc theo status.
     *
     * <p>Cần thiết vì DB có ràng buộc {@code UNIQUE (garage_id, start_time, end_time)} không
     * kèm điều kiện status, trong khi {@code deleteSlot} chỉ xoá mềm (giữ nguyên giờ). Nếu
     * chỉ tra slot ACTIVE thì tạo lại đúng khung giờ vừa xoá sẽ lọt qua validate rồi vỡ ở
     * tầng DB thành lỗi 500.
     */
    Optional<BookingSlot> findByGarageIdAndStartTimeAndEndTime(
            Integer garageId, java.time.LocalTime startTime, java.time.LocalTime endTime);
}
