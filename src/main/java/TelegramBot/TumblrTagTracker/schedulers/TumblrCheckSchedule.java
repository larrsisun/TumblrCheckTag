package TelegramBot.TumblrTagTracker.schedulers;

import TelegramBot.TumblrTagTracker.dto.TumblrPostDTO;
import TelegramBot.TumblrTagTracker.models.Subscription;
import TelegramBot.TumblrTagTracker.models.TrackedPost;
import TelegramBot.TumblrTagTracker.services.NotificationService;
import TelegramBot.TumblrTagTracker.services.PostTrackingService;
import TelegramBot.TumblrTagTracker.services.SubscriptionService;
import TelegramBot.TumblrTagTracker.services.TumblrService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@EnableScheduling
public class TumblrCheckSchedule {

    private static final Logger log = LoggerFactory.getLogger(TumblrCheckSchedule.class);

    @Value("${notification.delay.between.posts.ms}") // 1 минута
    private long delayBetweenPosts;
    @Value("${tumblr.check.interval.ms}") // 10 минут (application properties)
    private long checkIntervalMs;
    @Value("${notification.delay.between.users.ms}") // 1 секунда между пользователями
    private long delayBetweenUsers;

    private final SubscriptionService subscriptionService;
    private final TumblrService tumblrService;
    private final NotificationService notificationService;
    private final PostTrackingService postTrackingService;

    @Autowired
    public TumblrCheckSchedule(SubscriptionService subscriptionService, TumblrService tumblrService, NotificationService notificationService, PostTrackingService postTrackingService) {
        this.subscriptionService = subscriptionService;
        this.tumblrService = tumblrService;
        this.notificationService = notificationService;
        this.postTrackingService = postTrackingService;
    }

    @Scheduled(fixedDelay = 600000)
    public void checkForNewPosts() {
        try {
            List<Subscription> activeSubscriptions = subscriptionService.getAllActiveSubscriptions();

            if (activeSubscriptions.isEmpty()) {
                log.info("Нет активных подписчиков, проверка пропущена");
                return;
            }

            log.info("Найдено {} активных подписчиков", activeSubscriptions.size());
            activeSubscriptions.forEach(this::processSubscriptionAsync
            );

        } catch (Exception e) {
            log.error("Ошибка при проверке постов.", e);
        }
    }

    @Async("tumblrApiExecutor")
    protected void processSubscriptionAsync(Subscription subscription) {
        try {
            Set<String> userTags = subscription.getTags();
            if (userTags == null || userTags.isEmpty()) {
                log.debug("Пользователь {} не имеет тегов, пропускаем", subscription.getChatID());
                return;
            }

            log.debug("Проверка постов для пользователя {} по тегам: {}", subscription.getChatID(), userTags);

            // Получаем новые посты по тегам пользователя
            List<TumblrPostDTO> newPosts = tumblrService.getNewPostsByTags(userTags);

            if (newPosts.isEmpty()) {
                log.debug("Новых постов для пользователя {} не найдено", subscription.getChatID());
                return;
            }

            log.info("Найдено {} новых постов для пользователя {}", newPosts.size(), subscription.getChatID());
            sendPostsWithDelay(subscription.getChatID(), newPosts);

        } catch (Exception e) {
            log.error("Ошибка при попытке обработать подписку для пользователя {}", subscription.getChatID());
        }
    }

    private void sendPostsWithDelay(Long chatId, List<TumblrPostDTO> posts) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (TumblrPostDTO post : posts) {
            chain = chain.thenCompose(v -> notificationService.sendPostToUserAsync(chatId, post).thenApply(sent -> {
                                if (sent) {
                                    log.debug("Post {} sent to user {}", post.getId(), chatId);
                                }
                                return null;
                            })
            ).thenCompose(v -> delay(delayBetweenPosts));
        }

        // Handle any errors in the chain
        chain.exceptionally(ex -> {
            log.error("Error in notification chain for user {}", chatId, ex);
            return null;
        });
    }

    @Async("notificationExecutor")
    protected void processDelayedPostAsync(TrackedPost trackedPost,
                                           List<Subscription> activeSubscriptions) {
        try {
            Set<String> postTags = trackedPost.getTags() != null
                    ? Set.of(trackedPost.getTags().split(","))
                    : Set.of();

            // Find users with matching tags
            List<Subscription> matchingUsers = activeSubscriptions.stream()
                    .filter(sub -> sub.getTags() != null && !sub.getTags().isEmpty())
                    .filter(sub -> hasCommonTags(sub.getTags(), postTags))
                    .toList();

            if (matchingUsers.isEmpty()) {
                log.debug("No matching users for delayed post {}", trackedPost.getPostId());
                return;
            }

            log.info("Sending delayed post {} to {} users",
                    trackedPost.getPostId(), matchingUsers.size());

            TumblrPostDTO postDTO = createDTOFromTrackedPost(trackedPost);

            // Send to all matching users with delays
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

            for (Subscription user : matchingUsers) {
                chain = chain.thenCompose(v ->
                        notificationService.sendPostToUserAsync(user.getChatID(), postDTO)
                ).thenCompose(v -> delay(delayBetweenUsers));
            }

            // Mark as sent after all notifications complete
            chain.thenRun(() -> {
                postTrackingService.markPostAsSent(trackedPost.getPostId());
                log.info("Delayed post {} marked as sent", trackedPost.getPostId());
            }).exceptionally(ex -> {
                log.error("Error sending delayed post {}", trackedPost.getPostId(), ex);
                return null;
            });

        } catch (Exception e) {
            log.error("Error processing delayed post {}", trackedPost.getPostId(), e);
        }
    }

    /**
     * Cleans up old tracked posts.
     * Runs daily at 3 AM.
     */
    @Scheduled(cron = "${tumblr.cleanup.cron:0 0 3 * * ?}")
    public void cleanupOldPosts() {
        try {
            log.info("Starting cleanup of old tracked posts");
            postTrackingService.cleanUpOldPosts();
            log.info("Cleanup completed");
        } catch (Exception e) {
            log.error("Error during cleanup", e);
        }
    }

    @Scheduled(fixedDelay = 1800000) // 30 minutes
    public void checkDelayedPosts() {
        try {
            log.info("Checking delayed posts");
            List<TrackedPost> readyPosts = postTrackingService.findPostsReadyToSend();

            if (readyPosts.isEmpty()) {
                log.info("No delayed posts ready to send");
                return;
            }

            log.info("Found {} delayed posts ready to send", readyPosts.size());

            // Get all active subscriptions once
            List<Subscription> activeSubscriptions = subscriptionService.getAllActiveSubscriptions();

            // Process each ready post
            readyPosts.forEach(trackedPost ->
                    processDelayedPostAsync(trackedPost, activeSubscriptions)
            );

        } catch (Exception e) {
            log.error("Error checking delayed posts", e);
        }
    }

    private CompletableFuture<Void> delay(long millis) {
        return CompletableFuture.runAsync(() -> {},
                CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS));
    }

    private boolean hasCommonTags(Set<String> userTags, Set<String> postTags) {
        if (userTags == null || postTags == null) {
            return false;
        }
        return userTags.stream().anyMatch(postTags::contains);
    }

    private TumblrPostDTO createDTOFromTrackedPost(TrackedPost tracked) {
        TumblrPostDTO dto = new TumblrPostDTO();
        dto.setId(tracked.getPostId());
        dto.setBlogName(tracked.getBlogName());
        dto.setPostURL(tracked.getPostUrl());

        if (tracked.getTags() != null) {
            dto.setTags(Arrays.asList(tracked.getTags().split(",")));
        }

        return dto;
    }
}
