package TelegramBot.TumblrTagTracker.repositories;

import TelegramBot.TumblrTagTracker.models.TrackedPost;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TrackedPostRepository extends JpaRepository<TrackedPost, String> {
    Optional<TrackedPost> findByPostId(String postId);

    // Находит посты, которые еще не были отправлены и набрали минимум заметок
    @Query("SELECT tp FROM TrackedPost tp WHERE tp.wasSent = false AND tp.noteCount >= :minNotes")
    List<TrackedPost> findUnsentPostsWithMinimumNotes(@Param("minNotes") Integer minNotes);

    // Находит посты для повторной проверки, которые не были отправлены, когда последняя проверка была давно
    @Query("SELECT tp FROM TrackedPost tp WHERE tp.wasSent = false AND tp.lastCheckedAt < :checkBefore ORDER BY tp.lastCheckedAt ASC")
    List<TrackedPost> findPostsForRecheck(@Param("checkBefore") LocalDateTime checkBefore);

    // Находит старые отправленные посты для очистки
    @Query("SELECT tp FROM TrackedPost tp WHERE tp.wasSent = true AND tp.lastCheckedAt < :olderThan")
    List<TrackedPost> findOldSentPosts(@Param("olderThan") LocalDateTime olderThan);
}
