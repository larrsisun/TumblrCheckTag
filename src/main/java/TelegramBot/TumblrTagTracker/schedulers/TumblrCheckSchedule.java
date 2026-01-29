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

            // Проходим по каждому подписчику
            for (Subscription subscription : activeSubscriptions) {
                try {
                    Set<String> userTags = subscription.getTags();
                    if (userTags == null || userTags.isEmpty()) {
                        log.debug("Пользователь {} не имеет тегов, пропускаем", subscription.getChatID());
                        continue;
                    }

                    log.debug("Проверка постов для пользователя {} по тегам: {}", subscription.getChatID(), userTags);

                    // Получаем новые посты по тегам пользователя
                    List<TumblrPostDTO> newPosts = tumblrService.getNewPostsByTags(userTags);

                    if (newPosts.isEmpty()) {
                        log.debug("Новых постов для пользователя {} не найдено", subscription.getChatID());
                    } else {
                        log.info("Найдено {} новых постов для пользователя {}", newPosts.size(), subscription.getChatID());

                        // Отправляем посты пользователю
                        for (TumblrPostDTO post : newPosts) {
                            try {
                                notificationService.sendPostToUser(subscription.getChatID(), post);
                                // Задержка между постами
                                if (delayBetweenPosts > 0) {
                                    Thread.sleep(delayBetweenPosts);
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                log.error("Прервано ожидание между постами для пользователя {}", subscription.getChatID());
                                return;
                            } catch (Exception e) {
                                log.error("Ошибка при отправке поста {} пользователю {}", post.getId(), subscription.getChatID(), e);
                            }
                        }
                    }

                    // Задержка между пользователями, чтобы не перегрузить Telegram API
                    if (delayBetweenUsers > 0) {
                        Thread.sleep(delayBetweenUsers);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Прервано ожидание между пользователями");
                    return;
                } catch (Exception e) {
                    log.error("Ошибка при обработке подписки пользователя {}: {}",
                            subscription.getChatID(), e.getMessage(), e);
                }
            }

        } catch (Exception e) {
            log.error("Ошибка при проверке постов.", e);
        }
    }


    @Scheduled(fixedDelay = 1800000) // 30 минут
    public void checkDelayedPosts() {
        try {
            log.info("Проверка отложенных постов");
            List<TrackedPost> readyPosts = postTrackingService.findPostsReadyToSend();

            if (readyPosts.isEmpty()) {
                log.info("Нет отложенных постов, готовых к отправке");
                return;
            }

            log.info("Найдено {} отложенных постов, готовых к отправке", readyPosts.size());

            // Получаем всех активных подписчиков
            List<Subscription> activeSubscriptions = subscriptionService.getAllActiveSubscriptions();

            // Для каждого готового поста находим подходящих пользователей
            for (TrackedPost trackedPost : readyPosts) {
                Set<String> postTags = trackedPost.getTags() != null ? Set.of(trackedPost.getTags().split(",")) : Set.of();

                // Находим пользователей, у которых есть пересечение тегов
                List<Subscription> matchingUsers = activeSubscriptions.stream()
                        .filter(sub -> sub.getTags() != null && !sub.getTags().isEmpty())
                        .filter(sub -> hasCommonTags(sub.getTags(), postTags))
                        .toList();

                if (!matchingUsers.isEmpty()) {
                    log.info("Отправка отложенного поста {} пользователям: {}", trackedPost.getPostId(), matchingUsers.size());
                    TumblrPostDTO postDTO = createDTOFromTrackedPost(trackedPost);

                    // Асинхронно отправляем посты
                    for (Subscription user : matchingUsers) {
                        notificationService.sendPostToUserAsync(user.getChatID(), postDTO);

                        // Небольшая задержка между пользователями
                        if (delayBetweenUsers > 0) {
                            Thread.sleep(delayBetweenUsers);
                        }
                    }
                    // Помечаем пост как отправленный
                    postTrackingService.markPostAsSent(trackedPost.getPostId());
                }
            }

        } catch (Exception e) {
            log.error("Ошибка при проверке отложенных постов", e);
        }
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
