package org.example.service;

import lombok.RequiredArgsConstructor;
import org.example.entity.Movie;
import org.example.entity.UserMovieRating;
import org.example.entity.Usr;
import org.example.repository.MovieRepository;
import org.example.repository.UserMovieRatingRepository;
import org.example.repository.UsrRepository;
import org.example.service.Json.JsonProcessing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class CommandProcessingService {
    private final JsonProcessing jsonProcessing;
    private final TmdbService tmdbService;
    private final MovieRepository movieRepository;
    private final UsrRepository usrRepository;
    private static final Logger logger = LoggerFactory.getLogger(CommandProcessingService.class);
    private final UserMovieRatingRepository userMovieRatingRepository;

    // Метод для обработки команды /search
    public String searchMovie(String query) {
        Map<String, Object> response = tmdbService.searchMovie(query);

        if (response != null && response.containsKey("results")) {
            List<Map<String, Object>> movies = (List<Map<String, Object>>) response.get("results");

            movies.sort((movie1, movie2) -> {
                Double rating1 = parseRating(movie1.get("vote_average"));
                Double rating2 = parseRating(movie2.get("vote_average"));
                return rating2.compareTo(rating1);
            });

            StringBuilder result = new StringBuilder();
            for (int i = 0; i < Math.min(5, movies.size()); i++) {
                Map<String, Object> movie = movies.get(i);
                String title = (String) movie.getOrDefault("title", "Нет названия");
                String overview = (String) movie.getOrDefault("overview", "Нет описания");
                String rating = String.valueOf(movie.getOrDefault("vote_average", "Нет рейтинга"));

                result.append(String.format("Название: %s\nОписание: %s\nРейтинг: %s\n\n", title, overview, rating));
            }

            return result.toString().trim();
        }

        return "Фильмы не найдены.";
    }

    // Метод для обработки команды /popular
    public String getPopularMoviesRandom() {
        Map<String, Object> response = tmdbService.getPopularMovies();

        if (response != null && response.containsKey("results")) {
            List<Map<String, Object>> movies = (List<Map<String, Object>>) response.get("results");
            Collections.shuffle(movies);

            return movies.stream()
                    .limit(5)
                    .map(movie -> String.format("Название: %s\nОписание: %s\nРейтинг: %s\n",
                            movie.get("title"),
                            movie.getOrDefault("overview", "Нет описания"),
                            movie.getOrDefault("vote_average", "Нет рейтинга")))
                    .collect(Collectors.joining("\n---\n"));
        }

        return "Популярные фильмы не найдены.";
    }

    // Метод для обработки команды /random
    public String getRandomMovie() {
        Map<String, Object> response = tmdbService.getPopularMovies();

        if (response != null && response.containsKey("results")) {
            List<Map<String, Object>> movies = (List<Map<String, Object>>) response.get("results");

            List<Map<String, Object>> filteredMovies = movies.stream()
                    .filter(movie -> parseRating(movie.get("vote_average")) > 6.0)
                    .collect(Collectors.toList());

            if (!filteredMovies.isEmpty()) {
                Map<String, Object> randomMovie = filteredMovies.get(new Random().nextInt(filteredMovies.size()));
                String title = (String) randomMovie.getOrDefault("title", "Нет названия");
                String overview = (String) randomMovie.getOrDefault("overview", "Нет описания");
                String rating = String.valueOf(randomMovie.getOrDefault("vote_average", "Нет рейтинга"));

                return String.format("Название: %s\n Описание: %s\n Рейтинг: %s", title, overview, rating);
            }
        }

        return "К сожалению, не удалось найти фильм с рейтингом выше 6.";
    }

    // Утилитарный метод для парсинга рейтинга
    private Double parseRating(Object ratingObj) {
        if (ratingObj == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(ratingObj.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public Movie getOrCreateMovie() {
        Map<String, Object> response = tmdbService.getPopularMovies();
        Map<String, Object> randomMovi = new LinkedHashMap<>();

        if (response != null && response.containsKey("results")) {
            List<Map<String, Object>> movies = (List<Map<String, Object>>) response.get("results");
            if (!movies.isEmpty()) {
                randomMovi = movies.get(new Random().nextInt(movies.size()));
            }
        }
        final Map<String, Object> randomMovie = randomMovi;

        Long movieId = Long.valueOf(randomMovie.getOrDefault("id", 0).toString()); // Предполагается метод для извлечения ID
        return movieRepository.findByMovieId(movieId).orElseGet(() -> {
            Movie newMovie = new Movie();
            newMovie.setMovieId(movieId);
            newMovie.setTitle((String) randomMovie.getOrDefault("title", "Нет названия")); // Метод для извлечения названия
            newMovie.setRating((Double) randomMovie.getOrDefault("vote_average", "Нет рейтинга")); // Метод для рейтинга
//            List<Integer> genre_ids = (List<Integer>) randomMovie.getOrDefault("genre_ids", "Нет названия");
            StringBuilder stringBuilder = new StringBuilder();
            ((List<Integer>) randomMovie.getOrDefault("genre_ids", "Нет названия"))
                    .forEach(genre_id -> stringBuilder.append(genre_id).append("_"));
            stringBuilder.deleteCharAt(stringBuilder.length() - 1);
            newMovie.setGenreIds(stringBuilder.toString());
            return newMovie;
        });
    }

    public String getPersonalRecommendation(String chatId) {
        logger.info("Получение персональной рекомендации для пользователя с chatId: {}", chatId);

        Usr user = usrRepository.findByChatId(Long.parseLong(chatId))
                .orElseThrow(() -> {
                    logger.error("Пользователь с chatId {} не найден!", chatId);
                    return new IllegalArgumentException("Пользователь не найден.");
                });

        logger.info("Пользователь найден: {} ({} {})", user.getUsername(), user.getFirstName(), user.getLastName());

        List<Movie> allMovies = movieRepository.findAll();
        Collections.shuffle(allMovies); // Перемешиваем фильмы
        List<Movie> movies = allMovies.stream().limit(10).toList();

        logger.info("Найдено {} фильмов в базе. Отобрано {} фильмов для анализа.", allMovies.size(), movies.size());

        if (movies.isEmpty()) {
            logger.warn("В базе данных отсутствуют фильмы для рекомендаций.");
            return "У нас пока нет фильмов для рекомендаций. Попробуйте позже!";
        }

        Map<String, Double> userGenres = getUserGenres(user);
        logger.info("Вектор жанров пользователя: {}", userGenres);

        Movie bestMatch = findBestMatch(userGenres, movies);

        if (bestMatch == null) {
            logger.warn("Не удалось подобрать подходящий фильм для пользователя.");
            return "К сожалению, мы не смогли подобрать подходящий фильм для вас.";
        }

        // Расчет косинусного сходства для лучшего фильма
        Map<String, Integer> bestMovieVector = createGenreVector(bestMatch.getGenreIds());
        double similarity = computeCosineSimilarity(userGenres, bestMovieVector);

        logger.info("Лучший фильм для пользователя: {} (id: {}). Сходство: {}", bestMatch.getTitle(), bestMatch.getMovieId(), similarity);

        return String.format(
                "🎥 Рекомендуем вам фильм: %s\nОписание: %s\n\nСходство с вашими предпочтениями: %.2f%%",
                bestMatch.getTitle(),
                bestMatch.getDescription(),
                similarity * 100
        );
    }



    private Map<String, Double> getUserGenres(Usr user) {
        // Извлекаем все записи рейтингов для данного пользователя
        List<UserMovieRating> ratings = userMovieRatingRepository.findByUserId(user.getId());

        // Создаем карту для накопления веса жанров
        Map<String, Double> genreWeights = new HashMap<>();

        for (UserMovieRating rating : ratings) {
            int userRating = rating.getRating(); // Рейтинг пользователя
            String[] genres = rating.getMovie().getGenreIds().split("_"); // Жанры фильма

            // Добавляем вес к каждому жанру
            for (String genre : genres) {
                genreWeights.put(genre, genreWeights.getOrDefault(genre, 0.0) + userRating);
            }
        }

        return genreWeights; // Возвращаем карту жанров и их весов
    }




    private Movie findBestMatch(Map<String, Double> userGenres, List<Movie> movies) {
        if (userGenres.isEmpty()) {
            logger.warn("У пользователя отсутствуют предпочтения по жанрам.");
            return null;
        }

        // Лучший фильм и максимальное сходство
        Movie bestMatch = null;
        double maxSimilarity = -1;

        for (Movie movie : movies) {
            Map<String, Integer> movieVector = createGenreVector(movie.getGenreIds()); // Создаем вектор жанров фильма
            double similarity = computeCosineSimilarity(userGenres, movieVector);

            logger.debug("Косинусное сходство для фильма '{}' (id: {}): {}", movie.getTitle(), movie.getMovieId(), similarity);

            if (similarity > maxSimilarity) {
                maxSimilarity = similarity;
                bestMatch = movie;
            }
        }

        logger.info("Максимальное сходство: {}. Лучший фильм: {}", maxSimilarity, bestMatch != null ? bestMatch.getTitle() : "нет подходящего фильма");
        return bestMatch;
    }


    private Map<String, Integer> createGenreVector(String genreIds) {
        Map<String, Integer> vector = new HashMap<>();
        for (String genreId : genreIds.split("_")) {
            vector.put(genreId, vector.getOrDefault(genreId, 0) + 1);
        }
        logger.debug("Создан вектор жанров: {}", vector);
        return vector;
    }

    private double computeCosineSimilarity(Map<String, Double> vectorA, Map<String, Integer> vectorB) {
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(vectorA.keySet());
        allKeys.addAll(vectorB.keySet());

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (String key : allKeys) {
            double valueA = vectorA.getOrDefault(key, 0.0);
            double valueB = vectorB.getOrDefault(key, 0);

            dotProduct += valueA * valueB;
            normA += Math.pow(valueA, 2);
            normB += Math.pow(valueB, 2);

            logger.debug("Жанр: {}, Значение вектора A: {}, Значение вектора B: {}, Текущий dotProduct: {}", key, valueA, valueB, dotProduct);
        }

        logger.debug("Норма A: {}, Норма B: {}, Итоговый dotProduct: {}", Math.sqrt(normA), Math.sqrt(normB), dotProduct);

        if (normA == 0 || normB == 0) {
            logger.warn("Один из векторов пуст. Косинусное сходство равно 0.");
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
