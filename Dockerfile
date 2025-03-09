# Используем официальный образ с Gradle для сборки
FROM gradle:7.6-jdk17 AS builder

# Устанавливаем рабочую директорию
WORKDIR /app

# Копируем файл с зависимостями (например, build.gradle)
COPY build.gradle.kts settings.gradle.kts /app/

# Копируем исходные файлы проекта
COPY src /app/src

# Строим проект с помощью Gradle
RUN gradle build --no-daemon

# Используем образ с JDK 17 для запуска приложения
FROM openjdk:17-jdk-slim

# Устанавливаем рабочую директорию
WORKDIR /app

# Копируем JAR файл, собранный в предыдущем этапе
COPY --from=builder /app/build/libs/*.jar /app/app.jar

# Указываем команду запуска
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# Открываем нужный порт для приложения (если он используется)
EXPOSE 8080