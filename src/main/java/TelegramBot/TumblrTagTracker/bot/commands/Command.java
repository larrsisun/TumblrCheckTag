package TelegramBot.TumblrTagTracker.bot.commands;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

public interface Command {

    void execute(Long chatID, String[] args, SendMessage response);

    String getName();
}
