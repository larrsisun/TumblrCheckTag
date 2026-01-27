package TelegramBot.TumblrTagTracker.util;

import TelegramBot.TumblrTagTracker.dto.TumblrPostDTO;
import TelegramBot.TumblrTagTracker.model.Subscription;
import TelegramBot.TumblrTagTracker.services.NotificationService;
import TelegramBot.TumblrTagTracker.services.SubscriptionService;
import TelegramBot.TumblrTagTracker.services.TumblrService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@EnableScheduling
public class TumblrCheckSchedule {

    private static final Logger log = LoggerFactory.getLogger(TumblrCheckSchedule.class);

    @Value("${notification.delay.between.posts.ms:50000}") // 0.5 секунды между постами
    private long delayBetweenPosts;
    @Value("${tumblr.check.interval.ms:3000}") // 5 минут по умолчанию
    private long checkIntervalMs;
    @Value("${notification.delay.between.users.ms:1000}") // 1 секунда между пользователями
    private long delayBetweenUsers;

    private final SubscriptionService subscriptionService;
    private final TumblrService tumblrService;
    private final NotificationService notificationService;

    @Autowired
    public TumblrCheckSchedule(SubscriptionService subscriptionService, TumblrService tumblrService, NotificationService notificationService) {
        this.subscriptionService = subscriptionService;
        this.tumblrService = tumblrService;
        this.notificationService = notificationService;
    }

    @Scheduled(fixedDelay = 300000) // 5 минут
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
                                break;
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
                    break;
                } catch (Exception e) {
                    log.error("Ошибка при обработке подписки пользователя {}: {}",
                            subscription.getChatID(), e.getMessage(), e);
                }
            }

        } catch (Exception e) {
            log.error("Ошибка при проверке постов.", e);
        }
    }
}
