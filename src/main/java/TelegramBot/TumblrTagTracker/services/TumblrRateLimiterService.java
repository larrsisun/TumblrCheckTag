package TelegramBot.TumblrTagTracker.services;

import TelegramBot.TumblrTagTracker.util.RateLimitExceededException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class TumblrRateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(TumblrRateLimiterService.class);

    private final RateLimiter rateLimiter;

    public TumblrRateLimiterService() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(20) // 20 запросов
                .limitRefreshPeriod(Duration.ofMinutes(1)) // за 1 минуту
                .timeoutDuration(Duration.ofSeconds(30)) // максимальное ожидание токена
                .build();

        RateLimiterRegistry rateLimiterRegistry = RateLimiterRegistry.of(config);
        this.rateLimiter = rateLimiterRegistry.rateLimiter("tumblr-api");

        rateLimiter.getEventPublisher().onSuccess(event -> log.debug("Токен получен. Доступно: {}",
                rateLimiter.getMetrics().getAvailablePermissions())).onFailure(event ->
                log.error("Не удалось получить токен."));
    }

    public void waitForRateLimit() {
        try {
            boolean acquired = rateLimiter.acquirePermission();
            if (!acquired) {
                throw new RateLimitExceededException("Rate limit превышен");
            }

        } catch (RequestNotPermitted e) {
            log.error("Превышен лимит запросов (20/минуту)");
            throw new RateLimitExceededException("Превышен лимит запросов к Tumblr API");
        } catch (Exception e) {
            log.error("Ошибка при ожидании rate limit", e);
            throw new RuntimeException("Rate limiter error", e);
        }
    }

    public boolean tryAcquire() {
        return rateLimiter.acquirePermission();
    }
}
