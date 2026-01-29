package TelegramBot.TumblrTagTracker.util;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ContentExtractor {

    public Optional<String> extractFirstImageUrl(String html) {
        if (html == null || html.isEmpty()) {
            return Optional.empty();
        }

        Pattern pattern = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);

        if (matcher.find()) {
            String imageUrl = matcher.group(1);
            if (isValidImageUrl(imageUrl)) {
                return Optional.of(imageUrl);
            }
        }
        return null;
    }

    public Optional<String> extractFirstVideoUrl(String html) {
        if (html == null || html.isEmpty()) {
            return Optional.empty();
        }

        Pattern pattern = Pattern.compile("<video[^>]+src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);

        if (matcher.find()) {
            String videoUrl = matcher.group(1);
            if (isValidVideoUrl(videoUrl)) {
                return Optional.of(videoUrl);
            }
        }
        return null;
    }

    private boolean isValidImageUrl(String url) {
        return url != null &&
                (url.startsWith("http://") || url.startsWith("https://")) &&
                url.toLowerCase().matches(".*\\.(jpg|jpeg|png|gif|bmp|webp|svg)(\\?.*)?$");
    }

    private boolean isValidVideoUrl(String url) {
        return url != null &&
                (url.startsWith("http://") || url.startsWith("https://")) &&
                url.toLowerCase().matches(".*\\.(mp4|webm|mov|avi|mkv|flv|wmv|m4v)(\\?.*)?$");
    }
}
