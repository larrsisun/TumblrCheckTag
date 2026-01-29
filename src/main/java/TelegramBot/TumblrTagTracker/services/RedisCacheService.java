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
    private static final String PREFIX_SENT_POSTS = "sent_post:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, String> redisTemplate;

    @Autowired
    public RedisCacheService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean wasSent(String postID) {
        try {
            String key = PREFIX_SENT_POSTS + postID;
            Boolean exists = redisTemplate.hasKey(key);
            return Boolean.TRUE.equals(exists);
        } catch (RedisCacheException e) {
            log.error("Не удалось проверить, отправлен ли пост {}.", postID, e);
            return false;
        }
    }

    public boolean markAsSentIfNotSent(String postID) {
        try {
            String key = PREFIX_SENT_POSTS + postID;
            Boolean wasSet = redisTemplate.opsForValue().setIfAbsent(key, "1", DEFAULT_TTL);
            log.debug("Пост {} помечен как отправленный (TTL: {})", postID, DEFAULT_TTL);
            return Boolean.TRUE.equals(wasSet);
        } catch (Exception e) {
            log.error("Не удалось пометить пост {} как отправленный.", postID, e);
            return false;
        }
    }

}
