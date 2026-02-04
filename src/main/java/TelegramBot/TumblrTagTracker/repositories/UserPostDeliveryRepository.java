package TelegramBot.TumblrTagTracker.repositories;

import TelegramBot.TumblrTagTracker.models.UserPostDelivery;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserPostDeliveryRepository extends JpaRepository<UserPostDelivery, Long> {

    Optional<UserPostDelivery> findByUserIdAndPostId(Long userId, String postId);

    @Query("SELECT upd FROM UserPostDelivery upd WHERE upd.wasSent = true AND upd.sentAt < :olderThan")
    List<UserPostDelivery> findOldSentDeliveries(@Param("olderThan") LocalDateTime olderThan);
}
