package swp391.carwash.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import swp391.carwash.entity.Booking;
import swp391.carwash.enums.BookingStatus;

public interface BookingRepository extends JpaRepository<Booking, Integer> {
  @EntityGraph(attributePaths = { "user", "garage", "slot", "service", "vehicle", "assignedStaff" })
  @Query("select b from Booking b where b.id = :id")
  Optional<Booking> findDetailedById(@Param("id") Integer id);

  // Khóa bi quan row booking cho các chuyển trạng thái nhạy cảm (vd complete cộng điểm),
  // tránh xử lý trùng khi có request đồng thời.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(attributePaths = { "user", "garage", "slot", "service", "vehicle", "assignedStaff" })
  @Query("select b from Booking b where b.id = :id")
  Optional<Booking> findDetailedByIdForUpdate(@Param("id") Integer id);

  /** Email khách "inactive": lần đặt gần nhất trước mốc cutoff (tệp win-back). */
  @Query("""
      select b.user.email
      from Booking b
      where b.user.email is not null
        and (:garageId is null or b.garage.id = :garageId)
      group by b.user.email
      having max(b.bookingDate) < :cutoff
      """)
  List<String> findInactiveCustomerEmails(
      @Param("cutoff") LocalDate cutoff,
      @Param("garageId") Integer garageId);

  @EntityGraph(attributePaths = { "user", "garage", "slot", "service", "vehicle", "assignedStaff" })
  List<Booking> findByUserIdOrderByCreatedAtDesc(Integer userId);

  Optional<Booking> findFirstByUserIdAndGarageIdAndStatusOrderByCompletedTimeDesc(
      Integer userId,
      Integer garageId,
      BookingStatus status);

  @Query("""
      select count(b)
      from Booking b
      where b.slot.id = :slotId
        and b.garage.id = :garageId
        and b.bookingDate = :bookingDate
        and b.status in :statuses
      """)
  long countActiveBookings(
      @Param("slotId") Integer slotId,
      @Param("garageId") Integer garageId,
      @Param("bookingDate") LocalDate bookingDate,
      @Param("statuses") Collection<BookingStatus> statuses);

  /**
   * Check user đã có booking đang hoạt động trên cùng slot + ngày chưa
   * (chặn double booking). excludeBookingId dùng cho update, truyền null khi create.
   */
  @Query("""
      select count(b) > 0
      from Booking b
      where b.user.id = :userId
        and b.slot.id = :slotId
        and b.bookingDate = :bookingDate
        and b.status in :statuses
        and (:excludeBookingId is null or b.id != :excludeBookingId)
      """)
  boolean existsActiveBookingForUserAndSlot(
      @Param("userId") Integer userId,
      @Param("slotId") Integer slotId,
      @Param("bookingDate") LocalDate bookingDate,
      @Param("statuses") Collection<BookingStatus> statuses,
      @Param("excludeBookingId") Integer excludeBookingId);

  @EntityGraph(attributePaths = { "user", "garage", "slot", "service", "vehicle", "assignedStaff" })
  @Query("""
      select b from Booking b
      where (cast(:status as text) is null or b.status = :status)
        and (cast(:garageId as int) is null or b.garage.id = :garageId)
        and (cast(:fromDate as date) is null or b.bookingDate >= :fromDate)
        and (cast(:toDate as date) is null or b.bookingDate <= :toDate)
        and (coalesce(:garageIds, null) is null or b.garage.id in :garageIds)
      """)
  org.springframework.data.domain.Page<Booking> findBookingsWithFilters(
      @Param("status") BookingStatus status,
      @Param("garageId") Integer garageId,
      @Param("fromDate") LocalDate fromDate,
      @Param("toDate") LocalDate toDate,
      @Param("garageIds") List<Integer> garageIds,
      org.springframework.data.domain.Pageable pageable);

  @Query("""
      select count(b)
      from Booking b
      where b.slot.id = :slotId
        and b.garage.id = :garageId
        and b.bookingDate = :bookingDate
        and b.status in :statuses
        and b.id != :excludeBookingId
      """)
  long countActiveBookingsForUpdate(
      @Param("slotId") Integer slotId,
      @Param("garageId") Integer garageId,
      @Param("bookingDate") LocalDate bookingDate,
      @Param("statuses") Collection<BookingStatus> statuses,
      @Param("excludeBookingId") Integer excludeBookingId);

  @Query("""
      SELECT b.slot.id, count(b.id)
      FROM Booking b
      WHERE b.garage.id = :garageId
        AND b.bookingDate = :bookingDate
        AND b.status IN :statuses
      GROUP BY b.slot.id
      """)
  List<Object[]> countActiveBookingsGroupBySlot(
      @Param("garageId") Integer garageId,
      @Param("bookingDate") LocalDate bookingDate,
      @Param("statuses") Collection<BookingStatus> statuses);

  long countByStatus(BookingStatus status);

  @EntityGraph(attributePaths = { "user", "slot" })
  @Query("""
      SELECT b FROM Booking b
      WHERE b.reminderSent = false
        AND b.status IN (swp391.carwash.enums.BookingStatus.PENDING, swp391.carwash.enums.BookingStatus.CONFIRMED)
        AND b.bookingDate = CAST(:date AS java.time.LocalDate)
        AND b.slot.startTime >= CAST(:startTime AS java.time.LocalTime)
        AND b.slot.startTime < CAST(:endTime AS java.time.LocalTime)
      """)
  List<Booking> findBookingNeedReminder(
      @Param("date") LocalDateTime date,
      @Param("startTime") LocalDateTime startTime,
      @Param("endTime") LocalDateTime endTime);

  @Query("""
      SELECT count(b.id)
      FROM Booking b
      WHERE b.slot.id = :slotId
        AND b.bookingDate >= :fromDate
        AND b.status IN :statuses
      GROUP BY b.bookingDate
      """)
  List<Long> countActiveBookingsByDateForSlotFromDate(
      @Param("slotId") Integer slotId,
      @Param("fromDate") LocalDate fromDate,
      @Param("statuses") Collection<BookingStatus> statuses);

  @Query("""
          SELECT b
          FROM Booking b
          WHERE b.reminderSent = false
            AND b.checkinTime BETWEEN :startTime AND :endTime
            AND b.status = 'CONFIRMED'
      """)
  List<Booking> findBookingNeedReminder(
      @Param("startTime") OffsetDateTime startTime,
      @Param("endTime") OffsetDateTime endTime);

  @EntityGraph(attributePaths = { "user", "garage", "slot", "service" })
  @Query("""
      SELECT b FROM Booking b
      WHERE b.bookingDate >= :fromDate
        AND b.bookingDate <= :toDate
        AND (:garageId IS NULL OR b.garage.id = :garageId)
      """)
  List<Booking> findForInsightPeriod(
      @Param("fromDate") LocalDate fromDate,
      @Param("toDate") LocalDate toDate,
      @Param("garageId") Integer garageId);

  default List<Booking> findForInsightPeriod(LocalDate fromDate, LocalDate toDate) {
    return findForInsightPeriod(fromDate, toDate, null);
  }

  @Query("""
      SELECT DISTINCT b.user.id FROM Booking b
      WHERE b.bookingDate < :beforeDate
        AND (:garageId IS NULL OR b.garage.id = :garageId)
      """)
  List<Integer> findDistinctCustomerIdsWithBookingBefore(
      @Param("beforeDate") LocalDate beforeDate,
      @Param("garageId") Integer garageId);

  default List<Integer> findDistinctCustomerIdsWithBookingBefore(LocalDate beforeDate) {
    return findDistinctCustomerIdsWithBookingBefore(beforeDate, null);
  }

  // Đơn còn PENDING và có bookingDate <= hôm nay (ứng viên có thể đã quá khung giờ).
  // Lọc chính xác theo giờ kết thúc của slot được thực hiện ở tầng scheduler.
  @EntityGraph(attributePaths = { "user", "garage", "slot" })
  @Query("""
      SELECT b FROM Booking b
      WHERE b.status = swp391.carwash.enums.BookingStatus.PENDING
        AND b.bookingDate <= :today
      """)
  List<Booking> findPendingBookingsUpToDate(@Param("today") LocalDate today);
}
