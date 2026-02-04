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

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Component
@EnableScheduling
public class TumblrCheckSchedule {

    private static final Logger log = LoggerFactory.getLogger(TumblrCheckSchedule.class);

    private final Duration delayBetweenPosts = Duration.ofMinutes(1);

    private final SubscriptionService subscriptionService;
    private final TumblrService tumblrService;
    private final NotificationService notificationService;
    private final PostTrackingService postTrackingService;
    private final UserPostTrackingService userPostTrackingService;

    // Пул потоков для параллельной отправки (по одному потоку на пользователя)
    private final ExecutorService userExecutor = Executors.newCachedThreadPool();

    @Autowired
    public TumblrCheckSchedule(SubscriptionService subscriptionService, TumblrService tumblrService,
                               NotificationService notificationService, PostTrackingService postTrackingService,
                               UserPostTrackingService userPostTrackingService) {
        this.subscriptionService = subscriptionService;
        this.tumblrService = tumblrService;
        this.notificationService = notificationService;
        this.postTrackingService = postTrackingService;
        this.userPostTrackingService = userPostTrackingService;
    }

    @Scheduled(fixedDelay = 300000) // 5 минут
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

                for (Subscription subscription : activeSubscriptions) {
                    Long userId = subscription.getChatID();
                    Set<String> userTags = subscription.getTags();

                    if (userPostTrackingService.shouldSendToUser(userId, post, userTags)) {
                        postsPerUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(post);
                        log.debug("Пост {} добавлен для пользователя {}", post.getId(), userId);
                    }
                }
            }

            if (postsPerUser.isEmpty()) {
                log.info("Нет постов для отправки пользователям (не прошли фильтры или уже отправлены)");
                return;
            }

            log.info("=== ИТОГО: {} постов для отправки {} пользователям ===", postsPerUser.size(), activeSubscriptions.size());

            for (Map.Entry<Long, List<TumblrPostDTO>> entry : postsPerUser.entrySet()) {
                log.info("Пользователь {}: {} постов", entry.getKey(), entry.getValue().size());
            }

            sendPostsToUsersAsync(postsPerUser);

        } catch (Exception e) {
            log.error("Ошибка при проверке новых постов", e);
        }
    }

    @Scheduled(fixedDelay = 1800000) // 30 минут
    public void checkDelayedPosts() {
        try {
            log.info("Проверка отложенных постов");

            List<TrackedPost> readyPosts = postTrackingService.findPostsReadyToSend();

            if (readyPosts.isEmpty()) {
                log.info("Отложенных постов нет");
                return;
            }

            log.info("Найдено {} постов, готовых к отправке", readyPosts.size());

            List<Subscription> activeSubscriptions = subscriptionService.getAllActiveSubscriptions();

            if (activeSubscriptions.isEmpty()) {
                log.info("Нет активных подписчиков для отложенных постов");
                return;
            }

            Map<Long, List<TumblrPostDTO>> postsPerUser = new HashMap<>();

            for (TrackedPost trackedPost : readyPosts) {
                Set<String> postTags = trackedPost.getTags() != null
                        ? Arrays.stream(trackedPost.getTags().split(","))
                        .map(String::trim)
                        .filter(tag -> !tag.isEmpty())
                        .collect(Collectors.toSet()) : Collections.emptySet();

                if (postTags.isEmpty()) {
                    log.warn("Пост {} не имеет тегов, пропускаем", trackedPost.getPostId());
                    continue;
                }

                for (Subscription subscription : activeSubscriptions) {
                    Long userId = subscription.getChatID();
                    Set<String> userTags = subscriptionService.getTags(userId);

                    if (userTags == null || userTags.isEmpty()) {
                        continue;
                    }

                    TumblrPostDTO postDTO = createDTOFromTrackedPost(trackedPost);

                    if (userPostTrackingService.shouldSendToUser(userId, postDTO, userTags)) {
                        postsPerUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(postDTO);
                    }
                }
            }

            if (!postsPerUser.isEmpty()) {
                int totalPosts = postsPerUser.values().stream().mapToInt(List::size).sum();
                log.info("Отправляем {} отложенных постов {} пользователям", totalPosts, postsPerUser.size());
                sendPostsToUsersAsync(postsPerUser);
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

            List<TrackedPost> postsToRecheck = postTrackingService.findPostsForRecheck();

            if (postsToRecheck.isEmpty()) {
                log.info("Нет постов для повторной проверки метрик");
                return;
            }

            log.info("Найдено {} постов для повторной проверки", postsToRecheck.size());

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

            List<TumblrPostDTO> freshPosts = tumblrService.getNewPostsByTags(tagsToCheck);

            if (freshPosts.isEmpty()) {
                log.info("Не получено свежих данных от Tumblr API");
                return;
            }

            Map<String, TumblrPostDTO> freshPostsMap = freshPosts.stream()
                    .collect(Collectors.toMap(TumblrPostDTO::getId, p -> p, (p1, p2) -> p1));

            int updatedCount = 0;

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

    @Scheduled(cron = "${tumblr.cleanup.cron:0 0 3 * * ?}")
    public void cleanupOldPosts() {
        try {
            log.info("Начало очистки старых данных");
            postTrackingService.cleanUpOldPosts();
            userPostTrackingService.cleanupOldDeliveries(7);
            log.info("Очистка завершена");
        } catch (Exception e) {
            log.error("Ошибка в процессе очистки", e);
        }
    }

     // Каждый пользователь получает посты в своем собственном потоке с задержками
    private void sendPostsToUsersAsync(Map<Long, List<TumblrPostDTO>> postsPerUser) {
        AtomicInteger totalSent = new AtomicInteger(0);
        AtomicInteger totalFailed = new AtomicInteger(0);

        // Для каждого пользователя создаем отдельную задачу
        List<CompletableFuture<Void>> userTasks = new ArrayList<>();

        for (Map.Entry<Long, List<TumblrPostDTO>> entry : postsPerUser.entrySet()) {
            Long userId = entry.getKey();
            List<TumblrPostDTO> posts = entry.getValue();

            // Создаем асинхронную задачу для этого пользователя
            CompletableFuture<Void> userTask = CompletableFuture.runAsync(() -> {

                for (int i = 0; i < posts.size(); i++) {
                    TumblrPostDTO post = posts.get(i);

                    try {
                        // Отправляем пост
                        boolean sent = notificationService.sendPostToUser(userId, post);

                        if (sent) {
                            totalSent.incrementAndGet();
                            userPostTrackingService.markAsSent(userId, post.getId());
                            postTrackingService.markPostAsSent(post.getId());
                        } else {
                            totalFailed.incrementAndGet();
                        }

                        // Задержка между постами (кроме последнего)
                        if (i < posts.size() - 1) {
                            Thread.sleep(delayBetweenPosts);
                        }

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("Отправка пользователю {} прервана", userId, e);
                        break;
                    } catch (Exception e) {
                        totalFailed.incrementAndGet();
                        log.error("Ошибка при отправке поста {} пользователю {}", post.getId(), userId, e);
                    }
                }

                log.info("Завершена отправка постов пользователю {}", userId);}, userExecutor); // Выполняется в отдельном потоке

            userTasks.add(userTask);
        }

        // Ждем завершения всех задач
        CompletableFuture<Void> allTasks = CompletableFuture.allOf(userTasks.toArray(new CompletableFuture[0]));

        try {
            // Ждем завершения с таймаутом (чтобы не зависнуть навсегда)
            allTasks.get(30, TimeUnit.MINUTES);

            log.info("=== ✓ ВСЕ ПОЛЬЗОВАТЕЛИ ОБРАБОТАНЫ ===");
            log.info("Успешно отправлено: {}", totalSent.get());
            log.info("Ошибок: {}", totalFailed.get());

        } catch (TimeoutException e) {
            log.error("Превышен таймаут ожидания отправки (30 минут)", e);
        } catch (Exception e) {
            log.error("!!! Критическая ошибка при отправке", e);
        }
    }

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

        return dto;
    }
}