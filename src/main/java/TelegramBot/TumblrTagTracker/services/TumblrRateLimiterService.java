package TelegramBot.TumblrTagTracker.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TumblrRateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(TumblrRateLimiterService.class);
    // Tumblr API лимиты: 5000 запросов в день
    private static final int MAX_REQ_PER_MINUTE = 100;
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration REQ_INTERVAL = Duration.ofSeconds(3);

    private Instant lastRequestTime = Instant.EPOCH;
    private Instant windowStart = Instant.now();
    private final AtomicInteger count = new AtomicInteger(0);

    public synchronized void waitForRateLimit() {
        Instant now = Instant.now();

        Duration timeSinceLastRequest = Duration.between(lastRequestTime, now);
        if (timeSinceLastRequest.compareTo(REQ_INTERVAL) < 0) {
            waitFor(REQ_INTERVAL.minus(timeSinceLastRequest));
            now = Instant.now();
        }

        Duration timeSinceWindowStart = Duration.between(windowStart, now);
        if (timeSinceWindowStart.compareTo(MINUTE) >= 0) {
            windowStart = now;
            count.set(0);
        } else if (count.get() >= MAX_REQ_PER_MINUTE) {
            Duration waitTime = MINUTE.minus(timeSinceWindowStart);
            log.warn("Превышен лимит {} запросов в минуту, ожидание {} секунд.", MAX_REQ_PER_MINUTE, waitTime.getSeconds());

            waitFor(waitTime);

            windowStart = Instant.now();
            count.set(0);
        }

        lastRequestTime = Instant.now();
        count.incrementAndGet();

        log.debug("Запрос {}/{} в текущем окне",
                count.get(), MAX_REQ_PER_MINUTE);
    }


    private void waitFor(Duration duration) {
        try {
            long millis = duration.toMillis();
            if (millis > 0) {
                log.debug("Ожидание {} мс.", millis);
                Thread.sleep(millis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Ожидание прервано", e);
        }
    }


}
