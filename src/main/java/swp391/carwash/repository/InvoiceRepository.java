package swp391.carwash.repository;

import java.util.Optional;
import java.util.List;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import swp391.carwash.entity.Invoice;
import swp391.carwash.enums.InvoiceStatus;

public interface InvoiceRepository extends JpaRepository<Invoice, Integer> {
    @EntityGraph(attributePaths = {"booking", "payment", "garage"})
    Optional<Invoice> findByBookingId(Integer bookingId);

    @EntityGraph(attributePaths = {"booking", "booking.user", "booking.garage", "booking.slot", "booking.service", "booking.vehicle", "payment", "garage"})
    @Query("select invoice from Invoice invoice where invoice.id = :id")
    Optional<Invoice> findDetailedById(Integer id);

    boolean existsByBookingId(Integer bookingId);

    boolean existsByInvoiceCode(String invoiceCode);

    List<Invoice> findByBookingIdIn(List<Integer> bookingIds);

    @EntityGraph(attributePaths = {"booking", "booking.user", "payment", "garage"})
    @Query(value = """
            select invoice from Invoice invoice
            where (:garageId is null or invoice.garage.id = :garageId)
              and (:status is null or invoice.status = :status)
              and (:fromTime is null or invoice.issuedAt >= :fromTime)
              and (:toTime is null or invoice.issuedAt < :toTime)
            order by invoice.issuedAt desc
            """,
            countQuery = """
            select count(invoice) from Invoice invoice
            where (:garageId is null or invoice.garage.id = :garageId)
              and (:status is null or invoice.status = :status)
              and (:fromTime is null or invoice.issuedAt >= :fromTime)
              and (:toTime is null or invoice.issuedAt < :toTime)
            """)
    Page<Invoice> findAdminInvoices(
            @Param("garageId") Integer garageId,
            @Param("status") InvoiceStatus status,
            @Param("fromTime") OffsetDateTime fromTime,
            @Param("toTime") OffsetDateTime toTime,
            Pageable pageable);

    long countByStatus(InvoiceStatus status);

    @Query("select coalesce(sum(invoice.totalAmount), 0) from Invoice invoice where invoice.status = :status")
    BigDecimal sumTotalAmountByStatus(@Param("status") InvoiceStatus status);

    @EntityGraph(attributePaths = {"booking", "booking.user", "booking.service", "garage"})
    @Query("""
            select invoice from Invoice invoice
            where invoice.status = :status
              and invoice.paidAt is not null
              and invoice.paidAt >= :fromTime
              and invoice.paidAt < :toTime
              and (:garageId is null or invoice.garage.id = :garageId)
            """)
    List<Invoice> findPaidForInsightPeriod(
            @Param("status") InvoiceStatus status,
            @Param("fromTime") OffsetDateTime fromTime,
            @Param("toTime") OffsetDateTime toTime,
            @Param("garageId") Integer garageId);

    default List<Invoice> findPaidForInsightPeriod(
            InvoiceStatus status,
            OffsetDateTime fromTime,
            OffsetDateTime toTime) {
        return findPaidForInsightPeriod(status, fromTime, toTime, null);
    }
}
