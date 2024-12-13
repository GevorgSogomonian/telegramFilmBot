package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.telegram.telegrambots.meta.api.objects.Update;

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
    private final UserMovieRatingRepository userMovieRatingRepository;

    private static final Logger logger = LoggerFactory.getLogger(CommandProcessingService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public String searchMovie(Update update) {
        Long chatId = update.getMessage().getChatId();
        String messageText = update.getMessage().getText().trim();
        Map<String, Object> response = tmdbService.searchMovie(messageText);
        Usr user = usrRepository.findByChatId(chatId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден."));

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
                    Movie movie = saveOrUpdateMovie(movieData);
                    double similarity = computeCosineSimilarity(getUserGenres(user), createGenreVector(movie.getGenreIds()));
                    result.append(String.format("""
                            %s
                            🤝 *Сходство:* %s
                            
                            """,
                            movietoString(movie),
                            similarity != 0 ? String.valueOf(similarity * 100).substring(0, 4) + "%": "Не известно")

                    );
                } catch (Exception e) {
                    logger.error("Ошибка обработки данных фильма: {}", movieData, e);
                }
            }

            return result.toString().trim();
        }

        return "Фильмы не найдены.";
    }

    public String movietoString(Movie movie) {
        return String.format(
                """
                        🎬 *Название:* %s
                        📝 *Описание:* %s
                        🎭 *Жанры:* %s
                        📜 *Релиз:* %s
                        ⭐ *Рейтинг:* %s""",
                movie.getTitle(),
                truncateDescription(movie.getDescription()),
                tmdbService.getGenreNames(movie.getGenreIds()),
                movie.getReleaseDate(),
                movie.getRating() != null ? movie.getRating().toString() : "Нет рейтинга"
        );
    }

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

        Map<String, Double> userGenres = getUserGenres(user);
        if (userGenres.isEmpty()) {
            logger.warn("У пользователя с chatId {} отсутствуют оценки фильмов.", chatId);
            return """
                    🤷‍♂️ *У нас нет достаточно данных, чтобы предложить вам рекомендации.*
                    
                    🎬 *Оцените несколько фильмов, используя команды*:
                    Попробуйте эти команды:
                    🎬 *Популярные фильмы*
                    🌀 *Рандомный фильм*""";
        }

        List<Movie> allMovies = movieRepository.findAll();
        Collections.shuffle(allMovies);
        List<Movie> movies = allMovies.stream().limit(10).toList();

        if (movies.isEmpty()) {
            logger.warn("В базе данных отсутствуют фильмы для анализа.");
            return """
                    😞 *К сожалению, у нас пока нет фильмов для анализа.* Попробуйте позже!""";
        }

        logger.info("Найдено {} фильмов в базе. Отобрано {} фильмов для анализа.", allMovies.size(), movies.size());

        Map<Movie, Double> similarityMap = new HashMap<>();
        for (Movie movie : movies) {
            Map<String, Integer> movieVector = createGenreVector(movie.getGenreIds());
            double similarity = computeCosineSimilarity(userGenres, movieVector);

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
            return """
                    🤷‍♂️ *К сожалению, мы не смогли подобрать подходящие фильмы для вас.*
                    🎬 *Оцените несколько фильмов, чтобы улучшить персональные рекомендации.*
                    
                    Попробуйте эти команды:
                    🎬 *Популярные фильмы*
                    🌀 *Рандомный фильм*""";
        }

        StringBuilder response = new StringBuilder();
        for (Map.Entry<Movie, Double> entry : sortedMovies) {
            Movie movie = entry.getKey();
            double similarity = entry.getValue();
            response.append(String.format(
                    """
                            %s
                            🤝 *Сходство:* %s
                            
                            """,
                    movietoString(movie),
                    similarity != 0 ? String.valueOf(similarity * 100).substring(0, 4) + "%" : "Не известно"
            ));
        }

        logger.info("Рекомендация сформирована для пользователя с chatId: {}", chatId);
        return response.toString().trim();
    }

    private String truncateDescription(String description) {
        int maxLength = 2000;
        if (description != null && description.length() > maxLength) {
            return description.substring(0, maxLength) + "...";
        }
        return description != null ? description : "Описание недоступно.";
    }

    public Map<String, Double> getUserGenres(Usr user) {
        List<UserMovieRating> ratings = userMovieRatingRepository.findByUserId(user.getId());

        Map<String, Double> genreWeights = new HashMap<>();

        for (UserMovieRating rating : ratings) {
            int userRating = rating.getRating();
            String[] genres = rating.getMovie().getGenreIds().split("_");

            for (String genre : genres) {
                genreWeights.put(genre, genreWeights.getOrDefault(genre, 0.0) + userRating);
            }
        }

        return genreWeights;
    }

    public static String mapToJson(Map<String, Double> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static Map<String, Double> jsonToMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Double>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, Integer> createGenreVector(String genreIds) {
        Map<String, Integer> vector = new HashMap<>();
        for (String genreId : genreIds.split("_")) {
            vector.put(genreId, vector.getOrDefault(genreId, 0) + 1);
        }
        logger.debug("Создан вектор жанров: {}", vector);
        return vector;
    }

    public double computeCosineSimilarity(Map<String, Double> vectorA, Map<String, Integer> vectorB) {
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

    public Movie getRandomMovieForRating() {
        Map<String, Object> randomMovie = tmdbService.getRandomMovieFromAll();

        if (randomMovie != null) {
            Long movieId = Long.valueOf(randomMovie.getOrDefault("id", 0).toString());
            return movieRepository.findByMovieId(movieId).orElseGet(() -> {
                Movie newMovie = new Movie();
                newMovie.setMovieId(movieId);
                newMovie.setReleaseDate((String) randomMovie.getOrDefault("release_date", "Не известно"));
                newMovie.setTitle((String) randomMovie.getOrDefault("title", "Нет названия"));
                newMovie.setRating(parseRating(randomMovie.get("vote_average")));
                newMovie.setDescription((String) randomMovie.getOrDefault("overview", "Описание недоступно"));

                StringBuilder stringBuilder = new StringBuilder();
                ((List<Integer>) randomMovie.getOrDefault("genre_ids", Collections.emptyList()))
                        .forEach(genreId -> stringBuilder.append(genreId).append("_"));
                if (stringBuilder.length() > 0) {
                    stringBuilder.deleteCharAt(stringBuilder.length() - 1);
                }
                newMovie.setGenreIds(stringBuilder.toString());

                movieRepository.save(newMovie);
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
                .toList();

        if (ratings.isEmpty()) {
            return """
                    📝 *Вы пока не оценили ни одного фильма.*
                    
                    Попробуйте эти команды:
                    🎬 *Популярные фильмы*
                    🌀 *Рандомный фильм*""";
        }

        return ratings.stream()
                .map(rating -> String.format(
                        """
                                🎬 *Название*: %s
                                ⭐ *Оценка*: %d
                                🎭 *Жанры*: %s
                                """,
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
            Movie movie = existingMovie.get();
            movie.setTitle((String) movieData.getOrDefault("title", "Нет названия"));
            movie.setReleaseDate(((String) movieData.getOrDefault("release_date", "Не известно")).replace("-", "."));
            movie.setDescription((String) movieData.getOrDefault("overview", "Нет описания"));
            movie.setRating(parseRating(movieData.get("vote_average")));

            StringBuilder genreBuilder = new StringBuilder();
            ((List<Integer>) movieData.getOrDefault("genre_ids", Collections.emptyList()))
                    .forEach(genreId -> genreBuilder.append(genreId).append("_"));
            if (genreBuilder.length() > 0) {
                genreBuilder.deleteCharAt(genreBuilder.length() - 1);
            }
            movie.setGenreIds(genreBuilder.toString());

            return movieRepository.save(movie);
        } else {
            Movie newMovie = new Movie();
            newMovie.setMovieId(movieId);
            newMovie.setTitle((String) movieData.getOrDefault("title", "Нет названия"));
            newMovie.setReleaseDate(((String) movieData.getOrDefault("release_date", "Не известно")).replace("-", "."));
            newMovie.setDescription((String) movieData.getOrDefault("overview", "Нет описания"));
            newMovie.setRating(parseRating(movieData.get("vote_average")));

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

        Map<String, Double> userGenres = getUserGenres(user);
        if (userGenres.isEmpty()) {
            logger.warn("У пользователя с chatId {} отсутствуют оценки фильмов.", chatId);
            return """
                    🤷‍♂️ *У нас нет достаточно данных, чтобы предложить вам рекомендацию.*
                    
                    🎬 *Оцените несколько фильмов*:
                    Попробуйте эти команды:
                    🎬 *Популярные фильмы*
                    🌀 *Рандомный фильм*""";
        }

        List<Movie> allMovies = movieRepository.findAll();
        if (allMovies.isEmpty()) {
            logger.warn("В базе данных отсутствуют фильмы для анализа.");
            return """
                    😞 *К сожалению, у нас пока нет фильмов для анализа.*
                    Попробуйте позже!""";
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
            return """
                    🤷‍♂️ *К сожалению, мы не смогли подобрать подходящий фильм для вас.*""";
        }

        logger.info("Лучший фильм для пользователя: {} (id: {}). Сходство: {}", bestMatch.getTitle(), bestMatch.getMovieId(), maxSimilarity);

        return String.format(
                """
                        %s
                        🤝 *Сходство:* %s""",
                movietoString(bestMatch),
                maxSimilarity != 0 ? String.valueOf(maxSimilarity * 100).substring(0, 4) + "%" : "Не известно"
        );
    }
}
