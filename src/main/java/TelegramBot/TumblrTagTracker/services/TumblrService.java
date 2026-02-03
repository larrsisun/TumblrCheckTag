package TelegramBot.TumblrTagTracker.services;

import TelegramBot.TumblrTagTracker.dto.TumblrPostDTO;
import TelegramBot.TumblrTagTracker.util.ContentExtractor;
import com.tumblr.jumblr.JumblrClient;
import com.tumblr.jumblr.types.*;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class TumblrService {

    private final Logger log = LoggerFactory.getLogger(TumblrService.class);

    private final JumblrClient tumblrClient;
    private final TumblrRateLimiterService rateLimiter;
    private final ContentExtractor contentExtractor;
    private final PostTrackingService postTrackingService;

    private final int FETCH_LIMIT = 20;

    @Autowired
    public TumblrService(JumblrClient tumblrClient,
                         TumblrRateLimiterService rateLimiter,
                         PostTrackingService postTrackingService,
                         ContentExtractor contentExtractor) {
        this.tumblrClient = tumblrClient;
        this.rateLimiter = rateLimiter;
        this.contentExtractor = contentExtractor;
        this.postTrackingService = postTrackingService;
    }

    /**
     * Получает новые посты по тегам.
     * ВАЖНО: Больше не фильтрует по per-user доставке - это делается в scheduler
     */
    public List<TumblrPostDTO> getNewPostsByTags(Set<String> tags) {

        if (tags == null || tags.isEmpty()) {
            log.debug("Теги не указаны, возвращаем пустой список");
            return Collections.emptyList();
        }

        log.info("Поиск постов по тегам: {}", tags);
        Map<String, TumblrPostDTO> allPostsMap = new ConcurrentHashMap<>();

        for (String tag : tags) {
            try {
                if (!rateLimiter.tryAcquire()) {
                    log.warn("Достигнут лимит запросов, прерываем сбор постов");
                    break;
                }

                List<TumblrPostDTO> postsForTag = getPostsByTag(tag);

                for (TumblrPostDTO post : postsForTag) {
                    // Проверяем только глобальные фильтры (возраст, заметки)
                    if (postTrackingService.shouldSendPostNow(post)) {
                        allPostsMap.putIfAbsent(post.getId(), post);
                    }
                }

                log.debug("По тегу {} найдено {} постов, прошедших фильтры",
                        tag, postsForTag.size());

            } catch (Exception e) {
                log.error("Ошибка при получении постов по тегу {}", tag, e);
            }
        }

        List<TumblrPostDTO> newPosts = new ArrayList<>(allPostsMap.values());
        log.info("Найдено {} постов всего", newPosts.size());
        return newPosts;
    }

    @CircuitBreaker(name = "tumblr", fallbackMethod = "fallbackGetPosts")
    private List<TumblrPostDTO> getPostsByTag(String tag) {
        try {
            rateLimiter.waitForRateLimit();

            Map<String, Object> options = new HashMap<>();
            options.put("limit", FETCH_LIMIT);

            List<Post> posts = tumblrClient.tagged(tag, options);
            log.debug("Получено {} постов по тегу {} от Tumblr API", posts.size(), tag);

            return posts.stream()
                    .map(this::convertToDTO)
                    .collect(Collectors.toList());

        } catch (RequestNotPermitted r) {
            log.warn("Рейт лимит превышен для тега {}", tag);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Ошибка при обращении к Tumblr API по тегу {}", tag, e);
            throw e; // Пробрасываем для CircuitBreaker
        }
    }

    private TumblrPostDTO convertToDTO(Post post) {
        TumblrPostDTO dto = new TumblrPostDTO();

        dto.setId(String.valueOf(post.getId()));
        dto.setBlogName(post.getBlogName());
        dto.setPostURL(post.getPostUrl());

        if (post.getTimestamp() != null) {
            dto.setTimestamp(post.getTimestamp());
        }

        if (post.getNoteCount() != null) {
            dto.setNoteCount(String.valueOf(post.getNoteCount()));
        }

        dto.setType(post.getType());
        dto.setTags(post.getTags());

        // В зависимости от типа поста извлекаем разные данные
        switch (post.getType()) {

            case TEXT:
                TextPost textPost = (TextPost) post;

                Optional.ofNullable(textPost.getBody()).ifPresent(body -> {
                    dto.setBody(body);
                    contentExtractor.extractFirstImageUrl(body).ifPresent(dto::setPhotoUrl);
                    contentExtractor.extractFirstVideoUrl(body).ifPresent(dto::setVideoUrl);
                });

                if (textPost.getTitle() != null) {
                    dto.setSummary(textPost.getTitle());
                }
                break;

            case PHOTO:
                PhotoPost photoPost = (PhotoPost) post;
                if (photoPost.getPhotos() != null && !photoPost.getPhotos().isEmpty()) {
                    Photo photo = photoPost.getPhotos().get(0);
                    if (photo != null && photo.getOriginalSize() != null) {
                        dto.setPhotoUrl(photo.getOriginalSize().getUrl());
                    }
                }
                if (photoPost.getCaption() != null) {
                    dto.setSummary(photoPost.getCaption());
                    dto.setBody(photoPost.getCaption());
                }
                if (photoPost.getSourceUrl() != null) {
                    dto.setSourceUrl(photoPost.getSourceUrl());
                }
                break;

            case VIDEO:
                VideoPost videoPost = (VideoPost) post;
                if (videoPost.getVideos() != null && !videoPost.getVideos().isEmpty()) {
                    Video video = videoPost.getVideos().get(0);
                    if (video != null) {
                        dto.setVideoUrl(video.getEmbedCode());
                    }
                }

                if (videoPost.getCaption() != null) {
                    dto.setSummary(videoPost.getCaption());
                    dto.setBody(videoPost.getCaption());
                }

                if (videoPost.getSourceUrl() != null) {
                    dto.setSourceUrl(videoPost.getSourceUrl());
                }
                break;

            case QUOTE:
                QuotePost quotePost = (QuotePost) post;
                if (quotePost.getText() != null) {
                    dto.setBody(quotePost.getText());
                }
                if (quotePost.getSource() != null) {
                    dto.setSummary(quotePost.getSource());
                }
                break;

            case LINK:
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

            case ANSWER:
                AnswerPost answerPost = (AnswerPost) post;

                if (answerPost.getQuestion() != null) {
                    dto.setQuestion(answerPost.getQuestion());
                }

                if (answerPost.getAnswer() != null) {
                    dto.setAnswer(answerPost.getAnswer());
                }
                break;

            default:
                dto.setSummary(post.getType() + " post");
                break;
        }

        return dto;
    }

    private List<TumblrPostDTO> fallbackGetPosts(String tag, Exception e) {
        log.warn("Tumblr API недоступен, используем fallback для тега {}", tag, e);
        return Collections.emptyList();
    }
}
