package TelegramBot.TumblrTagTracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "tracked_posts")
@Getter
@Setter
public class TrackedPost {

    @Id
    @Column(name = "post_id", length = 100)
    private String postId;

    @Column(name = "blog_name", length = 500)
    private String blogName;

    @Column(name = "post_url", length = 500)
    private String postUrl;

    @Column(name = "note_count")
    private Long noteCount;

    @Column(name = "first_seen_at")
    private LocalDateTime firstSeenAt;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "post_created_at")
    private LocalDateTime postCreatedAt;

    @Column(name = "was_sent")
    private Boolean wasSent;

    @Column(name = "sent_to_users_count")
    private Integer sentToUsersCount;

    @Column(name = "tags")
    private String tags;

    public TrackedPost() {
        this.firstSeenAt = LocalDateTime.now();
        this.lastCheckedAt = LocalDateTime.now();
        this.wasSent = false;
        this.sentToUsersCount = 0;
    }

    public TrackedPost(String postId) {
        this();
        this.postId = postId;
    }

    public boolean meetsMinimumThreshold(int minimumNotes) {
        return this.noteCount != null && this.noteCount >= minimumNotes;
    }

    public boolean isOldEnough(int minAgeInHours) {
        if (this.postCreatedAt == null) {
            return true; // если не знаем возраст, считаем что достаточно старый
        }
        return LocalDateTime.now().minusHours(minAgeInHours).isAfter(this.postCreatedAt);
    }

    public void markAsSent() {
        this.wasSent = true;
        this.sentToUsersCount++;
    }
}
