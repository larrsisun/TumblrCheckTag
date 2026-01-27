package TelegramBot.TumblrTagTracker.bot.commands;


import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

@Component
public class HelpCommand implements Command {

    @Override
    public void execute(Long chatID, String[] args, SendMessage response) {
        response.setText("""
            
            *Основные команды:*
            `/start` - начать работу с ботом;
            `/subscribe` - подписаться на рассылку;
            /unsubscribe - отписаться от рассылки.
            
            *Управление тегами:*
            `/tag` - просмотр текущих тегов;
            `/tag add` - добавить теги для поиска постов;
            `/tag remove` - удалить определённые теги;
            `/tag clear` - очистить все теги сразу;
            `/tag list` - показать текущие теги;
            
            *Примеры использования тегов:*
            • `/tag add "lord of the mysteries" ersatz` - получать посты с тегами "lord of the mysteries" и ersatz (используйте кавычки для тегов с пробелами, для однословных тегов можно не использовать кавычки);
            • `/tag remove "lord of the mysteries"` - перестать получать посты с тегом "lord of the mysteries";
            • `/tag clear` - очистить все теги сразу.
            
            *Справка:*
            • Вы не будете получать посты от этого бота, пока не добавите хотя бы один тег
            • Бот проверяет новые посты каждые 10 минут, интервал между их отправкой - минута.
            
            *Проблемы?*
            Если что-то не работает, попробуйте:
            1. Переподписаться `/unsubscribe`, затем снова `/subscribe`.
            2. Проверить теги `/tag`.
           
            """);
    }

    @Override
    public String getName() {
        return "/help";
    }
}
