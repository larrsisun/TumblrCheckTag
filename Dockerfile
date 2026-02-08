# Multi-stage build для оптимизации размера образа
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /app

# Копируем только pom.xml сначала для кеширования зависимостей
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Копируем исходный код и собираем приложение
COPY src ./src
RUN mvn clean package -DskipTests

# Production stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Создаем пользователя для безопасности
RUN addgroup -g 1001 -S appuser && \
    adduser -u 1001 -S appuser -G appuser

# Копируем собранный JAR из build stage
COPY --from=build /app/target/*.jar app.jar

# Создаем startup скрипт для конвертации DATABASE_URL
RUN echo '#!/bin/sh' > /app/start.sh && \
    echo 'if [ -n "$DATABASE_URL" ]; then' >> /app/start.sh && \
    echo '  # Convert mysql:// to jdbc:mysql://' >> /app/start.sh && \
    echo '  if echo "$DATABASE_URL" | grep -q "^mysql://"; then' >> /app/start.sh && \
    echo '    export DATABASE_URL="jdbc:${DATABASE_URL}"' >> /app/start.sh && \
    echo '    echo "Converted DATABASE_URL to JDBC format"' >> /app/start.sh && \
    echo '  fi' >> /app/start.sh && \
    echo 'fi' >> /app/start.sh && \
    echo 'exec java -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom -jar app.jar "$@"' >> /app/start.sh && \
    chmod +x /app/start.sh

# Меняем владельца файлов
RUN chown -R appuser:appuser /app

# Переключаемся на непривилегированного пользователя
USER appuser

# Запуск через наш скрипт
ENTRYPOINT ["/app/start.sh"]
