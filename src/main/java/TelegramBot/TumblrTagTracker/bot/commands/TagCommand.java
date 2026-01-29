package TelegramBot.TumblrTagTracker.bot.commands;

import TelegramBot.TumblrTagTracker.services.SubscriptionService;
import TelegramBot.TumblrTagTracker.util.BotExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class TagCommand implements Command {

    private static final int MAX_LENGTH = 150;
    private static final int MAX_TAGS_PER_USER = 100;
    private static final Pattern VALID_TAG_PATTERN = Pattern.compile("^[a-zA-Z0-9\\s-]+$");
    private final SubscriptionService subscriptionService;

    @Autowired
    public TagCommand(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @Override
    public void execute(Long chatID, String[] args, SendMessage response) {
        if(!subscriptionService.isSubscribed(chatID)) {
            response.setText("Сначала нужно подписаться!");
            return;
        }

        if (args.length == 0) {
            showCurrentTags(chatID, response);
            return;
        }

        String action = args[0].toLowerCase();

        switch (action) {
            case "add":
                addTags(chatID, Arrays.copyOfRange(args, 1, args.length), response);
                break;
            case "remove":
                removeTags(chatID, Arrays.copyOfRange(args, 1, args.length), response);
                break;
            case "clear":
                clearTags(chatID, response);
                break;
            case "list":
                showCurrentTags(chatID, response);
                break;
            default:
                break;
        }
    }

    private void showCurrentTags(Long chatID, SendMessage response) {
        Set<String> tags = subscriptionService.getTags(chatID);

        StringBuilder message = new StringBuilder();
        message.append("*Ваши текущие теги:* \n");

        if (tags.isEmpty()) {
            message.append("В данный момент вы не подписаны ни на один тег. :(\n");
        } else {
            for (String tag : tags) {
                message.append("• ").append(tag).append("\n");
            }
        }

        message.append("\n*Примеры использования:* \n");
        message.append("`/tag add \"lord of the mysteries\" ersatz` - добавить теги;\n");
        message.append("`/tag add ersatz` - добавить теги без пробелов;\n");
        message.append("`/tag remove \"lord of the mysteries\"` - убрать один определённый тег;\n");
        message.append("`/tag clear` - очистить все теги;\n");
        message.append("\n*Примечание:* Для тегов с пробелами используйте кавычки: `/tag add \"lord of the mysteries\"`. Для однословных тегов можно не использовать кавычки. Без тегов вы не будете получать посты.");

        response.setText(message.toString());
        response.setParseMode("Markdown");
    }

    private void addTags(Long chatID, String[] tagNames, SendMessage response) {
        if(!subscriptionService.isSubscribed(chatID)) {
            response.setText("Сначала нужно подписаться!");
            return;
        }

        if (tagNames.length == 0) {
            response.setText("Укажите теги для добавления в формате `/tag add [тег1] [тег2] ...`.\n" +
                    "Для тегов с пробелами используйте кавычки: `/tag add \"lord of the mysteries\"`");
            response.setParseMode("Markdown");
            return;
        }

        Set<String> currentTags = new HashSet<>(subscriptionService.getTags(chatID));
        Set<String> newTags = new HashSet<>();

        for (String tag : tagNames) {
            String trimmed = tag.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                if (trimmed.length() > MAX_LENGTH) {
                    response.setText("Тег слишком длинный, максимальный тег - 150 символов.");
                    return;
                } else if (!VALID_TAG_PATTERN.matcher(trimmed).matches()) {
                    response.setText("Тег содержит недопустимые символы.");
                    return;
                }
                newTags.add(trimmed);
            }
        }

        if (currentTags.size() + newTags.size() > MAX_TAGS_PER_USER) {
            response.setText("Превышен лимит тегов (" + MAX_TAGS_PER_USER + ")");
            return;
        }

        if (newTags.isEmpty()) {
            response.setText("Не удалось добавить теги. Проверьте правильность ввода.");
            return;
        }

        currentTags.addAll(newTags);

        try {
            subscriptionService.updateTags(chatID, currentTags);
            response.setText("Теги добавлены! Теперь вы будете получать посты по тегам: " +
                    String.join(", ", currentTags));
        } catch (Exception e) {
            BotExceptionHandler.handleException(e, chatID, response);
        }
    }

    private void removeTags(Long chatID, String[] tagNames, SendMessage response) {
        if (tagNames.length == 0) {
            response.setText("Укажите теги для удаления в формате `/tag remove [тег1] [тег2] ...`.\n" +
                    "Для тегов с пробелами используйте кавычки: `/tag remove \"lord of the mysteries\"`");
            response.setParseMode("Markdown");
            return;
        }

        Set<String> currentTags = new HashSet<>(subscriptionService.getTags(chatID));

        if (currentTags.isEmpty()) {
            response.setText("У вас нет тегов для удаления.");
            return;
        }

        Set<String> removed = new HashSet<>();

        for (String tag : tagNames) {
            String trimmed = tag.trim().toLowerCase();
            if (currentTags.remove(trimmed)) {
                removed.add(trimmed);
            }
        }

        try {
            subscriptionService.updateTags(chatID, currentTags);

            StringBuilder result = new StringBuilder();

            if (!removed.isEmpty()) {
                result.append("Теги удалены: ").append(String.join(", ", removed)).append("\n");
            } else {
                result.append("Ни один тег не был удалён.\n");
            }

            if (currentTags.isEmpty()) {
                result.append("\nТеперь у вас нет тегов. Вы не будете получать посты.");
            } else {
                result.append("\nОстались теги: ").append(String.join(", ", currentTags));
            }

            response.setText(result.toString());
            response.setParseMode("Markdown");
        } catch (Exception e) {
            BotExceptionHandler.handleException(e, chatID, response);
        }
    }

    private void clearTags(Long chatID, SendMessage response) {
        try {
            subscriptionService.updateTags(chatID, new HashSet<>());
            response.setText("Все теги очищены!\n" +
                    "Теперь вы не будете получать посты. Добавьте теги командой `/tag add [теги]`.");
            response.setParseMode("Markdown");
        } catch (Exception e) {
            BotExceptionHandler.handleException(e, chatID, response);
        }
    }

    @Override
    public String getName() {
        return "/tag";
    }
}
