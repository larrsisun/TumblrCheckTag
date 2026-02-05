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

# Меняем владельца файлов
RUN chown -R appuser:appuser /app

# Переключаемся на непривилегированного пользователя
USER appuser

# Healthcheck
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD pgrep -f 'java.*app.jar' || exit 1

# Запуск приложения
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
