package TelegramBot.TumblrTagTracker.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Setter
@Getter
@Entity
@Table(name = "Subscription")
public class Subscription {

    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_ID", nullable = false, unique = true)
    private Long chatID;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "subscription_tags",
            joinColumns = @JoinColumn(name = "subscription_id")
    )
    @Column(name = "tag")
    private Set<String> tags = new HashSet<>();

    public Subscription() {
        this.createdAt = LocalDateTime.now();
        this.isActive = true;
    }

    public Subscription(Long chatID) {
        this.chatID = chatID;
        this.createdAt = LocalDateTime.now();
        this.isActive = true;
    }

    public Boolean getActive() {
        return isActive;
    }

    public void setActive(Boolean active) {
        isActive = active;
    }

}
