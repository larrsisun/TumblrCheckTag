package TelegramBot.TumblrTagTracker.bot.commands;

import TelegramBot.TumblrTagTracker.services.SubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

@Component
public class UnsubscribeCommand implements Command {

    @Autowired
    private SubscriptionService subscriptionService;

    @Override
    public void execute(Long chatID, String[] args, SendMessage response) {
         if(!subscriptionService.isSubscribed(chatID)) {
             response.setText("Вы не были подписаны!");
             return;
         }

         subscriptionService.unsubscribe(chatID);
         response.setText("Вы отписались.");

    }

    @Override
    public String getName() {
        return "/unsubscribe";
    }
}
