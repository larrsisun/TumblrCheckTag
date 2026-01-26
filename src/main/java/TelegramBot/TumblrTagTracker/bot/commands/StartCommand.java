package TelegramBot.TumblrTagTracker.bot.commands;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

@Component
public class StartCommand implements Command
{
    @Override
    public void execute(Long chatID, String[] args, SendMessage response) {
        String welcomeMessage = """
                Привет, я бот, который может присылать новые посты из Тамблера по желаемым тегам!
                
                *Доступные команды*:\n 
                /subscribe - подписаться на рассылку;\n
                /unsubscribe - отписаться от рассылки;\n
                /help - помощь по командам;\n
                /tag - инструкция по выбору тегов (нажмите после подписки);\n
                
                """;

        response.setText(welcomeMessage);
        response.setParseMode(ParseMode.MARKDOWN);
    }

    @Override
    public String getName() {
        return "/start";
    }

}
