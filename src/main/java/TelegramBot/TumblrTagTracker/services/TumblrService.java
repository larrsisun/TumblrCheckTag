package TelegramBot.TumblrTagTracker.services;



import TelegramBot.TumblrTagTracker.dto.TumblrPostDTO;
import com.tumblr.jumblr.JumblrClient;
import com.tumblr.jumblr.types.*;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class TumblrService {

    private final Logger log = LoggerFactory.getLogger(TumblrService.class);

    private final JumblrClient tumblrClient;
    private final RedisCacheService cacheService;
    private final TumblrRateLimiterService rateLimiter;

    private final int FETCH_LIMIT = 20;
    private static final int MIN_NOTES = 5; // Минимальное количество заметок


    @Autowired
    public TumblrService(JumblrClient tumblrClient, RedisCacheService cacheService, TumblrRateLimiterService rateLimiter) {
        this.tumblrClient = tumblrClient;
        this.cacheService = cacheService;
        this.rateLimiter = rateLimiter;
    }

    public List<TumblrPostDTO> getNewPostsByTags(Set<String> tags) {
        if (tags == null || tags.isEmpty()) {
            log.debug("Теги не указаны, возвращаем пустой список");
            return Collections.emptyList();
        }

        log.info("Поиск постов по тегам: {}", tags);

        // Используем Map для исключения дубликатов по ID
        Map<String, TumblrPostDTO> allPostsMap = new HashMap<>();

        for (String tag : tags) {
            try {
                List<TumblrPostDTO> postsForTag = getPostsByTag(tag);
                for (TumblrPostDTO post : postsForTag) {
                    if (!cacheService.wasSent(post.getId())) {
                        allPostsMap.putIfAbsent(post.getId(), post);
                    }
                }
            } catch (Exception e) {
                log.error("Ошибка при получении постов по тегу {}", tag, e);
            }
        }

        // Фильтруем только новые посты (которые еще не были отправлены)
        List<TumblrPostDTO> newPosts = allPostsMap.values().stream()
                .filter(post -> post.getId() != null && !cacheService.wasSent(post.getId()))
                .collect(Collectors.toList());

        log.info("Найдено {} новых постов", newPosts.size());
        return newPosts;
    }


    @CircuitBreaker(name = "tumblr", fallbackMethod = "fallbackGetPosts")
    private List<TumblrPostDTO> getPostsByTag(String tag) {
        try {
            rateLimiter.waitForRateLimit();

            Map<String, Object> options = new HashMap<>();
            options.put("limit", FETCH_LIMIT);
            // filter: "text" - HTML, "raw" - без форматирования, "none" - без тела поста
            // Используем "text" для получения HTML-контента

            List<Post> posts = tumblrClient.tagged(tag, options);
            log.debug("Получено {} постов из Tumblr API по тегу {}", posts.size(), tag);

            List<TumblrPostDTO> filteredPosts = posts.stream()
                    .filter(this::isPostValid)
                    .map(this::convertToDTO)
                    .collect(Collectors.toList());

            log.debug("После фильтрации осталось {} постов по тегу {}", filteredPosts.size(), tag);
            return filteredPosts;

        } catch (Exception e) {
            log.error("Ошибка при обращении к Tumblr API по тегу {}", tag, e);
            return Collections.emptyList();
        }
    }

    private boolean isPostValid(Post post) {
        // Проверка количества лайков (noteCount включает в себя все взаимодействия: лайки, реблоги, комментарии)
        Long noteCount = post.getNoteCount();
        if (noteCount == null || noteCount <= MIN_NOTES) {
            log.debug("Пост {} пропущен: недостаточно взаимодействий (noteCount: {})", post.getId(), noteCount);
            return false;
        }
        log.debug("Пост {} прошёл фильтрацию (noteCount: {}, timestamp: {})", post.getId(), noteCount, post.getTimestamp());
        return true;
    }

    private TumblrPostDTO convertToDTO(Post post) {
        TumblrPostDTO dto = new TumblrPostDTO();

        dto.setId(String.valueOf(post.getId()));
        dto.setBlogName(post.getBlogName());
        dto.setPostURL(post.getPostUrl());

        // Обработка timestamp
        if (post.getTimestamp() != null) {
            dto.setTimestamp(post.getTimestamp());
        }

        dto.setType(post.getType());
        dto.setTags(post.getTags());

        // В зависимости от типа поста извлекаем разные данные
        switch (post.getType()) {
            case Post.PostType.TEXT:
                TextPost textPost = (TextPost) post;
                if (textPost.getBody() != null) {
                    dto.setBody(textPost.getBody());
                    String imageUrl = extractImageFromHtml(textPost.getBody());
                    String videoUrl = extractTumblrVideoFromHtml(textPost.getBody());
                    if (imageUrl != null) {
                        dto.setPhotoUrl(imageUrl);
                    }

                    if (videoUrl != null) {
                        dto.setVideoUrl(videoUrl);
                    }
                }
                if (textPost.getTitle() != null) {
                    dto.setSummary(textPost.getTitle());
                }
                break;
            case Post.PostType.PHOTO:
                PhotoPost photoPost = (PhotoPost) post;
                if (photoPost.getPhotos() != null && !photoPost.getPhotos().isEmpty()) {
                    Photo photo = photoPost.getPhotos().get(0);
                    if (photo != null && photo.getOriginalSize() != null) {
                        dto.setPhotoUrl(photo.getOriginalSize().getUrl());
                    }
                }
                if (photoPost.getCaption() != null) {
                    dto.setSummary(photoPost.getCaption());
                }
                if (photoPost.getSourceUrl() != null) {
                    dto.setSourceUrl(photoPost.getSourceUrl());
                }
                break;
            case Post.PostType.QUOTE:
                QuotePost quotePost = (QuotePost) post;
                if (quotePost.getText() != null) {
                    dto.setBody(quotePost.getText());
                }
                if (quotePost.getSource() != null) {
                    dto.setSummary(quotePost.getSource());
                }
                break;
            case Post.PostType.LINK:
                LinkPost linkPost = (LinkPost) post;
                if (linkPost.getTitle() != null) {
                    dto.setSummary(linkPost.getTitle());
                }
                if (linkPost.getDescription() != null) {
                    dto.setBody(linkPost.getDescription());
                }
                if (linkPost.getLinkUrl() != null) {
                    dto.setSourceUrl(linkPost.getLinkUrl());
                }
                break;
            default:
                dto.setSummary(post.getType() + " post");
                break;
        }

        return dto;
    }

    private String extractImageFromHtml(String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }
        
        // Ищем img теги с src атрибутом
        Pattern pattern = Pattern.compile(
            "<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pattern.matcher(html);
        
        if (matcher.find()) {
            String imageUrl = matcher.group(1);
            // Проверяем, что это валидный URL изображения
            if (imageUrl != null && 
                (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) &&
                (imageUrl.contains("media.tumblr.com") || 
                 imageUrl.matches(".*\\.(jpg|jpeg|png|gif|bmp|webp)(\\?.*)?$"))) {
                return imageUrl;
            }
        }
        
        return null;
    }

    private String extractTumblrVideoFromHtml(String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }

        // Сначала ищем в тегах video
        Pattern pattern = Pattern.compile(
                "<video[^>]+src=[\"']([^\"']+)[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pattern.matcher(html);

        if (matcher.find()) {
            String videoUrl = matcher.group(1);
            // Проверяем, что это валидный URL видео с Tumblr
            if (videoUrl != null &&
                    (videoUrl.startsWith("http://") || videoUrl.startsWith("https://")) &&
                    (videoUrl.contains("media.tumblr.com") ||
                            videoUrl.contains("video.tumblr.com") ||
                            videoUrl.matches(".*\\.(mp4|webm|mov)(\\?.*)?$"))) {
                return videoUrl;
            }
        }

        return null;
    }

    private List<TumblrPostDTO> fallbackGetPosts(String tag, Exception e) {
        log.warn("Tumblr API недоступен, используем fallback для тега {}", tag);
        return Collections.emptyList();
    }
}
