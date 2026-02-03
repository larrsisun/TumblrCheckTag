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

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Component
@EnableScheduling
public class TumblrCheckSchedule {

    private static final Logger log = LoggerFactory.getLogger(TumblrCheckSchedule.class);

    @Value("${notification.delay.between.posts.ms:60000}") // 1 минута
    private long delayBetweenPosts;

    @Value("${notification.delay.between.users.ms:1000}") // 1 секунда между пользователями
    private long delayBetweenUsers;

    private final SubscriptionService subscriptionService;
    private final TumblrService tumblrService;
    private final NotificationService notificationService;
    private final PostTrackingService postTrackingService;
    private final UserPostTrackingService userPostTrackingService;

    @Autowired
    public TumblrCheckSchedule(SubscriptionService subscriptionService,
                               TumblrService tumblrService,
                               NotificationService notificationService,
                               PostTrackingService postTrackingService,
                               UserPostTrackingService userPostTrackingService) {
        this.subscriptionService = subscriptionService;
        this.tumblrService = tumblrService;
        this.notificationService = notificationService;
        this.postTrackingService = postTrackingService;
        this.userPostTrackingService = userPostTrackingService;
    }

    /**
     * Проверка новых постов каждые 10 минут
     */
    @Scheduled(fixedDelay = 60000) // 10 минут
    public void checkForNewPosts() {
        try {
            List<Subscription> activeSubscriptions = subscriptionService.getAllActiveSubscriptions();

            if (activeSubscriptions.isEmpty()) {
                log.info("Нет активных подписчиков");
                return;
            }

            log.info("Найдено {} активных подписчиков", activeSubscriptions.size());

            // Собираем все уникальные теги от всех пользователей
            Set<String> allTags = activeSubscriptions.stream()
                    .map(Subscription::getTags)
                    .filter(tags -> tags != null && !tags.isEmpty())
                    .flatMap(Set::stream)
                    .collect(Collectors.toSet());

            if (allTags.isEmpty()) {
                log.info("Нет тегов для проверки");
                return;
            }

            log.info("Собрано {} уникальных тегов для проверки", allTags.size());

            // Получаем новые посты по всем тегам
            List<TumblrPostDTO> newPosts = tumblrService.getNewPostsByTags(allTags);

            if (newPosts.isEmpty()) {
                log.info("Новых постов не найдено");
                return;
            }

            log.info("Найдено {} новых постов", newPosts.size());

            // Для каждого поста определяем, кому его отправить
            Map<Long, List<TumblrPostDTO>> postsPerUser = new HashMap<>();

            for (TumblrPostDTO post : newPosts) {
                log.info("=== Обработка поста {} с тегами: {} ===", post.getId(), post.getTags());

                for (Subscription subscription : activeSubscriptions) {
                    Long userId = subscription.getChatID();
                    Set<String> userTags = subscription.getTags();

                    log.info("Проверка пользователя {} с тегами: {}", userId, userTags);

                    if (userPostTrackingService.shouldSendToUser(userId, post, userTags)) {
                        postsPerUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(post);
                        log.info("✓ Пост {} ДОБАВЛЕН для пользователя {}", post.getId(), userId);
                    } else {
                        log.warn("✗ Пост {} НЕ добавлен для пользователя {}", post.getId(), userId);
                    }
                }
            }

// После цикла
            log.info("=== Итого: постов для отправки ===");
            for (Map.Entry<Long, List<TumblrPostDTO>> entry : postsPerUser.entrySet()) {
                log.info("Пользователь {}: {} постов", entry.getKey(), entry.getValue().size());
                for (TumblrPostDTO post : entry.getValue()) {
                    log.info("  - Пост {}", post.getId());
                }
            }

            if (postsPerUser.isEmpty()) {
                log.info("Нет постов для отправки пользователям (не прошли фильтры или уже отправлены)");
                return;
            }

            log.info("Подготовлено постов для отправки: {} пользователям", postsPerUser.size());

            // Отправляем посты пользователям последовательно
            sendPostsToUsersSequentially(postsPerUser);

        } catch (Exception e) {
            log.error("Ошибка при проверке новых постов", e);
        }
    }

    /**
     * Проверка отложенных постов каждые 30 минут
     */
    @Scheduled(fixedDelay = 1800000) // 30 минут
    public void checkDelayedPosts() {
        try {
            log.info("Проверка отложенных постов");

            // Получаем посты, которые теперь готовы к отправке
            List<TrackedPost> readyPosts = postTrackingService.findPostsReadyToSend();

            if (readyPosts.isEmpty()) {
                log.info("Отложенных постов нет");
                return;
            }

            log.info("Найдено {} постов, готовых к отправке", readyPosts.size());

            // Получаем всех активных подписчиков
            List<Subscription> activeSubscriptions = subscriptionService.getAllActiveSubscriptions();

            if (activeSubscriptions.isEmpty()) {
                log.info("Нет активных подписчиков для отложенных постов");
                return;
            }

            // Для каждого поста определяем, кому его нужно отправить
            Map<Long, List<TumblrPostDTO>> postsPerUser = new HashMap<>();

            for (TrackedPost trackedPost : readyPosts) {
                // Получаем теги поста
                Set<String> postTags = trackedPost.getTags() != null
                        ? Arrays.stream(trackedPost.getTags().split(","))
                        .map(String::trim)
                        .filter(tag -> !tag.isEmpty())
                        .collect(Collectors.toSet())
                        : Collections.emptySet();

                if (postTags.isEmpty()) {
                    log.warn("Пост {} не имеет тегов, пропускаем", trackedPost.getPostId());
                    continue;
                }

                log.debug("Проверка отложенного поста {} с тегами: {}",
                        trackedPost.getPostId(), postTags);

                // Проверяем каждого подписчика
                for (Subscription subscription : activeSubscriptions) {
                    Long userId = subscription.getChatID();

                    // Получаем АКТУАЛЬНЫЕ теги пользователя из БД
                    Set<String> userTags = subscriptionService.getTags(userId);

                    if (userTags == null || userTags.isEmpty()) {
                        continue;
                    }

                    // Проверяем, подходит ли пост этому пользователю
                    TumblrPostDTO postDTO = createDTOFromTrackedPost(trackedPost);

                    if (userPostTrackingService.shouldSendToUser(userId, postDTO, userTags)) {
                        postsPerUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(postDTO);

                        Set<String> matchedTags = userTags.stream()
                                .filter(postTags::contains)
                                .collect(Collectors.toSet());

                        log.info("✓ Отложенный пост {} будет отправлен пользователю {} " +
                                        "(совпадающие теги: {})",
                                trackedPost.getPostId(), userId, matchedTags);
                    } else {
                        log.debug("✗ Отложенный пост {} НЕ будет отправлен пользователю {} " +
                                        "(либо уже отправлен, либо теги не совпадают)",
                                trackedPost.getPostId(), userId);
                    }
                }
            }

            if (!postsPerUser.isEmpty()) {
                int totalPosts = postsPerUser.values().stream().mapToInt(List::size).sum();
                log.info("Отправляем {} отложенных постов {} пользователям",
                        totalPosts, postsPerUser.size());

                sendPostsToUsersSequentially(postsPerUser);
            } else {
                log.info("Нет пользователей для отправки отложенных постов");
            }

        } catch (Exception e) {
            log.error("Ошибка при проверке отложенных постов", e);
        }
    }

    /**
     * Повторная проверка метрик отложенных постов каждый час
     */
    @Scheduled(fixedDelay = 3600000) // 1 час
    public void recheckPostMetrics() {
        try {
            log.info("Повторная проверка метрик отложенных постов");

            // Находим посты, которые нужно перепроверить
            List<TrackedPost> postsToRecheck = postTrackingService.findPostsForRecheck();

            if (postsToRecheck.isEmpty()) {
                log.info("Нет постов для повторной проверки метрик");
                return;
            }

            log.info("Найдено {} постов для повторной проверки", postsToRecheck.size());

            // Собираем все уникальные теги из постов
            Set<String> tagsToCheck = postsToRecheck.stream()
                    .map(TrackedPost::getTags)
                    .filter(tags -> tags != null && !tags.isEmpty())
                    .flatMap(tags -> Arrays.stream(tags.split(",")))
                    .map(String::trim)
                    .filter(tag -> !tag.isEmpty())
                    .collect(Collectors.toSet());

            if (tagsToCheck.isEmpty()) {
                log.warn("Нет тегов для проверки метрик");
                return;
            }

            log.info("Проверяем {} уникальных тегов", tagsToCheck.size());

            // Получаем свежие данные от Tumblr API
            List<TumblrPostDTO> freshPosts = tumblrService.getNewPostsByTags(tagsToCheck);

            if (freshPosts.isEmpty()) {
                log.info("Не получено свежих данных от Tumblr API");
                return;
            }

            // Создаем мапу для быстрого доступа
            Map<String, TumblrPostDTO> freshPostsMap = freshPosts.stream()
                    .collect(Collectors.toMap(TumblrPostDTO::getId, p -> p, (p1, p2) -> p1));

            int updatedCount = 0;

            // Обновляем метрики
            for (TrackedPost tracked : postsToRecheck) {
                TumblrPostDTO freshData = freshPostsMap.get(tracked.getPostId());

                if (freshData != null && freshData.getNoteCount() != null) {
                    try {
                        int newCount = Integer.parseInt(freshData.getNoteCount());
                        postTrackingService.updatePostMetrics(tracked.getPostId(), newCount);
                        updatedCount++;
                    } catch (NumberFormatException e) {
                        log.warn("Некорректное значение noteCount для поста {}: {}",
                                tracked.getPostId(), freshData.getNoteCount());
                    }
                }
            }

            log.info("Обновлено метрик у {} постов", updatedCount);

        } catch (Exception e) {
            log.error("Ошибка при повторной проверке метрик", e);
        }
    }

    /**
     * Очистка старых постов раз в день в 3:00
     */
    @Scheduled(cron = "${tumblr.cleanup.cron:0 0 3 * * ?}")
    public void cleanupOldPosts() {
        try {
            log.info("Начало очистки старых данных");

            // Очистка TrackedPost
            postTrackingService.cleanUpOldPosts();

            // Очистка UserPostDelivery (7 дней)
            userPostTrackingService.cleanupOldDeliveries(7);

            log.info("Очистка завершена");
        } catch (Exception e) {
            log.error("Ошибка в процессе очистки", e);
        }
    }

    /**
     * Отправка постов пользователям последовательно с задержками
     */
    private void sendPostsToUsersSequentially(Map<Long, List<TumblrPostDTO>> postsPerUser) {
        AtomicInteger totalSent = new AtomicInteger(0);
        AtomicInteger totalFailed = new AtomicInteger(0);

        log.info("Начинаем отправку постов {} пользователям", postsPerUser.size());

        // ДОБАВЬТЕ ЭТО:
        for (Map.Entry<Long, List<TumblrPostDTO>> entry : postsPerUser.entrySet()) {
            log.info("→ Пользователь {}: {} постов в очереди",
                    entry.getKey(), entry.getValue().size());
        }

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (Map.Entry<Long, List<TumblrPostDTO>> entry : postsPerUser.entrySet()) {
            Long userId = entry.getKey();
            List<TumblrPostDTO> posts = entry.getValue();

            log.info("=== Начинаем отправку {} постов пользователю {} ===", posts.size(), userId);

            for (TumblrPostDTO post : posts) {
                // ДОБАВЬТЕ ЭТО:
                log.info("→ Отправляем пост {} пользователю {}...", post.getId(), userId);

                chain = chain
                        .thenCompose(v -> {
                            log.debug("CompletableFuture: начинаем отправку поста {} пользователю {}",
                                    post.getId(), userId);
                            return notificationService.sendPostToUserAsync(userId, post);
                        })
                        .thenApply(sent -> {
                            if (sent) {
                                totalSent.incrementAndGet();
                                userPostTrackingService.markAsSent(userId, post.getId());
                                postTrackingService.markPostAsSent(post.getId());
                                log.info("✓ Пост {} успешно отправлен пользователю {}", post.getId(), userId);
                            } else {
                                totalFailed.incrementAndGet();
                                log.error("✗ ОШИБКА: Не удалось отправить пост {} пользователю {}",
                                        post.getId(), userId);
                            }
                            return null;
                        })
                        .exceptionally(ex -> {
                            totalFailed.incrementAndGet();
                            log.error("✗ ИСКЛЮЧЕНИЕ при отправке поста {} пользователю {}: {}",
                                    post.getId(), userId, ex.getMessage(), ex);
                            return null;
                        })
                        .thenCompose(v -> {
                            log.debug("Задержка {} мс между постами", delayBetweenPosts);
                            return delay(delayBetweenPosts);
                        });
            }

            // Задержка между пользователями
            chain = chain.thenCompose(v -> {
                log.debug("Задержка {} мс между пользователями", delayBetweenUsers);
                return delay(delayBetweenUsers);
            });
        }

        chain.thenRun(() -> {
            log.info("=== Отправка завершена ===");
            log.info("Успешно: {}", totalSent.get());
            log.info("Ошибок: {}", totalFailed.get());
        }).exceptionally(ex -> {
            log.error("КРИТИЧЕСКАЯ ОШИБКА в процессе отправки постов", ex);
            return null;
        }).join();
    }

    /**
     * Создает DTO из TrackedPost для отправки
     */
    private TumblrPostDTO createDTOFromTrackedPost(TrackedPost tracked) {
        TumblrPostDTO dto = new TumblrPostDTO();

        dto.setId(tracked.getPostId());
        dto.setBlogName(tracked.getBlogName());
        dto.setPostURL(tracked.getPostUrl());

        if (tracked.getTags() != null) {
            List<String> tags = Arrays.stream(tracked.getTags().split(","))
                    .map(String::trim)
                    .filter(tag -> !tag.isEmpty())
                    .collect(Collectors.toList());
            dto.setTags(tags);
        }

        if (tracked.getNoteCount() != null) {
            dto.setNoteCount(String.valueOf(tracked.getNoteCount()));
        }

        // Добавляем информацию о посте
        String summary = "Post from " + (tracked.getBlogName() != null ? tracked.getBlogName() : "Tumblr");
        if (tracked.getNoteCount() != null) {
            summary += " (" + tracked.getNoteCount() + " notes)";
        }
        dto.setSummary(summary);

        return dto;
    }

    /**
     * Создает задержку
     */
    private CompletableFuture<Void> delay(long millis) {
        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Задержка была прервана", e);
            }
        });
    }
}
