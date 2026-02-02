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
            activeSubscriptions.forEach(this::processSubscriptionAsync);

        } catch (Exception e) {
            log.error("Ошибка при проверке постов.", e);
        }
    }

    @Scheduled(fixedDelay = 1800000) // 30 minutes
    public void checkDelayedPosts() {
        try {
            log.info("Проверка отложенных постов.");
            List<TrackedPost> readyPosts = postTrackingService.findPostsReadyToSend();

            if (readyPosts.isEmpty()) {
                log.info("Отложенных постов нет.");
                return;
            }

            log.info("Найдено {} постов, готовых к отправке.", readyPosts.size());

            List<Subscription> activeSubscriptions = subscriptionService.getAllActiveSubscriptions();

            readyPosts.forEach(trackedPost ->
                    processDelayedPostAsync(trackedPost, activeSubscriptions)
            );

        } catch (Exception e) {
            log.error("Error checking delayed posts", e);
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
            chain = chain.thenCompose(v -> notificationService.sendPostToUserAsync(chatId, post)
                    .thenApply(sent -> {
                        if (sent) {
                            log.debug("Пост {} отправлен пользователю {}", post.getId(), chatId);
                        }
                        return null;})
            ).thenCompose(v -> delay(delayBetweenPosts));
        }

        chain.exceptionally(ex -> {
            log.error("Ошибка в оповещениях для пользователя {}", chatId, ex);
            return null;
        });
    }

    @Async("notificationExecutor")
    protected void processDelayedPostAsync(TrackedPost trackedPost, List<Subscription> activeSubscriptions) {
        try {
            Set<String> postTags = trackedPost.getTags() != null ? Set.of(trackedPost.getTags().split(",")) : Set.of();

            // найти подписчиков с одинаковыми тегами
            List<Subscription> matchingUsers = activeSubscriptions.stream()
                    .filter(sub -> sub.getTags() != null && !sub.getTags().isEmpty())
                    .filter(sub -> hasCommonTags(sub.getTags(), postTags))
                    .toList();

            if (matchingUsers.isEmpty()) {
                log.debug("Нет пересекающихся пользователей для поста {}", trackedPost.getPostId());
                return;
            }

            TumblrPostDTO postDTO = createDTOFromTrackedPost(trackedPost);
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

            for (Subscription user : matchingUsers) {
                chain = chain.thenCompose(v -> notificationService.sendPostToUserAsync(user.getChatID(), postDTO))
                        .thenCompose(v -> delay(delayBetweenUsers));
            }

            chain.thenRun(() -> {
                postTrackingService.markPostAsSent(trackedPost.getPostId());
                log.info("Отложенный пост {} помечен как отправленный", trackedPost.getPostId());
            }).exceptionally(ex -> {
                log.error("Ошибка при попытке отправить отложенный пост {}", trackedPost.getPostId(), ex);
                return null;
            });

        } catch (Exception e) {
            log.error("Ошибка в обработке отложенного поста {}", trackedPost.getPostId(), e);
        }
    }


    @Scheduled(cron = "${tumblr.cleanup.cron:0 0 3 * * ?}")
    public void cleanupOldPosts() {
        try {
            log.info("Начало очистки старых постов");
            postTrackingService.cleanUpOldPosts();
            log.info("Очистка завершена");
        } catch (Exception e) {
            log.error("Ошибка в процессе очистки", e);
        }
    }

    private CompletableFuture<Void> delay(long millis) {
        return CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS));
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
