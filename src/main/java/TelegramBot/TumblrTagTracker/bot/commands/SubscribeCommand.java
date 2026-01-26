package TelegramBot.TumblrTagTracker.bot.commands;

import TelegramBot.TumblrTagTracker.services.SubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

@Component
public class SubscribeCommand implements Command {

    @Autowired
    private SubscriptionService subscriptionService;

    @Override
    public void execute(Long chatID, String[] args, SendMessage response) {

        if (subscriptionService.isSubscribed(chatID)) {
            response.setText("Вы уже подписаны.");
            return;
        }

        subscriptionService.subscribe(chatID);
        response.setText("Поздравляю, теперь вы подписаны!");

    }

    @Override
    public String getName() {
        return "/subscribe";
    }
}
