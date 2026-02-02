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
import org.springframework.web.servlet.view.BeanNameViewResolver;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class TumblrService {

    private final Logger log = LoggerFactory.getLogger(TumblrService.class);

    private final JumblrClient tumblrClient;
    private final RedisCacheService cacheService;
    private final TumblrRateLimiterService rateLimiter;
    private final PostTrackingService postTrackingService;
    private final ContentExtractor contentExtractor;

    private final int FETCH_LIMIT = 20;

    @Autowired
    public TumblrService(JumblrClient tumblrClient, RedisCacheService cacheService,
                         TumblrRateLimiterService rateLimiter, PostTrackingService postTrackingService,
                         ContentExtractor contentExtractor) {
        this.tumblrClient = tumblrClient;
        this.cacheService = cacheService;
        this.rateLimiter = rateLimiter;
        this.postTrackingService = postTrackingService;
        this.contentExtractor = contentExtractor;
    }

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
                    if (!cacheService.wasSent(post.getId())) {
                        allPostsMap.putIfAbsent(post.getId(), post);
                    }
                }
                log.debug("По тегу {} найдено {} новых постов", tag, postsForTag.size());
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
            options.put("filter", "text");

            List<Post> posts = tumblrClient.tagged(tag, options);
            log.debug("Получено {} постов по тегу {}", posts.size(), tag);

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

            case Post.PostType.TEXT:
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

            case Post.PostType.PHOTO:
                PhotoPost photoPost = (PhotoPost) post;
                if (photoPost.getPhotos() != null && !photoPost.getPhotos().isEmpty()) {

                    Photo photo = photoPost.getPhotos().getFirst();
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

            case Post.PostType.VIDEO:
                VideoPost videoPost = (VideoPost) post;
                if (videoPost.getVideos() != null && !videoPost.getVideos().isEmpty()) {
                    Video video = videoPost.getVideos().getFirst();
                    if (video != null) {
                        dto.setVideoUrl(video.getEmbedCode());
                    }
                }

                if (videoPost.getCaption() != null) {
                    dto.setSummary(videoPost.getCaption());
                }

                if (videoPost.getSourceUrl() != null) {
                    dto.setSourceUrl(videoPost.getSourceUrl());
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

            case Post.PostType.ANSWER:
                AnswerPost answerPost = (AnswerPost) post;

                if (answerPost.getQuestion() != null) {
                    dto.setQuestion(answerPost.getQuestion());
                }

                if (answerPost.getAnswer() != null) {
                    dto.setAnswer(answerPost.getAnswer());
                }

            default:
                dto.setSummary(post.getType() + " post");
                break;
        }
        return dto;
    }

    private List<TumblrPostDTO> fallbackGetPosts(String tag, Exception e) {
        log.warn("Tumblr API недоступен, используем fallback для тега {}", tag);
        return Collections.emptyList();
    }
}
