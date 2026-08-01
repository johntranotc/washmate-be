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

  /**
   * Số booking CHƯA kết thúc đang gán cho nhân viên này tại các garage sắp bị gỡ.
   * Dùng để chặn việc gỡ chi nhánh làm booking mồ côi người phụ trách — trigger
   * validate_assigned_staff_role chỉ chạy khi insert/update booking nên DB không tự chặn được.
   */
  @Query("""
      select count(b) from Booking b
      where b.assignedStaff.id = :staffUserId
        and b.garage.id in :garageIds
        and b.status in :activeStatuses
      """)
  long countActiveAssignmentsInGarages(
      @Param("staffUserId") Integer staffUserId,
      @Param("garageIds") Collection<Integer> garageIds,
      @Param("activeStatuses") Collection<BookingStatus> activeStatuses);

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
  org.springframework.data.domain.Page<Booking> findByUserIdOrderByCreatedAtDesc(
      Integer userId, org.springframework.data.domain.Pageable pageable);

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

  boolean existsByBookingCode(String bookingCode);

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

  /**
   * Ứng viên NO_SHOW: đơn đã CONFIRMED nhưng chưa được garage đưa vào quy trình
   * (chưa CHECKED_IN/WASHING), giới hạn trong một CỬA SỔ ngày.
   *
   * <p>Giới hạn dưới ({@code fromDate}) là bắt buộc. Không có nó, scheduler quét ngược vô
   * hạn vào lịch sử: mọi đơn CONFIRMED kẹt từ trước đều bị đánh vắng mặt, khách nhận thông
   * báo cho đơn từ hàng tháng trước, và tệ nhất là ăn án tích đủ để bị KHOÁ QUYỀN ĐẶT LỊCH
   * chỉ vì dọn dữ liệu cũ. Đơn cũ hơn cửa sổ này là rác dữ liệu cần xử lý riêng, không phải
   * khách vừa lỡ hẹn.
   */
  @EntityGraph(attributePaths = { "user", "garage", "slot" })
  @Query("""
      SELECT b FROM Booking b
      WHERE b.status = swp391.carwash.enums.BookingStatus.CONFIRMED
        AND b.bookingDate <= :today
        AND b.bookingDate >= :fromDate
      """)
  List<Booking> findConfirmedBookingsInWindow(
      @Param("fromDate") LocalDate fromDate,
      @Param("today") LocalDate today);

  /**
   * Số đơn đang mở mà khách CHƯA TRẢ TIỀN, từ hôm nay trở đi.
   *
   * <p>Đây là hàng rào chống phá hoại: chỉ chặn trùng slot là chưa đủ vì kẻ phá vẫn rải
   * được đơn khắp các slot/ngày khác nhau để giữ kín garage mà không trả đồng nào (đơn CASH
   * chỉ bị dọn SAU khi khung giờ trôi qua — lúc đó đã chiếm chỗ xong rồi).
   *
   * <p>Cố ý đếm theo đơn CHƯA thanh toán chứ không phải mọi đơn đang mở: khách thật trả tiền
   * xong thì không bao giờ chạm trần, còn kẻ phá không trả tiền sẽ tắc ngay ở đơn thứ N.
   */
  @Query("""
      select count(b) from Booking b, Payment p
      where p.booking.id = b.id
        and b.user.id = :userId
        and b.status in :statuses
        and b.bookingDate >= :fromDate
        and p.status <> swp391.carwash.enums.PaymentStatus.PAID
      """)
  long countUnpaidOpenBookingsForUser(
      @Param("userId") Integer userId,
      @Param("statuses") Collection<BookingStatus> statuses,
      @Param("fromDate") LocalDate fromDate);

  /**
   * Số đơn đang mở của một khách tại MỘT garage trong MỘT ngày. Chặn kiểu tấn công
   * "ôm trọn lịch của một garage trong ngày cao điểm" — trần tổng theo khách vẫn cho phép
   * dồn hết hạn mức vào đúng một garage/một ngày.
   */
  @Query("""
      select count(b) from Booking b
      where b.user.id = :userId
        and b.garage.id = :garageId
        and b.bookingDate = :bookingDate
        and b.status in :statuses
      """)
  long countOpenBookingsForUserInGarageOnDate(
      @Param("userId") Integer userId,
      @Param("garageId") Integer garageId,
      @Param("bookingDate") LocalDate bookingDate,
      @Param("statuses") Collection<BookingStatus> statuses);

  /**
   * Số lần khách bị đánh dấu vắng mặt trong một cửa sổ thời gian gần đây.
   *
   * <p>Đây là "án tích" dùng để chặn khách liên tục đặt rồi không tới — kiểu phá hoại mà
   * trần đơn đang mở KHÔNG bắt được: kẻ phá đặt 2 đơn, để trôi qua giờ cho scheduler dọn,
   * rồi lại đặt tiếp, lặp vô hạn mà không bao giờ chạm trần đơn treo.
   *
   * <p>Chỉ đếm đơn CÒN đang ở trạng thái NO_SHOW: staff xoá án oan bằng cách chuyển đơn về
   * CANCELLED (xem BookingService.excuseNoShow) là nó tự rụng khỏi phép đếm này.
   */
  @Query("""
      select count(b) from Booking b
      where b.user.id = :userId
        and b.status = swp391.carwash.enums.BookingStatus.NO_SHOW
        and b.noShowAt >= :since
      """)
  long countRecentNoShows(
      @Param("userId") Integer userId,
      @Param("since") OffsetDateTime since);

  /**
   * Số đơn CHƯA THANH TOÁN, thu tiền mặt, đang giữ chỗ trên một slot cụ thể.
   *
   * <p>Đơn CASH là thứ duy nhất giữ slot MIỄN PHÍ và VÔ THỜI HẠN cho tới hết khung giờ —
   * đơn VNPAY chưa trả đã có {@code expiresAt} tự dọn sau ít phút. Nên nếu muốn giới hạn
   * phần capacity có thể bị chiếm mà không tốn gì, đây chính là thứ phải đếm.
   */
  @Query("""
      select count(b) from Booking b, Payment p
      where p.booking.id = b.id
        and b.slot.id = :slotId
        and b.garage.id = :garageId
        and b.bookingDate = :bookingDate
        and b.status in :statuses
        and p.method = swp391.carwash.enums.PaymentMethod.CASH
        and p.status <> swp391.carwash.enums.PaymentStatus.PAID
        and (:excludeBookingId is null or b.id <> :excludeBookingId)
      """)
  long countUnpaidCashBookingsOnSlot(
      @Param("slotId") Integer slotId,
      @Param("garageId") Integer garageId,
      @Param("bookingDate") LocalDate bookingDate,
      @Param("statuses") Collection<BookingStatus> statuses,
      @Param("excludeBookingId") Integer excludeBookingId);

  /** Đã từng rửa xe xong lần nào chưa — dùng để phân biệt tài khoản mới với khách quen. */
  @Query("""
      select count(b) from Booking b
      where b.user.id = :userId
        and b.status = swp391.carwash.enums.BookingStatus.COMPLETED
      """)
  long countCompletedBookingsForUser(@Param("userId") Integer userId);
}
