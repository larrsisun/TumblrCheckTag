package TelegramBot.TumblrTagTracker.services;



import TelegramBot.TumblrTagTracker.dto.TumblrPostDTO;
import com.tumblr.jumblr.JumblrClient;
import com.tumblr.jumblr.types.Post;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class TumblrService {

    private final Logger log = LoggerFactory.getLogger(TumblrService.class);

    private final JumblrClient tumblrClient;
    private final RedisCacheService cacheService;
    private final TumblrRateLimiterService rateLimiter;

    private final int FETCH_LIMIT = 20;

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
        log.debug("Запрос к Tumblr API по тегу: {}, лимит={}", tag, FETCH_LIMIT);

        try {
            rateLimiter.waitForRateLimit();

            Map<String, Object> options = new HashMap<>();
            options.put("limit", FETCH_LIMIT);
            // filter: "text" - HTML, "raw" - без форматирования, "none" - без тела поста
            // Используем "text" для получения HTML-контента

            List<Post> posts = tumblrClient.tagged(tag, options);
            log.debug("Получено {} постов из Tumblr API по тегу {}", posts.size(), tag);

            return posts.stream()
                    .map(this::convertToDTO)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Ошибка при обращении к Tumblr API по тегу {}", tag, e);
            return Collections.emptyList();
        }
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
                com.tumblr.jumblr.types.TextPost textPost = (com.tumblr.jumblr.types.TextPost) post;
                if (textPost.getBody() != null) {
                    dto.setBody(textPost.getBody());
                    // Пытаемся извлечь изображение из HTML body
                    String imageUrl = extractImageFromHtml(textPost.getBody());
                    if (imageUrl != null) {
                        dto.setPhotoUrl(imageUrl);
                    }
                }
                if (textPost.getTitle() != null) {
                    dto.setSummary(textPost.getTitle());
                }
                break;
            case Post.PostType.PHOTO:
                com.tumblr.jumblr.types.PhotoPost photoPost = (com.tumblr.jumblr.types.PhotoPost) post;
                if (photoPost.getPhotos() != null && !photoPost.getPhotos().isEmpty()) {
                    com.tumblr.jumblr.types.Photo photo = photoPost.getPhotos().get(0);
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
                com.tumblr.jumblr.types.QuotePost quotePost = (com.tumblr.jumblr.types.QuotePost) post;
                if (quotePost.getText() != null) {
                    dto.setBody(quotePost.getText());
                }
                if (quotePost.getSource() != null) {
                    dto.setSummary(quotePost.getSource());
                }
                break;
            case Post.PostType.LINK:
                com.tumblr.jumblr.types.LinkPost linkPost = (com.tumblr.jumblr.types.LinkPost) post;
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

    /**
     * Извлекает URL первого изображения из HTML
     */
    private String extractImageFromHtml(String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }
        
        // Ищем img теги с src атрибутом
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = pattern.matcher(html);
        
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

    private List<TumblrPostDTO> fallbackGetPosts(String tag, Exception e) {
        log.warn("Tumblr API недоступен, используем fallback для тега {}", tag);
        return Collections.emptyList();
    }
}
