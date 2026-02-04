package TelegramBot.TumblrTagTracker.services;

import TelegramBot.TumblrTagTracker.dto.TumblrPostDTO;
import TelegramBot.TumblrTagTracker.models.Subscription;
import TelegramBot.TumblrTagTracker.models.UserPostDelivery;
import TelegramBot.TumblrTagTracker.repositories.UserPostDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class UserPostTrackingService {

    private static final Logger log = LoggerFactory.getLogger(UserPostTrackingService.class);

    private final UserPostDeliveryRepository deliveryRepository;
    private final RedisCacheService cacheService;

    @Autowired
    public UserPostTrackingService(UserPostDeliveryRepository deliveryRepository,
                                   RedisCacheService cacheService) {
        this.deliveryRepository = deliveryRepository;
        this.cacheService = cacheService;
    }

    public boolean shouldSendToUser(Long userId, TumblrPostDTO post, Set<String> userTags) {

        if (userId == null || post == null || post.getId() == null) {
            return false;
        }

        if (userTags == null || userTags.isEmpty()) {
            return false;
        }

        // Сначала проверяем Redis
        if (cacheService.wasSentToUser(userId, post.getId())) {
            log.info("Пост {} уже был отправлен пользователю {} (Redis)", post.getId(), userId);
            return false;
        }

        // Проверяем БД
        Optional<UserPostDelivery> existing = deliveryRepository.findByUserIdAndPostId(userId, post.getId());

        if (existing.isPresent() && existing.get().getWasSent()) {
            log.info("Пост {} уже был отправлен пользователю {} (БД)", post.getId(), userId);
            cacheService.markAsSentToUser(userId, post.getId());
            return false;
        }

        // Проверяем, совпадают ли теги
        Set<String> postTags = post.getTags() != null ? new HashSet<>(post.getTags()) : Collections.emptySet();
        Set<String> matchedTags = userTags.stream().filter(postTags::contains).collect(Collectors.toSet());

        if (matchedTags.isEmpty()) {
            return false;
        }

        // Создаем запись о необходимости доставки, если ее еще нет
        if (existing.isEmpty()) {
            UserPostDelivery delivery = new UserPostDelivery(userId, post.getId(), matchedTags);
            deliveryRepository.save(delivery);
        }

        log.info("Пост {} готов к отправке пользователю {} (теги: {})", post.getId(), userId, matchedTags);
        return true;
    }

    public void markAsSent(Long userId, String postId) {
        if (userId == null || postId == null) {
            log.warn("Попытка пометить пост как отправленный с некорректными параметрами");
            return;
        }

        deliveryRepository.findByUserIdAndPostId(userId, postId).ifPresent(delivery -> {
            delivery.setWasSent(true);
            delivery.setSentAt(LocalDateTime.now());
            deliveryRepository.save(delivery);

            // Дублируем в Redis
            cacheService.markAsSentToUser(userId, postId);

            log.debug("Пост {} помечен как отправленный пользователю {}", postId, userId);
        });
    }

    public List<Long> findUsersForPost(String postId, Set<String> postTags, List<Subscription> allSubscriptions) {
        List<Long> targetUsers = new ArrayList<>();

        if (postId == null || postTags == null || postTags.isEmpty()) {
            log.warn("Некорректные параметры для поиска пользователей");
            return targetUsers;
        }

        for (Subscription subscription : allSubscriptions) {
            Long userId = subscription.getChatID();
            Set<String> userTags = subscription.getTags();

            if (userTags == null || userTags.isEmpty()) {
                continue;
            }

            // Проверяем совпадение тегов
            boolean hasMatchingTags = userTags.stream().anyMatch(postTags::contains);
            if (!hasMatchingTags) {
                continue;
            }

            // Проверяем, не был ли уже отправлен
            if (cacheService.wasSentToUser(userId, postId)) {
                continue;
            }

            Optional<UserPostDelivery> delivery = deliveryRepository
                    .findByUserIdAndPostId(userId, postId);

            if (delivery.isPresent() && delivery.get().getWasSent()) {
                continue;
            }

            targetUsers.add(userId);
        }

        return targetUsers;
    }

    public void cleanupOldDeliveries(int daysOld) {
        LocalDateTime olderThan = LocalDateTime.now().minusDays(daysOld);
        List<UserPostDelivery> oldDeliveries = deliveryRepository.findOldSentDeliveries(olderThan);

        if (!oldDeliveries.isEmpty()) {
            deliveryRepository.deleteAll(oldDeliveries);
            log.info("Удалено {} старых записей о доставке", oldDeliveries.size());
        }
    }
}
