package org.example.util;

import org.example.entity.Movie;
import org.springframework.stereotype.Service;

@Service
public class MessageFormatter {

    public static String formatMovieForRating(Movie movie) {
        return String.format(
                "🎥 *Мы предлагаем вам фильм:*\n" +
                        "🎬 *Название*: %s\n📖 *Описание*: %s\n🎭 *Жанры*: %s\n⭐ *Рейтинг*: %s\n\n" +
                        "❓ *Вы уже видели этот фильм?* Ответьте 'да' или 'нет'.",
                movie.getTitle(),
                truncateDescription(movie.getDescription()),
                movie.getGenres(), //Заменить
                movie.getRating() != null ? movie.getRating() : "Нет рейтинга"
        );
    }

    public static String truncateDescription(String description) {
        int maxLength = 500;
        if (description != null && description.length() > maxLength) {
            return description.substring(0, maxLength) + "...";
        }
        return description != null ? description : "Описание недоступно.";
    }

    public static String getHelpMessage() {
        return """
        🐾 *Добро пожаловать в вашего личного помощника по фильмам!* 🎥✨
        
        _Вот список доступных команд:_
        
        🔍 `/search` — Найти фильм по названию.
        🌟 `/popular` — Список популярных фильмов.
        🎲 `/random` — Случайный фильм.
        ❤️ `/personal` — Персональные рекомендации.
        🏆 `/mostpersonal` — Самый подходящий фильм.
        🎬 `/ratepopular` — Оцените популярный фильм.
        🌀 `/rateall` — Оцените случайный фильм.
        📜 `/allrated` — Посмотрите ваши оценки.
        
        🧡 Спасибо, что пользуетесь ботом! 😊
        """;
    }
}