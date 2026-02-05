package TelegramBot.TumblrTagTracker.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "userExecutor")
    public ExecutorService userExecutor() {
        return new ThreadPoolExecutor(
                5,      // 5 постоянных потоков
                20,     // максимум 20 потоков
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100), // Очередь на 100 задач
                new ThreadPoolExecutor.CallerRunsPolicy() // Политика отказа
        );
    }
}
