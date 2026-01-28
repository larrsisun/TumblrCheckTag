package TelegramBot.TumblrTagTracker.configs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;


@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    // Executor для отправки уведомления
    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5); // минимальное количество потоков
        executor.setMaxPoolSize(10); // максимальное количество потоков
        executor.setQueueCapacity(100); // очередь задач
        executor.setThreadNamePrefix("notification-");
        executor.setRejectedExecutionHandler((r, exec) ->
                log.warn("Notification task was rejected, queue is full"));
        executor.initialize();
        return executor;
    }

    // Executor для запросов к Tumblr API
    @Bean(name = "tumblrApiExecutor")
    public Executor tumblrApiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("tumblr-api-");
        executor.setRejectedExecutionHandler((r, exec) ->
                log.warn("Tumblr API was rejected, queue is full"));
        executor.initialize();
        return executor;
    }


}
