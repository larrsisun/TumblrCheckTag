package TelegramBot.TumblrTagTracker.services;

import TelegramBot.TumblrTagTracker.dto.TumblrPostDTO;
import TelegramBot.TumblrTagTracker.util.ContentExtractor;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final TelegramLongPollingBot bot;
    private final RedisCacheService redisCacheService;
    private final ContentExtractor contentExtractor;

    @Autowired
    public NotificationService(TelegramLongPollingBot bot, RedisCacheService redisCacheService, ContentExtractor contentExtractor) {
        this.bot = bot;
        this.redisCacheService = redisCacheService;
        this.contentExtractor = contentExtractor;
    }

    @Async("notificationExecutor")
    public CompletableFuture<Boolean> sendPostToUserAsync (Long chatID, TumblrPostDTO post) {
        try {
            boolean sent = sendPostToUser(chatID, post);
            return CompletableFuture.completedFuture(sent);
        } catch (Exception e) {
            log.error("Unexpected error sending post {} to user {}", post.getId(), chatID, e);
            return CompletableFuture.completedFuture(false);
        }
    }

    @CircuitBreaker(name = "telegram", fallbackMethod = "fallbackSendMessage")
    @Retry(name = "telegram")
    public boolean sendPostToUser(Long chatID, TumblrPostDTO post) {
        if (redisCacheService.wasSent(post.getId())) {
            return false;
        }

        try {
            String message = post.getFormattedMessage();
            String imageUrl = getImageUrl(post);
            String videoUrl = getVideoUrl(post);
            
            // Если есть изображение, отправляем фото с подписью
            if (imageUrl != null && !imageUrl.isEmpty()) {
                sendPhotoWithCaption(chatID, imageUrl, message);
            } else if (videoUrl != null && !videoUrl.isEmpty()) {
                sendVideoWithCaption(chatID, videoUrl, message);
            } else {
                sendTextMessage(chatID, message);
            }

            redisCacheService.markAsSentIfNotSent(post.getId());
            log.info("Пост {} отправлен человеку {}", post.getId(), chatID);
            return true;

        } catch (TelegramApiException e) {
            log.error("Не удалось отправить пост {} человеку {}", post.getId(), chatID, e);
            try {
                // Fallback: отправляем простым текстом
                sendTextMessage(chatID, post.getFormattedMessage());
                redisCacheService.markAsSentIfNotSent(post.getId());
                log.info("Пост {} отправлен человеку {} простым текстом", post.getId(), chatID);
                return  true;
            } catch (TelegramApiException ex) {
                log.error("Не удалось отправить ни обычный, ни текстовый пост {} человеку {}", post.getId(), chatID, ex);
                return false;
            }
        }
    }

    private String getImageUrl(TumblrPostDTO post) {
        return Optional.ofNullable(post.getPhotoUrl())
                .filter(url -> !url.isEmpty())
                .or(() -> contentExtractor.extractFirstImageUrl(post.getBody()))
                .orElse(null);
    }

    private String getVideoUrl(TumblrPostDTO post) {
        return Optional.ofNullable(post.getVideoUrl())
                .filter(url -> !url.isEmpty())
                .or(() -> contentExtractor.extractFirstVideoUrl(post.getBody()))
                .orElse(null);
    }


    private void sendTextMessage(Long chatID, String text) throws TelegramApiException {
        SendMessage message = new SendMessage();

        message.setChatId(chatID.toString());
        message.setText(text);
        message.setParseMode("MarkdownV2");
        message.disableWebPagePreview();

        bot.execute(message);
    }

    private void sendPhotoWithCaption(Long chatID, String photoURL, String caption) throws TelegramApiException {
        SendPhoto photo = new SendPhoto();

        photo.setChatId(chatID.toString());

        InputFile inputFile = new InputFile();
        inputFile.setMedia(photoURL);

        photo.setPhoto(inputFile);

        // Ограничиваем длину подписи (Telegram ограничение: 1024 символа)
        if (caption != null && caption.length() > 1024) {
            caption = caption.substring(0, 1021) + "...";
        }
        
        if (caption != null && !caption.trim().isEmpty()) {
            photo.setCaption(caption);
            photo.setParseMode("MarkdownV2");
        }

        bot.execute(photo);
    }

    private void sendVideoWithCaption(Long chatID, String videoURL, String caption) throws TelegramApiException {
        SendVideo video = new SendVideo();

        video.setChatId(chatID.toString());

        InputFile inputFile = new InputFile();
        inputFile.setMedia(videoURL);

        video.setVideo(inputFile);

        if (caption != null && caption.length() > 1024) {
            caption = caption.substring(0, 1021) + "...";
        }

        if (caption != null && !caption.trim().isEmpty()) {
            video.setCaption(caption);
            video.setParseMode("MarkdownV2");
        }

        bot.execute(video);
    }

    private boolean fallbackSendMessage(Long chatID, TumblrPostDTO post, Exception e) {
        log.error("Failed to send post {} to user {} after retries", post.getId(), chatID, e);
        return false;
    }
}
