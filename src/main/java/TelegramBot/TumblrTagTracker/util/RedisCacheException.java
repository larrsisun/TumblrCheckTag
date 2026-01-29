package TelegramBot.TumblrTagTracker.util;

public class RedisCacheException extends RuntimeException {
    public RedisCacheException(String message) {
        super(message);
    }
}
