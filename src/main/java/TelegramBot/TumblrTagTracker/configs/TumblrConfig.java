package TelegramBot.TumblrTagTracker.configs;

import com.tumblr.jumblr.JumblrClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TumblrConfig {

    private static final Logger log = LoggerFactory.getLogger(TumblrConfig.class);

    @Value("${tumblr.api.key}")
    private String apiKey;

    @Value("${tumblr.api.secret}")
    private String apiSecret;

    @Bean
    public JumblrClient tumblrClient() {

        try {
            JumblrClient client;

            if (apiSecret != null && !apiSecret.trim().isEmpty()) {
                client = new JumblrClient(apiKey, apiSecret);
            } else {
                client = new JumblrClient();
            }
            log.info("Клиент инициализирован");
            return client;
        } catch (Exception e) {
            log.error("Ошибка при инициализации Tumblr клиента", e);
            throw new RuntimeException("Не могу подключиться к Tumblr API", e);
        }
    }
}
