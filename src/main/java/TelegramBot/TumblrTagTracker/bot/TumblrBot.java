package TelegramBot.TumblrTagTracker.bot;


import TelegramBot.TumblrTagTracker.bot.commands.Command;
import TelegramBot.TumblrTagTracker.util.BotExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class TumblrBot extends TelegramLongPollingBot {
    private final String botToken;
    private final String botUsername;
    private final Map<String, Command> commandMap;

    private Logger log = LoggerFactory.getLogger(TumblrBot.class);

    @Autowired
    public TumblrBot(@Value("${telegram.bot.token}") String botToken, @Value("${telegram.bot.username}")
                         String botUsername, List<Command> commands) {
        this.botToken = botToken;
        this.botUsername = botUsername;
        this.commandMap = new HashMap<>();

        // Инициализация команд для бота
        for (Command command : commands) {
            commandMap.put(command.getName().toLowerCase(), command);
        }
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        Message message = update.getMessage();
        Long chatId = message.getChatId();
        String text = message.getText().trim();
        SendMessage messageForUser = new SendMessage();

        // Проверяем, является ли сообщение командой
        if (!text.startsWith("/")) {
            messageForUser.setText("Извините, я умею понимать только команды и не могу с вами пообщаться!");
            log.info("Пользователь {} отправил сообщение: {}", message.getChatId(), message.getText());
            return;
        }

        // Парсим команду и аргументы
        String[] parts = parseArguments(text);
        String commandName = parts[0].toLowerCase();
        String[] args = Arrays.copyOfRange(parts, 1, parts.length); // ["add", "lord of the mysteries", "fanart"]

        // Ищем команду
        Command command = commandMap.get(commandName);
        if (command == null) {
            sendUnknownCommandMessage(chatId);
            return;
        }

        // Выполняем команду
        SendMessage response = new SendMessage();
        response.setChatId(chatId.toString());

        try {
            command.execute(chatId, args, response);
            execute(response);
        } catch (TelegramApiException e) {
            log.error("Не удалось выполнить команду со стороны пользователя {}", chatId);
        }
    }

    private void sendUnknownCommandMessage(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Упс, неизвестная команда! Используйте /help для списка команд.");

        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить сообщение.");
        }
    }

    // Парс строки команды для многословных тегов
    private String[] parseArguments(String text) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args.toArray(new String[0]);
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

}
