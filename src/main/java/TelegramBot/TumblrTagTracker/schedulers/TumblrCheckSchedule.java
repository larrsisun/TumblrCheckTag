package TelegramBot.TumblrTagTracker.schedulers;

import TelegramBot.TumblrTagTracker.dto.TumblrPostDTO;
import TelegramBot.TumblrTagTracker.models.Subscription;
import TelegramBot.TumblrTagTracker.models.TrackedPost;
import TelegramBot.TumblrTagTracker.services.*;
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
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final RedisCacheService redisCacheService;

    @Autowired
    public TumblrCheckSchedule(SubscriptionService subscriptionService,
                               TumblrService tumblrService,
                               NotificationService notificationService,
                               PostTrackingService postTrackingService,
                               RedisCacheService redisCacheService) {
        this.subscriptionService = subscriptionService;
        this.tumblrService = tumblrService;
        this.notificationService = notificationService;
        this.postTrackingService = postTrackingService;
        this.redisCacheService = redisCacheService;
    }

    @Scheduled(fixedDelay = 60000)
    public void checkForNewPosts() {
        try {
            List<Subscription> activeSubscriptions = subscriptionService.getAllActiveSubscriptions();

            if (activeSubscriptions.isEmpty()) {
                log.info("Нет активных подписчиков, проверка пропущена");
                return;
            }

            log.info("Найдено {} активных подписчиков", activeSubscriptions.size());

            // Обработка с ограниченным параллелизмом
            processSubscriptionsWithLimitedParallelism(activeSubscriptions);

        } catch (Exception e) {
            log.error("Ошибка при проверке постов.", e);
        }
    }

    private void processSubscriptionsWithLimitedParallelism(List<Subscription> subscriptions) {
        // Используем Semaphore для ограничения одновременных запросов к Tumblr API
        Semaphore semaphore = new Semaphore(3); // максимум 3 параллельных запроса

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Subscription subscription : subscriptions) {
            futures.add(
                    CompletableFuture.runAsync(() -> {
                        try {
                            semaphore.acquire();
                            processSingleSubscription(subscription);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            semaphore.release();
                        }
                    }, Executors.newFixedThreadPool(5))
            );
        }

        // Ждем завершения всех
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .exceptionally(ex -> {
                    log.error("Ошибка при обработке подписок", ex);
                    return null;
                })
                .join();
    }

    private void processSingleSubscription(Subscription subscription) {
        Set<String> userTags = subscription.getTags();
        if (userTags == null || userTags.isEmpty()) {
            return;
        }

        try {
            List<TumblrPostDTO> newPosts = tumblrService.getNewPostsByTags(userTags);

            if (newPosts.isEmpty()) {
                return;
            }

            log.info("Найдено {} постов для пользователя {}", newPosts.size(), subscription.getChatID());

            // Последовательная отправка постов ОДНОМУ пользователю
            sendPostsToOneUserSequentially(subscription.getChatID(), newPosts);

        } catch (Exception e) {
            log.error("Ошибка для пользователя {}", subscription.getChatID(), e);
        }
    }

    private void sendPostsToOneUserSequentially(Long chatId, List<TumblrPostDTO> posts) {
        AtomicInteger sentCount = new AtomicInteger(0);

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (TumblrPostDTO post : posts) {
            chain = chain
                    .thenCompose(v -> notificationService.sendPostToUserAsync(chatId, post))
                    .thenApply(success -> {
                        if (success) {
                            sentCount.incrementAndGet();
                            // ИСПРАВЛЕНИЕ: Помечаем пост в PostTrackingService
                            postTrackingService.markPostAsSent(post.getId());
                        }
                        return null;
                    })
                    .thenCompose(v -> delay(delayBetweenPosts)); // Задержка между постами
        }

        chain.thenRun(() -> {
            log.info("Пользователю {} отправлено {} постов", chatId, sentCount.get());
        }).exceptionally(ex -> {
            log.error("Ошибка отправки пользователю {}", chatId, ex);
            return null;
        }).join(); // Ждем завершения отправки этому пользователю
    }

    private void sendAllPostsSequentially(List<UserPostsPair> allUserPosts) {
        AtomicInteger totalSent = new AtomicInteger(0);
        AtomicInteger totalFailed = new AtomicInteger(0);

        log.info("Начинаем отправку постов {} пользователям", allUserPosts.size());

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (UserPostsPair pair : allUserPosts) {
            for (TumblrPostDTO post : pair.getPosts()) {
                chain = chain
                        .thenCompose(v -> notificationService.sendPostToUserAsync(pair.getChatId(), post))
                        .thenApply(sent -> {
                            if (sent) {
                                totalSent.incrementAndGet();
                                // ИСПРАВЛЕНИЕ: Помечаем пост как отправленный в Redis
                                redisCacheService.markAsSentIfNotSent(post.getId());
                                log.debug("Пост {} помечен как отправленный в Redis", post.getId());
                            } else {
                                totalFailed.incrementAndGet();
                            }
                            return null;
                        })
                        .thenCompose(v -> delay(delayBetweenPosts));
            }
            chain = chain.thenCompose(v -> delay(delayBetweenUsers));
        }

        chain.thenRun(() -> {
            log.info("Отправка завершена. Успешно: {}, Ошибок: {}",
                    totalSent.get(), totalFailed.get());
        }).exceptionally(ex -> {
            log.error("Ошибка в процессе отправки постов", ex);
            return null;
        }).join();
    }

    @Scheduled(fixedDelay = 1800000)
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
            processDelayedPostsSequentially(readyPosts, activeSubscriptions);

        } catch (Exception e) {
            log.error("Error checking delayed posts", e);
        }
    }

    private void processDelayedPostsSequentially(List<TrackedPost> readyPosts,
                                                 List<Subscription> activeSubscriptions) {
        List<UserPostsPair> allUserPosts = new ArrayList<>();

        for (TrackedPost trackedPost : readyPosts) {
            // Проверяем, не был ли пост уже отправлен (через Redis)
            if (redisCacheService.wasSent(trackedPost.getPostId())) {
                log.debug("Пост {} уже был отправлен ранее, пропускаем", trackedPost.getPostId());
                continue;
            }

            Set<String> postTags = trackedPost.getTags() != null
                    ? Set.of(trackedPost.getTags().split(","))
                    : Set.of();

            log.info("Проверка отложенного поста {} с тегами: {}", trackedPost.getPostId(), postTags);

            // ИСПРАВЛЕНИЕ: Для каждого пользователя получаем АКТУАЛЬНЫЕ теги из БД
            for (Subscription subscription : activeSubscriptions) {
                Long userId = subscription.getChatID();

                // Получаем свежие теги пользователя НАПРЯМУЮ из БД (с обновлением)
                Set<String> currentUserTags = subscriptionService.getTags(userId);

                log.info("Пользователь {}: текущие теги = {}, теги поста = {}",
                        userId, currentUserTags, postTags);

                // Проверяем, есть ли у пользователя СЕЙЧАС теги, совпадающие с постом
                if (currentUserTags != null && !currentUserTags.isEmpty()
                        && hasCommonTags(currentUserTags, postTags)) {
                    TumblrPostDTO postDTO = createDTOFromTrackedPost(trackedPost);
                    allUserPosts.add(new UserPostsPair(userId, List.of(postDTO)));
                    log.info("✓ Пост {} БУДЕТ отправлен пользователю {} (теги совпадают: {})",
                            trackedPost.getPostId(), userId,
                            currentUserTags.stream().filter(postTags::contains).toList());
                } else {
                    if (currentUserTags == null || currentUserTags.isEmpty()) {
                        log.info("✗ Пост {} НЕ будет отправлен пользователю {} (нет тегов)",
                                trackedPost.getPostId(), userId);
                    } else {
                        log.info("✗ Пост {} НЕ будет отправлен пользователю {} (теги не совпадают. Теги пользователя: {}, теги поста: {})",
                                trackedPost.getPostId(), userId, currentUserTags, postTags);
                    }
                }
            }
        }

        if (!allUserPosts.isEmpty()) {
            log.info("Отправляем {} отложенных постов", allUserPosts.size());
            sendAllPostsSequentially(allUserPosts);

            // ИСПРАВЛЕНИЕ: Помечаем посты как отправленные ПОСЛЕ успешной отправки
            readyPosts.forEach(post -> {
                postTrackingService.markPostAsSent(post.getPostId());
                log.debug("Отложенный пост {} помечен как отправленный в БД", post.getPostId());
            });
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
        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
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

    private static class UserPostsPair {
        private final Long chatId;
        private final List<TumblrPostDTO> posts;

        public UserPostsPair(Long chatId, List<TumblrPostDTO> posts) {
            this.chatId = chatId;
            this.posts = posts;
        }

        public Long getChatId() { return chatId; }
        public List<TumblrPostDTO> getPosts() { return posts; }
    }
}
