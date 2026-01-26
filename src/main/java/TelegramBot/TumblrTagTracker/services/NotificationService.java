package TelegramBot.TumblrTagTracker.services;

import TelegramBot.TumblrTagTracker.dto.TumblrPostDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final TelegramLongPollingBot bot;
    private final RedisCacheService redisCacheService;

    @Autowired
    public NotificationService(TelegramLongPollingBot bot, RedisCacheService redisCacheService) {
        this.bot = bot;
        this.redisCacheService = redisCacheService;
    }

    public void sendPostToUser(Long chatID, TumblrPostDTO post) {
        if (redisCacheService.wasSent(post.getId())) {
            return;
        }

        try {
            String message = post.getFormattedMessage();
            String imageUrl = getImageUrl(post);
            
            // Если есть изображение, отправляем фото с подписью
            if (imageUrl != null && !imageUrl.isEmpty()) {
                sendPhotoWithCaption(chatID, imageUrl, message);
            } else {
                sendTextMessage(chatID, message);
            }

            redisCacheService.markAsSent(post.getId());
            log.info("Пост {} отправлен человеку {}", post.getId(), chatID);

        } catch (TelegramApiException e) {
            log.error("Не удалось отправить пост {} человеку {}: {}", post.getId(), chatID, e.getMessage());
            try {
                // Fallback: отправляем простым текстом
                sendTextMessage(chatID, post.getFormattedMessage());
                redisCacheService.markAsSent(post.getId());
                log.info("Пост {} отправлен человеку {} простым текстом", post.getId(), chatID);
            } catch (TelegramApiException ex) {
                log.error("Не удалось отправить ни обычный, ни текстовый пост {} человеку {}", post.getId(), chatID);
            }
        }
    }
    
    /**
     * Определяет URL изображения для поста
     */
    private String getImageUrl(TumblrPostDTO post) {
        // Для PHOTO постов используем photoUrl
        if (post.getPhotoUrl() != null && !post.getPhotoUrl().isEmpty()) {
            return post.getPhotoUrl();
        }
        
        // Для TEXT постов пытаемся извлечь изображение из HTML
        String imageFromBody = post.extractImageUrlFromBody();
        if (imageFromBody != null && !imageFromBody.isEmpty()) {
            return imageFromBody;
        }
        
        // Проверяем sourceUrl, если это изображение
        if (post.getSourceUrl() != null && isImageUrl(post.getSourceUrl())) {
            return post.getSourceUrl();
        }
        
        return null;
    }

    private void sendTextMessage(Long chatID, String text) throws TelegramApiException {
        SendMessage message = new SendMessage();

        message.setChatId(chatID.toString());
        message.setText(text);
        message.setParseMode("Markdown");
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
            photo.setParseMode("Markdown");
        }

        bot.execute(photo);
    }

    private boolean isImageUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }

        String lowerUrl = url.toLowerCase();
        // Проверяем расширение файла
        if (lowerUrl.matches(".*\\.(jpg|jpeg|png|gif|bmp|webp)(\\?.*)?$")) {
            return true;
        }
        // Проверяем домены, которые обычно содержат изображения
        return lowerUrl.contains("media.tumblr.com") ||
               lowerUrl.contains("tumblr.com/photo") ||
               lowerUrl.contains("imgur.com") ||
               lowerUrl.contains("i.imgur.com");
    }

    public void clearCache() {
        redisCacheService.clearSentPostsCache();
        log.info("Отправленные посты почищены из кэша");
    }
}
