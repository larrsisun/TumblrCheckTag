package TelegramBot.TumblrTagTracker.services;

import TelegramBot.TumblrTagTracker.util.RedisCacheException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);
    private static final String PREFIX_USER_POST = "user_post:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, String> redisTemplate;

    @Autowired
    public RedisCacheService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // Проверка: был ли пост отправлен ЭТОМУ пользователю
    public boolean wasSentToUser(Long userId, String postId) {
        try {
            String key = PREFIX_USER_POST + userId + ":" + postId;
            Boolean exists = redisTemplate.hasKey(key);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("Не удалось проверить отправку поста {} пользователю {}", postId, userId, e);
            return false;
        }
    }

    // Пометить как отправленный ЭТОМУ пользователю
    public boolean markAsSentToUser(Long userId, String postId) {
        try {
            String key = PREFIX_USER_POST + userId + ":" + postId;
            Boolean wasSet = redisTemplate.opsForValue().setIfAbsent(key, "1", DEFAULT_TTL);
            log.debug("Пост {} помечен как отправленный пользователю {} (TTL: {})",
                    postId, userId, DEFAULT_TTL);
            return Boolean.TRUE.equals(wasSet);
        } catch (Exception e) {
            log.error("Не удалось пометить пост {} для пользователя {}", postId, userId, e);
            return false;
        }
    }
}
