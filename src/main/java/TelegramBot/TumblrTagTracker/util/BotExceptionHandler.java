package TelegramBot.TumblrTagTracker.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

public class BotExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BotExceptionHandler.class);

    public static void handleException(Exception e, Long chatID, SendMessage response) {
        if (e instanceof SubscriptionNotFoundException) {
            response.setText(e.getMessage());
            log.warn("Подписка не найдена для пользователя {}: {}", chatID, e.getMessage());
        } else if (e instanceof DatabaseException) {
            response.setText(e.getMessage());
            log.warn("Ошибка со стороны БД для пользователя {}.", chatID, e);
        } else {
            response.setText("Непредвиденная ошибка. Попробуйте позже.");
            log.error("Непредвиденная ошибка для пользователя {}", chatID);
        }
    }
}
