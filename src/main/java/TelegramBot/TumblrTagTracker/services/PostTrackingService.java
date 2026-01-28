package TelegramBot.TumblrTagTracker.services;

import TelegramBot.TumblrTagTracker.dto.TumblrPostDTO;
import TelegramBot.TumblrTagTracker.model.TrackedPost;
import TelegramBot.TumblrTagTracker.repositories.TrackedPostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional
public class PostTrackingService {

    private static final Logger log = LoggerFactory.getLogger(PostTrackingService.class);

    @Value("${tumblr.filter.minimum.notes:5}")
    private long minimumNotes;

    @Value("${tumblr.filter.minimum.age.hours:0}")
    private int minimumAgeHours;

    @Value("${tumblr.filter.recheck.interval.hours:6}")
    private int recheckIntervalHours;

    @Value("${tumblr.filter.cleanup.days:7}")
    private int cleanupAfterDays;

    private final TrackedPostRepository trackedPostRepository;

    @Autowired
    public PostTrackingService(TrackedPostRepository trackedPostRepository) {
        this.trackedPostRepository = trackedPostRepository;
    }

    /**
     * Проверяет, должен ли пост быть отправлен немедленно
     * или нужно подождать, пока он наберет популярность
     */
    public boolean shouldSendPostNow(TumblrPostDTO post) {

        // Проверяем, есть ли пост в БД
        Optional<TrackedPost> trackedOpt = trackedPostRepository.findByPostId(post.getId());

        if (trackedOpt.isPresent()) {
            TrackedPost tracked = trackedOpt.get();
            trackedPostRepository.save(tracked);

            // Проверяем условия отправки
            boolean meetsThreshold = tracked.meetsMinimumThreshold(minimumNotes);
            boolean oldEnough = tracked.isOldEnough(minimumAgeHours);

            if (meetsThreshold && oldEnough) {
                log.debug("Пост {} прошел фильтр: {} заметок (мин: {})",
                        post.getId(), tracked.getNoteCount(), minimumNotes);
                return true;
            } else {
                log.debug("Пост {} не прошел фильтр: {} заметок (мин: {}), возраст: {} часов",
                        post.getId(), tracked.getNoteCount(), minimumNotes,
                        tracked.getPostCreatedAt() != null ?
                                Duration.between(tracked.getPostCreatedAt(), LocalDateTime.now()).toHours() : "неизвестно");
                return false;
            }
        } else {
            // Первый раз видим этот пост - сохраняем для отслеживания
            TrackedPost newTracked = createTrackedPost(post);
            trackedPostRepository.save(newTracked);

            // Проверяем, можем ли отправить сразу
            boolean canSendNow = newTracked.meetsMinimumThreshold(minimumNotes) &&
                    newTracked.isOldEnough(minimumAgeHours);

            if (canSendNow) {
                log.debug("Новый пост {} сразу прошел фильтр: {} заметок",
                        post.getId(), newTracked.getNoteCount());
                return true;
            } else {
                log.debug("Новый пост {} добавлен для отслеживания: {} заметок (мин: {})",
                        post.getId(), newTracked.getNoteCount(), minimumNotes);
                return false;
            }
        }
    }

    public void markPostAsSent(String postId) {
        trackedPostRepository.findByPostId(postId).ifPresent(tracked -> {
            tracked.markAsSent();
            trackedPostRepository.save(tracked);
            log.debug("Пост {} помечен как отправленный", postId);
        });
    }

    /**
     * Находит посты, которые теперь готовы к отправке
     * (набрали достаточно лайков с момента последней проверки)
     */
    public List<TrackedPost> findPostsReadyToSend() {
        List<TrackedPost> candidates = trackedPostRepository
                .findUnsentPostsWithMinimumNotes(minimumNotes);

        // Дополнительно фильтруем по возрасту
        return candidates.stream()
                .filter(post -> post.isOldEnough(minimumAgeHours))
                .collect(Collectors.toList());
    }

    /**
     * Находит посты для повторной проверки метрик
     */
    public List<TrackedPost> findPostsForRecheck() {
        LocalDateTime checkBefore = LocalDateTime.now().minusHours(recheckIntervalHours);
        return trackedPostRepository.findPostsForRecheck(checkBefore);
    }

    /**
     * Очищает старые отправленные посты
     */
    public void cleanupOldPosts() {
        LocalDateTime olderThan = LocalDateTime.now().minusDays(cleanupAfterDays);
        List<TrackedPost> oldPosts = trackedPostRepository.findOldSentPosts(olderThan);

        if (!oldPosts.isEmpty()) {
            trackedPostRepository.deleteAll(oldPosts);
            log.info("Удалено {} старых отправленных постов", oldPosts.size());
        }
    }


    private TrackedPost createTrackedPost(TumblrPostDTO post) {
        TrackedPost tracked = new TrackedPost(post.getId());
        tracked.setBlogName(post.getBlogName());
        tracked.setPostUrl(post.getPostURL());

        if (post.getTimestamp() != null) {
            tracked.setPostCreatedAt(
                    LocalDateTime.ofEpochSecond(post.getTimestamp(), 0,
                            java.time.ZoneOffset.UTC)
            );
        }

        if (post.getTags() != null) {
            tracked.setTags(String.join(",", post.getTags()));
        }

        return tracked;
    }
}
