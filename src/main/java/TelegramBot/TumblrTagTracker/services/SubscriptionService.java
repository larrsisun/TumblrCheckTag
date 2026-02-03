package TelegramBot.TumblrTagTracker.services;


import TelegramBot.TumblrTagTracker.models.Subscription;
import TelegramBot.TumblrTagTracker.repositories.SubscriptionRepository;
import TelegramBot.TumblrTagTracker.util.DatabaseException;
import TelegramBot.TumblrTagTracker.util.SubscriptionNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    private final SubscriptionRepository subscriptionRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    public SubscriptionService(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    public Subscription subscribe(Long chatID) {

        try {
            Optional<Subscription> subscription = subscriptionRepository.findByChatID(chatID);
            if (subscription.isPresent()) {
                Subscription sub = subscription.get();
                if (Boolean.TRUE.equals(sub.getActive())) {
                    log.info("Пользователь {} уже подписан.", chatID);
                    return sub;
                }
                sub.setActive(true);
                return subscriptionRepository.save(sub);
            }

            Subscription newSub = new Subscription(chatID);
            return subscriptionRepository.save(newSub);
        } catch (DataAccessException e) {
            log.error("Ошибка в базе данных при попытке подписать пользователя");
            throw new DatabaseException("Не удалось подписаться из-за проблем с базой данных");
        }
    }

    public boolean unsubscribe(Long chatID) {
        try {
            Optional<Subscription> subscription = subscriptionRepository.findByChatID(chatID);
            if (subscription.isPresent() && Boolean.TRUE.equals(subscription.get().getActive())) {
                subscriptionRepository.deactivateByChatID(chatID);
                log.info("Пользователь {} отписан!", chatID);
                return true;
            }
            log.warn("Попытка отписать неподписанного пользователя {}", chatID);
            return false;
        } catch (DataAccessException e) {
            log.error("Ошибка в базе данных при попытке отписать пользователя {}", chatID);
            throw new DatabaseException("Не удалось отписать пользователя ввиду ошибки со стороны БД");
        }
    }

    public boolean isSubscribed(Long chatID) {
        try {
            Optional<Subscription> subscription = subscriptionRepository.findByChatID(chatID);
            return subscription.isPresent() && Boolean.TRUE.equals(subscription.get().getActive());
        } catch (DataAccessException e) {
            log.error("Ошибка при попытке выяснить, подписан ли пользователь {}", chatID);
            throw new DatabaseException("Не удалось узнать статус пользователя ввиду ошибки со стороны БД");
        }

    }

    // ИСПРАВЛЕНИЕ: Принудительно получаем свежие данные из БД
    @Transactional(readOnly = true)
    public Set<String> getTags(Long chatID) {
        try {
            // Очищаем кеш EntityManager для этой сущности, если она есть
            Optional<Subscription> cachedSub = subscriptionRepository.findByChatID(chatID);
            if (cachedSub.isPresent()) {
                entityManager.refresh(cachedSub.get()); // Принудительно обновляем из БД
                log.debug("Теги для пользователя {} обновлены из БД: {}", chatID, cachedSub.get().getTags());
                return cachedSub.get().getTags();
            }
            return Set.of();
        } catch (DataAccessException e) {
            log.error("Database error while getting tags for user {}", chatID, e);
            return Set.of();
        } catch (Exception e) {
            log.error("Error refreshing subscription for user {}", chatID, e);
            // Fallback: пытаемся получить без refresh
            return getSubscription(chatID)
                    .map(Subscription::getTags)
                    .orElse(Set.of());
        }
    }

    public Subscription updateTags(Long chatId, Set<String> tags) {
        try {
            Subscription subscription = getSubscription(chatId)
                    .orElseThrow(() -> new SubscriptionNotFoundException("Подписка не найдена, сначала подпишитесь (/subscribe)."));
            subscription.setTags(tags);
            Subscription saved = subscriptionRepository.save(subscription);
            entityManager.flush(); // ИСПРАВЛЕНИЕ: Принудительно сбрасываем изменения в БД
            log.info("Теги пользователя {} обновлены: {}", chatId, tags);
            return saved;
        } catch (SubscriptionNotFoundException e) {
            throw e; // Пробрасываем дальше
        } catch (DataAccessException e) {
            log.error("Database error while updating tags for user {}", chatId, e);
            throw new DatabaseException("Не удалось обновить теги. Попробуйте позже.");
        }
    }


    public Optional<Subscription> getSubscription(Long chatId) {
        return subscriptionRepository.findByChatID(chatId);
    }

    public List<Subscription> getAllActiveSubscriptions() {
        return subscriptionRepository.findByIsActiveTrueWithTags();
    }

}
