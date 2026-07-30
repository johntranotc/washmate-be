package swp391.carwash.Schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import swp391.carwash.service.LoyaltyPointExpiryService;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoyaltyPointExpiryScheduler {

    private final LoyaltyPointExpiryService expiryService;

    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Ho_Chi_Minh")
    public void expirePoints() {
        log.info("Start expire loyalty points");

        expiryService.expirePoints();

        log.info("Finish expire loyalty points");
    }
}
