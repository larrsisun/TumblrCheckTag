package TelegramBot.TumblrTagTracker.repositories;


import TelegramBot.TumblrTagTracker.models.Subscription;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, String> {

    Optional<Subscription> findByChatID(Long chatID);

    @Modifying
    @Query("UPDATE Subscription s SET s.isActive = false WHERE s.chatID = :chatID")
    void deactivateByChatID(@Param("chatID") Long chatID);

    // Исправление проблемы N + 1
    @Query("SELECT s FROM Subscription s LEFT JOIN FETCH s.tags WHERE s.isActive = true")
    List<Subscription> findByIsActiveTrueWithTags();
}
