package TelegramBot.TumblrTagTracker.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Set;

@Entity
@Table(name = "user_post_delivery", indexes = {
        @Index(name = "idx_user_post", columnList = "user_id,post_id", unique = true),
        @Index(name = "idx_was_sent", columnList = "was_sent"),
        @Index(name = "idx_user_id", columnList = "user_id")})
@Getter
@Setter
public class UserPostDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "post_id", nullable = false, length = 100)
    private String postId;

    @Column(name = "was_sent", nullable = false)
    private Boolean wasSent;

    @Column(name = "matched_tags", columnDefinition = "TEXT")
    private String matchedTags;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    public UserPostDelivery() {
        this.createdAt = LocalDateTime.now();
        this.wasSent = false;
    }

    public UserPostDelivery(Long userId, String postId, Set<String> matchedTags) {
        this();
        this.userId = userId;
        this.postId = postId;

        if (matchedTags != null && !matchedTags.isEmpty()) {
            this.matchedTags = String.join(",", matchedTags);
        }
    }
}
