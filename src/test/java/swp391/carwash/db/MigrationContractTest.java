package swp391.carwash.db;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MigrationContractTest {
    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Path ENTITY_DIR = Path.of("src/main/java/swp391/carwash/entity");

    // Lấy tên bảng ngay sau "@Table(" — không dính name= của @UniqueConstraint lồng bên trong.
    private static final Pattern TABLE_NAME = Pattern.compile("@Table\\s*\\(\\s*name\\s*=\\s*\"([a-zA-Z_]+)\"");

    /**
     * Chốt chặn cho lỗi đã từng xảy ra: notification, promotion, promotion_usage, reward,
     * reward_redemption có entity nhưng KHÔNG có trong migration.
     *
     * <p>Profile local dùng {@code ddl-auto=create-drop} + flyway tắt nên Hibernate tự sinh
     * bảng, chạy vẫn ngon; profile supabase dùng {@code ddl-auto=validate} + flyway bật thì
     * app không khởi động nổi ("Schema-validation: missing table"). Test này bắt lệch ngay
     * ở CI thay vì để lộ ra lúc deploy.
     */
    @Test
    void everyEntityTableExistsInMigrations() throws IOException {
        String allMigrations;
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            allMigrations = files
                    .filter(path -> path.toString().endsWith(".sql"))
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (IOException e) {
                            throw new IllegalStateException("Cannot read " + path, e);
                        }
                    })
                    .collect(Collectors.joining("\n"));
        }

        List<String> missing = new ArrayList<>();
        try (Stream<Path> entities = Files.list(ENTITY_DIR)) {
            for (Path entity : entities.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = TABLE_NAME.matcher(Files.readString(entity));
                if (!matcher.find()) {
                    continue; // entity không khai báo @Table(name=...) -> bỏ qua
                }
                String table = matcher.group(1);
                boolean declared = Pattern
                        .compile("CREATE TABLE (IF NOT EXISTS )?" + table + "\\b", Pattern.CASE_INSENSITIVE)
                        .matcher(allMigrations)
                        .find();
                if (!declared) {
                    missing.add(table + " (" + entity.getFileName() + ")");
                }
            }
        }

        assertTrue(missing.isEmpty(),
                "Các bảng có entity nhưng thiếu CREATE TABLE trong db/migration: " + missing);
    }

    /**
     * payment/invoice tham chiếu booking bằng cặp (booking_id, garage_id). Khi đổi garage,
     * Hibernate không đảm bảo UPDATE booking chạy trước UPDATE payment, nên FK phải hoãn
     * kiểm tra tới lúc COMMIT — nếu không sẽ vi phạm FK ngẫu nhiên.
     */
    @Test
    void bookingGarageForeignKeysAreDeferrable() throws IOException {
        String migration = Files.readString(MIGRATION_DIR.resolve("V12__defer_booking_garage_fks.sql"));

        assertTrue(migration.contains("payment_booking_id_garage_id_fkey"));
        assertTrue(migration.contains("invoice_booking_id_garage_id_fkey"));
        assertTrue(migration.contains("DEFERRABLE INITIALLY DEFERRED"));
    }

    /** UNIQUE(user_id, promotion_id) là thứ PromotionReleaseService xoá để hoàn mã cho khách. */
    @Test
    void promotionUsageKeepsOneUsePerCustomerConstraint() throws IOException {
        String migration = Files.readString(MIGRATION_DIR.resolve("V11__missing_core_tables.sql"));

        assertTrue(migration.contains("CONSTRAINT uq_user_promotion UNIQUE (user_id, promotion_id)"));
    }

    @Test
    void initialSchemaDefinesCapacityAndPaymentGuards() throws IOException {
        String migration = Files.readString(MIGRATION_DIR.resolve("V1__baseline_schema.sql"));

        assertTrue(migration.contains("CREATE TRIGGER trg_check_booking_slot_capacity"));
        assertTrue(migration.contains("CREATE TRIGGER trg_refresh_booking_slot_capacity_insert_update"));
        assertTrue(migration.contains("CREATE TRIGGER trg_refresh_booking_slot_capacity_delete"));
        assertTrue(migration.contains("CREATE TRIGGER trg_validate_payment_paid_amount"));
    }

    @Test
    void publicSchemaMigrationEnablesRlsAndRevokesDataApiRoles() throws IOException {
        String migration = Files.readString(MIGRATION_DIR.resolve("V1__baseline_schema.sql"));

        assertTrue(migration.contains("ENABLE ROW LEVEL SECURITY"));
        assertTrue(migration.contains("rolname = 'anon'"));
        assertTrue(migration.contains("rolname = 'authenticated'"));
        assertTrue(migration.contains("REVOKE ALL PRIVILEGES ON ALL TABLES"));
        assertFalse(migration.contains("CREATE POLICY"));
    }

    @Test
    void insightMigrationEnablesRlsAndAddsReportingIndexes() throws IOException {
        String migration = Files.readString(MIGRATION_DIR.resolve("V1__baseline_schema.sql"));
        String sourceMigration = Files.readString(MIGRATION_DIR.resolve("V1__baseline_schema.sql"));

        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS insight_rule_config"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS business_insight"));
        assertTrue(migration.contains("CREATE TABLE insight_ai_enrichment"));
        assertTrue(migration.contains("ALTER TABLE business_insight ENABLE ROW LEVEL SECURITY"));
        assertTrue(migration.contains("ALTER TABLE insight_ai_enrichment ENABLE ROW LEVEL SECURITY"));
        assertTrue(migration.contains("idx_invoice_insight_paid_status_date"));
        assertTrue(migration.contains("idx_booking_insight_booking_date"));
        assertTrue(sourceMigration.contains("source VARCHAR(20)"));
        assertTrue(sourceMigration.contains("evidence_json JSONB"));
        assertTrue(sourceMigration.contains("verified BOOLEAN"));
    }
}
