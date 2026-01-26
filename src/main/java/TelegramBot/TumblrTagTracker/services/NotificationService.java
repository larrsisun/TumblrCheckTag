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
            // Если есть фото URL, отправляем фото с подписью
            if (post.getPhotoUrl() != null && !post.getPhotoUrl().isEmpty()) {
                sendPhotoWithCaption(chatID, post.getPhotoUrl(), message);
            } else if (isImageUrl(post.getSourceUrl())) {
                sendPhotoWithCaption(chatID, post.getSourceUrl(), message);
            } else {
                sendTextMessage(chatID, message);
            }

            redisCacheService.markAsSent(post.getId());
            log.info("Пост {} отправлен человеку {}", post.getId(), chatID);

        } catch (TelegramApiException e) {
            log.error("Не удалось отправить пост {} человеку {}", post.getId(), chatID);
            try {
                sendTextMessage(chatID, post.getFormattedMessage());
                redisCacheService.markAsSent(post.getId());
                log.info("Пост {} отправлен человеку {} простым текстом", post.getId(), chatID);
            } catch (TelegramApiException ex) {
                log.error("Не удалось отправить ни обычный, ни текстовый пост {} человеку {}", post.getId(), chatID);
            }
        }
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

        if (caption.length() > 1024) {
            caption = caption.substring(0, 1000) + "...";
        }
        photo.setCaption(caption);
        photo.setParseMode("Markdown");

        bot.execute(photo);

    }

    private boolean isImageUrl(String url) {
        if (url == null) {
            return false;
        }

        String lowerUrl = url.toLowerCase();
        return lowerUrl.matches(".*\\.(jpg|jpeg|png|gif|bmp|webp)$") ||
                lowerUrl.contains("imgur.com") ||
                lowerUrl.contains("tumblr.com") ||
                lowerUrl.contains("media.tumblr.com");
    }

    public void clearCache() {
        redisCacheService.clearSentPostsCache();
        log.info("Отправленные посты почищены из кэша");
    }
}
