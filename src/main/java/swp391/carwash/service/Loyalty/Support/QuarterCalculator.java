package swp391.carwash.service.Loyalty.Support;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;
import swp391.carwash.common.TimeZones;

@Component
public class QuarterCalculator {


    public QuarterPeriod previousQuarter() {
        LocalDate currentQuarterStart = getCurrentQuarterStart();
        LocalDate previousQuarterStart = currentQuarterStart.minusMonths(3);

        return new QuarterPeriod(
                previousQuarterStart.atStartOfDay().atOffset(ZoneOffset.UTC),
                currentQuarterStart.atStartOfDay().atOffset(ZoneOffset.UTC)
        );
    }

    public QuarterPeriod currentQuarter() {
        LocalDate currentQuarterStart = getCurrentQuarterStart();
        LocalDate nextQuarterStart = currentQuarterStart.plusMonths(3);

        return new QuarterPeriod(
                currentQuarterStart.atStartOfDay().atOffset(ZoneOffset.UTC),
                nextQuarterStart.atStartOfDay().atOffset(ZoneOffset.UTC)
        );
    }

    private LocalDate getCurrentQuarterStart() {
        LocalDate today = LocalDate.now(TimeZones.VIETNAM);
        int currentQuarter = ((today.getMonthValue() - 1) / 3) + 1;

        return switch (currentQuarter) {
            case 1 -> LocalDate.of(today.getYear(), 1, 1);
            case 2 -> LocalDate.of(today.getYear(), 4, 1);
            case 3 -> LocalDate.of(today.getYear(), 7, 1);
            default -> LocalDate.of(today.getYear(), 10, 1);
        };
    }
}
