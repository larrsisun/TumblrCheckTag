package TelegramBot.TumblrTagTracker.bot;


import TelegramBot.TumblrTagTracker.bot.commands.Command;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TumblrBot extends TelegramLongPollingBot {
    private final String botToken;
    private final String botUsername;
    private final Map<String, Command> commandMap;

    @Autowired
    public TumblrBot(
            @Value("${telegram.bot.token}") String botToken,
            @Value("${telegram.bot.username}") String botUsername,
            List<Command> commands
    ) {
        this.botToken = botToken;
        this.botUsername = botUsername;
        this.commandMap = new HashMap<>();

        // Инициализация комманд
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

        // Проверяем, является ли сообщение командой
        if (!text.startsWith("/")) {
            return;
        }

        // Парсим команду и аргументы
        String[] parts = text.split("\\s+");
        String commandName = parts[0].toLowerCase(); // "/filter"
        String[] args = Arrays.copyOfRange(parts, 1, parts.length); // ["add", "art", "memes"]

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
            e.printStackTrace();
        }
    }

    private void sendUnknownCommandMessage(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Упс, неизвестная команда! Используйте /help для списка команд.");

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
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
