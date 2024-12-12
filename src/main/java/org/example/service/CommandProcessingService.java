package org.example.service;

import lombok.RequiredArgsConstructor;
import org.example.entity.Movie;
import org.example.entity.UserMovieRating;
import org.example.entity.Usr;
import org.example.repository.MovieRepository;
import org.example.repository.UserMovieRatingRepository;
import org.example.repository.UsrRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class CommandProcessingService {
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
                Map<String, Object> movieData = movies.get(i);
                try {
                    Movie movie = saveOrUpdateMovie(movieData); // Обновляем или сохраняем фильм в базе
                    result.append(String.format(
                            "Название: %s\nОписание: %s\nЖанры: %s\nРейтинг: %s\n\n",
                            movie.getTitle(),
                            truncateDescription(movie.getDescription()),
                            tmdbService.getGenreNames(movie.getGenreIds()), // Добавление жанров
                            movie.getRating() != null ? movie.getRating().toString() : "Нет рейтинга"
                    ));
                } catch (Exception e) {
                    logger.error("Ошибка обработки данных фильма: {}", movieData, e);
                }
            }

            return result.toString().trim();
        }

        return "Фильмы не найдены.";
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

    public String getPersonalRecommendation(String chatId) {
        logger.info("Получение персональной рекомендации для пользователя с chatId: {}", chatId);

        Usr user = usrRepository.findByChatId(Long.parseLong(chatId))
                .orElseThrow(() -> {
                    logger.error("Пользователь с chatId {} не найден!", chatId);
                    return new IllegalArgumentException("Пользователь не найден.");
                });

        logger.info("Пользователь найден: {} ({} {})", user.getUsername(), user.getFirstName(), user.getLastName());

        // Извлекаем предпочтения пользователя
        Map<String, Double> userGenres = getUserGenres(user);
        if (userGenres.isEmpty()) {
            logger.warn("У пользователя с chatId {} отсутствуют оценки фильмов.", chatId);
            return "🤷‍♂️ *У нас нет достаточно данных, чтобы предложить вам рекомендации.*\n\n" +
                    "🎬 *Оцените несколько фильмов, используя команды*:\n" +
                    "🔹 `/rateall` — случайный фильм для оценки.\n" +
                    "🔹 `/ratepopular` — популярный фильм для оценки.";
        }

        List<Movie> allMovies = movieRepository.findAll();
        Collections.shuffle(allMovies);
        List<Movie> movies = allMovies.stream().limit(10).toList();

        if (movies.isEmpty()) {
            logger.warn("В базе данных отсутствуют фильмы для анализа.");
            return "😞 *К сожалению, у нас пока нет фильмов для анализа.* Попробуйте позже!";
        }

        logger.info("Найдено {} фильмов в базе. Отобрано {} фильмов для анализа.", allMovies.size(), movies.size());

        Map<Movie, Double> similarityMap = new HashMap<>();
        for (Movie movie : movies) {
            Map<String, Integer> movieVector = createGenreVector(movie.getGenreIds());
            double similarity = computeCosineSimilarity(userGenres, movieVector);

            // Добавляем только фильмы с ненулевым сходством
            if (similarity > 0) {
                similarityMap.put(movie, similarity);
            }

            logger.debug("Косинусное сходство для фильма '{}' (id: {}): {}", movie.getTitle(), movie.getMovieId(), similarity);
        }

        List<Map.Entry<Movie, Double>> sortedMovies = similarityMap.entrySet()
                .stream()
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                .limit(5)
                .toList();

        if (sortedMovies.isEmpty()) {
            logger.warn("Не удалось подобрать подходящие фильмы для пользователя.");
            return "🤷‍♂️ *К сожалению, мы не смогли подобрать подходящие фильмы для вас.*";
        }

        StringBuilder response = new StringBuilder();
        for (Map.Entry<Movie, Double> entry : sortedMovies) {
            Movie movie = entry.getKey();
            double similarity = entry.getValue();
            response.append(String.format(
                    "🎬 *Название*: %s\n📖 *Описание*: %s\n🎭 *Жанры*: %s\n🤝 *Сходство*: %.2f%%\n---\n",
                    movie.getTitle(),
                    truncateDescription(movie.getDescription()),
                    tmdbService.getGenreNames(movie.getGenreIds()),
                    similarity * 100
            ));
        }

        logger.info("Рекомендация сформирована для пользователя с chatId: {}", chatId);
        return response.toString().trim();
    }

    private String truncateDescription(String description) {
        int maxLength = 500;
        if (description != null && description.length() > maxLength) {
            return description.substring(0, maxLength) + "...";
        }
        return description != null ? description : "Описание недоступно.";
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

    public String getRandomMovie() {
        Map<String, Object> randomMovieData = tmdbService.getRandomMovieFromAll();

        if (randomMovieData != null) {
            Movie movie = saveOrUpdateMovie(randomMovieData);
            return String.format(
                    "🎲 *Случайный фильм для вас:*\n🎬 *Название*: %s\n📝 *Описание*: %s\n🎭 *Жанры*: %s\n⭐ *Рейтинг*: %s",
                    movie.getTitle(),
                    truncateDescription(movie.getDescription()),
                    tmdbService.getGenreNames(movie.getGenreIds()),
                    movie.getRating() != null ? movie.getRating().toString() : "Нет рейтинга"
            );
        }

        return "😔 *К сожалению, не удалось найти случайный фильм.* Попробуйте позже.";
    }

    public Movie getRandomMovieForRating() {
        Map<String, Object> randomMovie = tmdbService.getRandomMovieFromAll();

        if (randomMovie != null) {
            Long movieId = Long.valueOf(randomMovie.getOrDefault("id", 0).toString());
            return movieRepository.findByMovieId(movieId).orElseGet(() -> {
                Movie newMovie = new Movie();
                newMovie.setMovieId(movieId);
                newMovie.setTitle((String) randomMovie.getOrDefault("title", "Нет названия"));
                newMovie.setRating(parseRating(randomMovie.get("vote_average")));
                newMovie.setDescription((String) randomMovie.getOrDefault("overview", "Описание недоступно")); // Сохраняем описание

                // Сохраняем жанры
                StringBuilder stringBuilder = new StringBuilder();
                ((List<Integer>) randomMovie.getOrDefault("genre_ids", Collections.emptyList()))
                        .forEach(genreId -> stringBuilder.append(genreId).append("_"));
                if (stringBuilder.length() > 0) {
                    stringBuilder.deleteCharAt(stringBuilder.length() - 1);
                }
                newMovie.setGenreIds(stringBuilder.toString());

                movieRepository.save(newMovie); // Сохраняем фильм в базу
                return newMovie;
            });
        }

        throw new IllegalArgumentException("Не удалось получить случайный фильм из базы TMDb.");
    }

    public String getAllRatedMovies(String chatId) {
        Long userChatId = Long.parseLong(chatId);

        Usr user = usrRepository.findByChatId(userChatId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден."));

        List<UserMovieRating> ratings = userMovieRatingRepository.findByUserId(user.getId())
                .stream()
                .sorted((r1, r2) -> Long.compare(r2.getId(), r1.getId()))
                .limit(12)
                .toList();

        if (ratings.isEmpty()) {
            return "📝 *Вы пока не оценили ни одного фильма.* Попробуйте команды /ratepopular или /rateall!";
        }

        return ratings.stream()
                .map(rating -> String.format(
                        "🎬 *Название*: %s\n⭐ *Оценка*: %d\n🎭 *Жанры*: %s\n",
                        rating.getMovie().getTitle(),
                        rating.getRating(),
                        tmdbService.getGenreNames(rating.getMovie().getGenreIds())
                ))
                .collect(Collectors.joining("\n---\n"));
    }

    public Movie saveOrUpdateMovie(Map<String, Object> movieData) {
        Long movieId = Long.valueOf(movieData.getOrDefault("id", 0).toString());
        Optional<Movie> existingMovie = movieRepository.findByMovieId(movieId);

        if (existingMovie.isPresent()) {
            // Обновляем существующий фильм
            Movie movie = existingMovie.get();
            movie.setTitle((String) movieData.getOrDefault("title", "Нет названия"));
            movie.setDescription((String) movieData.getOrDefault("overview", "Нет описания"));
            movie.setRating(parseRating(movieData.get("vote_average")));

            // Обновляем жанры
            StringBuilder genreBuilder = new StringBuilder();
            ((List<Integer>) movieData.getOrDefault("genre_ids", Collections.emptyList()))
                    .forEach(genreId -> genreBuilder.append(genreId).append("_"));
            if (genreBuilder.length() > 0) {
                genreBuilder.deleteCharAt(genreBuilder.length() - 1);
            }
            movie.setGenreIds(genreBuilder.toString());

            return movieRepository.save(movie);
        } else {
            // Создаем новый фильм
            Movie newMovie = new Movie();
            newMovie.setMovieId(movieId);
            newMovie.setTitle((String) movieData.getOrDefault("title", "Нет названия"));
            newMovie.setDescription((String) movieData.getOrDefault("overview", "Нет описания"));
            newMovie.setRating(parseRating(movieData.get("vote_average")));

            // Сохраняем жанры
            StringBuilder genreBuilder = new StringBuilder();
            ((List<Integer>) movieData.getOrDefault("genre_ids", Collections.emptyList()))
                    .forEach(genreId -> genreBuilder.append(genreId).append("_"));
            if (genreBuilder.length() > 0) {
                genreBuilder.deleteCharAt(genreBuilder.length() - 1);
            }
            newMovie.setGenreIds(genreBuilder.toString());

            return movieRepository.save(newMovie);
        }
    }

    public String getMostPersonalRecommendation(String chatId) {
        logger.info("Получение самого подходящего фильма для пользователя с chatId: {}", chatId);

        Usr user = usrRepository.findByChatId(Long.parseLong(chatId))
                .orElseThrow(() -> {
                    logger.error("Пользователь с chatId {} не найден!", chatId);
                    return new IllegalArgumentException("Пользователь не найден.");
                });

        logger.info("Пользователь найден: {} ({} {})", user.getUsername(), user.getFirstName(), user.getLastName());

        // Извлекаем предпочтения пользователя
        Map<String, Double> userGenres = getUserGenres(user);
        if (userGenres.isEmpty()) {
            logger.warn("У пользователя с chatId {} отсутствуют оценки фильмов.", chatId);
            return "🤷‍♂️ *У нас нет достаточно данных, чтобы предложить вам рекомендацию.*\n\n" +
                    "🎬 *Оцените несколько фильмов, используя команды*:\n" +
                    "🔹 `/rateall` — случайный фильм для оценки.\n" +
                    "🔹 `/ratepopular` — популярный фильм для оценки.";
        }

        List<Movie> allMovies = movieRepository.findAll();
        if (allMovies.isEmpty()) {
            logger.warn("В базе данных отсутствуют фильмы для анализа.");
            return "😞 *К сожалению, у нас пока нет фильмов для анализа.* Попробуйте позже!";
        }

        Movie bestMatch = null;
        double maxSimilarity = -1;

        for (Movie movie : allMovies) {
            Map<String, Integer> movieVector = createGenreVector(movie.getGenreIds());
            double similarity = computeCosineSimilarity(userGenres, movieVector);

            logger.debug("Косинусное сходство для фильма '{}' (id: {}): {}", movie.getTitle(), movie.getMovieId(), similarity);

            if (similarity > maxSimilarity) {
                maxSimilarity = similarity;
                bestMatch = movie;
            }
        }

        if (bestMatch == null) {
            logger.warn("Не удалось подобрать подходящий фильм для пользователя.");
            return "🤷‍♂️ *К сожалению, мы не смогли подобрать подходящий фильм для вас.*";
        }

        logger.info("Лучший фильм для пользователя: {} (id: {}). Сходство: {}", bestMatch.getTitle(), bestMatch.getMovieId(), maxSimilarity);

        return String.format(
                "🎥 *Самый подходящий вам фильм:*\n\n" +
                        "🎬 *Название*: %s\n📖 *Описание*: %s\n🎭 *Жанры*: %s\n🤝 *Сходство*: %.2f%%",
                bestMatch.getTitle(),
                truncateDescription(bestMatch.getDescription()),
                tmdbService.getGenreNames(bestMatch.getGenreIds()),
                maxSimilarity * 100
        );
    }

    public String getPopularMoviesRandom() {
        Map<String, Object> response = tmdbService.getPopularMovies();

        if (response != null && response.containsKey("results")) {
            List<Map<String, Object>> movies = (List<Map<String, Object>>) response.get("results");

            // Сохраняем или обновляем фильмы в базе
            movies.forEach(this::saveOrUpdateMovie);

            // Перемешиваем список фильмов
            Collections.shuffle(movies);

            // Формируем ответ
            return movies.stream()
                    .limit(5) // Ограничиваем до 5 фильмов
                    .map(movie -> String.format(
                            "🎬 *Название*: %s\n📖 *Описание*: %s\n🎭 *Жанры*: %s\n⭐ *Рейтинг*: %s\n",
                            movie.get("title"),
                            truncateDescription((String) movie.getOrDefault("overview", "Нет описания")),
                            tmdbService.getGenreNames(getGenreIdsFromMovieData(movie)), // Жанры
                            movie.getOrDefault("vote_average", "Нет рейтинга")
                    ))
                    .collect(Collectors.joining("\n---\n"));
        }

        return "😞 *Популярные фильмы не найдены.* Попробуйте позже! 🌟";
    }

    // Вспомогательный метод для получения жанров
    private String getGenreIdsFromMovieData(Map<String, Object> movieData) {
        List<Integer> genreIds = (List<Integer>) movieData.getOrDefault("genre_ids", Collections.emptyList());
        return genreIds.stream()
                .map(Object::toString)
                .collect(Collectors.joining("_"));
    }
}
