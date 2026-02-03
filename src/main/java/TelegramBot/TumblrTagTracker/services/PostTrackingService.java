package TelegramBot.TumblrTagTracker.services;

import TelegramBot.TumblrTagTracker.dto.TumblrPostDTO;
import TelegramBot.TumblrTagTracker.models.TrackedPost;
import TelegramBot.TumblrTagTracker.repositories.TrackedPostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional
public class PostTrackingService {

    private static final Logger log = LoggerFactory.getLogger(PostTrackingService.class);

    @Value("${tumblr.filter.minimum.notes:5}")
    private int minimumNotes;

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

    // Проверка, нужно ли отправить пост сейчас (глобальные фильтры)
    public boolean shouldSendPostNow(TumblrPostDTO post) {

        Optional<TrackedPost> trackedPost = trackedPostRepository.findByPostId(post.getId());

        if (trackedPost.isPresent()) {
            TrackedPost tracked = trackedPost.get();

            if (post.getNoteCount() != null) {
                tracked.setNoteCount(Integer.parseInt(post.getNoteCount()));
            }
            tracked.setLastCheckedAt(LocalDateTime.now());
            trackedPostRepository.save(tracked);

            // Проверяем условия отправки
            boolean meetsThreshold = tracked.meetsMinimumThreshold(minimumNotes);
            boolean oldEnough = tracked.isOldEnough(minimumAgeHours);

            if (meetsThreshold && oldEnough) {
                log.debug("Пост {} сразу прошел фильтры.", post.getId());
                return true;
            } else {
                log.debug("Пост {} не прошел фильтры (noteCount: {}, age: {}h).",
                        post.getId(), tracked.getNoteCount(),
                        tracked.getPostCreatedAt() != null ?
                                java.time.Duration.between(tracked.getPostCreatedAt(), LocalDateTime.now()).toHours() : "unknown");
                return false;
            }
        } else {
            // Первый раз видим этот пост - сохраняем для отслеживания
            TrackedPost newTracked = createTrackedPost(post);
            if (post.getNoteCount() != null) {
                newTracked.setNoteCount(Integer.parseInt(post.getNoteCount()));
            }
            trackedPostRepository.save(newTracked);

            // Проверяем, можем ли отправить сразу
            boolean canSendNow = newTracked.meetsMinimumThreshold(minimumNotes) && newTracked.isOldEnough(minimumAgeHours);

            if (canSendNow) {
                log.debug("Новый пост {} сразу прошел фильтр.", post.getId());
                return true;
            } else {
                log.debug("Новый пост {} добавлен для отслеживания (noteCount: {}).",
                        post.getId(), newTracked.getNoteCount());
                return false;
            }
        }
    }

    public void markPostAsSent(String postId) {
        trackedPostRepository.findByPostId(postId).ifPresent(tracked -> {
            tracked.markAsSent();
            trackedPostRepository.save(tracked);
            log.debug("Пост {} помечен как отправленный (глобально)", postId);
        });
    }

    // Находит посты, которые теперь готовы к отправке
    public List<TrackedPost> findPostsReadyToSend() {
        List<TrackedPost> candidates = trackedPostRepository.findUnsentPostsWithMinimumNotes(minimumNotes);

        // Дополнительно фильтруем по возрасту
        return candidates.stream()
                .filter(post -> post.isOldEnough(minimumAgeHours))
                .collect(Collectors.toList());
    }

    // Находит посты для повторной проверки метрик
    public List<TrackedPost> findPostsForRecheck() {
        LocalDateTime checkBefore = LocalDateTime.now().minusHours(recheckIntervalHours);
        return trackedPostRepository.findPostsForRecheck(checkBefore);
    }

    // Очищает старые отправленные посты
    public void cleanUpOldPosts() {
        LocalDateTime olderThan = LocalDateTime.now().minusDays(cleanupAfterDays);
        List<TrackedPost> oldPosts = trackedPostRepository.findOldSentPosts(olderThan);

        if (!oldPosts.isEmpty()) {
            trackedPostRepository.deleteAll(oldPosts);
            log.info("Удалено {} старых отправленных постов", oldPosts.size());
        }
    }

    /**
     * Обновляет метрики поста (количество заметок)
     */
    public void updatePostMetrics(String postId, int noteCount) {
        if (postId == null) {
            log.warn("Попытка обновить метрики для null postId");
            return;
        }

        trackedPostRepository.findByPostId(postId).ifPresent(tracked -> {
            int oldCount = tracked.getNoteCount() != null ? tracked.getNoteCount() : 0;

            if (noteCount != oldCount) {
                tracked.setNoteCount(noteCount);
                tracked.setLastCheckedAt(LocalDateTime.now());
                trackedPostRepository.save(tracked);

                log.debug("Обновлены метрики поста {}: {} -> {} заметок",
                        postId, oldCount, noteCount);
            }
        });
    }

    // Создаём пост для отслеживания
    private TrackedPost createTrackedPost(TumblrPostDTO post) {
        TrackedPost tracked = new TrackedPost(post.getId());
        tracked.setBlogName(post.getBlogName());
        tracked.setPostUrl(post.getPostURL());

        if (post.getTimestamp() != null) {
            tracked.setPostCreatedAt(LocalDateTime.ofEpochSecond(post.getTimestamp(), 0, ZoneOffset.UTC));
        }

        if (post.getTags() != null && !post.getTags().isEmpty()) {
            tracked.setTags(String.join(",", post.getTags()));
        }
        return tracked;
    }
}
