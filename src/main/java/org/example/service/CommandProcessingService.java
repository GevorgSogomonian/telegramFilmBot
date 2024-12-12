package org.example.service;

import lombok.RequiredArgsConstructor;
import org.example.entity.Movie;
import org.example.repository.MovieRepository;
import org.example.repository.UserMovieRatingRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommandProcessingService {

    private final MovieRepository movieRepository;
    private final UserMovieRatingRepository userMovieRatingRepository;
    private final TmdbService tmdbService;

    public String searchMovie(String query) {
        Map<String, Object> searchResults = tmdbService.searchMovie(query);
        return formatMovies(searchResults, "results", "😔 *Фильмы не найдены.* Попробуйте другой запрос.");
    }

    public String getPopularMoviesRandom() {
        Map<String, Object> popularMovies = tmdbService.getPopularMovies();
        return formatMovies(popularMovies, "results", "😔 *Нет доступных фильмов.*");
    }

    public String getRandomMovie() {
        Map<String, Object> randomMovie = tmdbService.getRandomMovieFromAll();
        return formatSingleMovie(randomMovie);
    }

    public Movie saveOrUpdateMovie(Map<String, Object> movieData) {
        if (movieData == null || movieData.isEmpty()) {
            throw new IllegalArgumentException("Movie data cannot be null or empty");
        }

        Long movieId = ((Number) movieData.get("id")).longValue();
        String title = (String) movieData.get("title");
        String description = (String) movieData.get("overview");
        Double rating = movieData.get("vote_average") instanceof Number
                ? ((Number) movieData.get("vote_average")).doubleValue()
                : null;

        // Convert genre IDs list to the "_" delimited string format
        List<Integer> genreIdList = (List<Integer>) movieData.get("genre_ids");
        String genreIds = genreIdList != null
                ? genreIdList.stream().map(String::valueOf).collect(Collectors.joining("_"))
                : "";

        Movie movie = movieRepository.findById(movieId).orElseGet(() -> new Movie(movieId, title));
        movie.setDescription(description);
        movie.setRating(rating);
        movie.setGenreIds(genreIds);

        return movieRepository.save(movie);
    }

    public String getPersonalRecommendation(String chatId) {
        List<Movie> recommendedMovies = movieRepository.findRecommendationsForUser(chatId);

        // Filter out movies with 0% similarity
        List<Movie> filteredMovies = recommendedMovies.stream()
                .filter(movie -> movie.getSimilarityPercentage() > 0)
                .collect(Collectors.toList());

        return filteredMovies.isEmpty()
                ? "😔 *Нет подходящих рекомендаций.*"
                : formatMoviesWithSimilarity(filteredMovies);
    }

    public Movie getRandomMovieForRating() {
        Map<String, Object> randomMovieData = tmdbService.getRandomMovieFromAll();
        return saveOrUpdateMovie(randomMovieData);
    }

    public String getAllRatedMovies(String chatId) {
        List<Movie> ratedMovies = userMovieRatingRepository.findAllRatedMoviesByUser(chatId);

        return ratedMovies.isEmpty()
                ? "😔 *Вы еще не оценили ни одного фильма.*"
                : formatRatedMovies(ratedMovies);
    }

    public String getMostPersonalRecommendation(String chatId) {
        Optional<Movie> mostPersonalMovie = movieRepository.findMostPersonalRecommendation(chatId);

        return mostPersonalMovie.map(this::formatSingleMovie)
                .orElse("😔 *Нет подходящих фильмов для персональных рекомендаций.*");
    }

    private String formatMovies(Map<String, Object> movieData, String key, String emptyMessage) {
        if (movieData == null || !movieData.containsKey(key)) {
            return emptyMessage;
        }

        List<Map<String, Object>> movies = (List<Map<String, Object>>) movieData.get(key);
        return movies.stream()
                .map(this::formatMovieFromMap)
                .collect(Collectors.joining("\n\n"));
    }

    private String formatSingleMovie(Map<String, Object> movieData) {
        return movieData == null
                ? "😔 *Фильм не найден.*"
                : formatMovieFromMap(movieData);
    }

    private String formatMoviesWithSimilarity(List<Movie> movies) {
        return movies.stream()
                .map(movie -> String.format("🎬 *%s*\n⭐ *Рейтинг*: %.1f\n🔗 *Сходство*: %d%%",
                        movie.getTitle(),
                        Optional.ofNullable(movie.getRating()).orElse(0.0),
                        movie.getSimilarityPercentage()))
                .collect(Collectors.joining("\n\n"));
    }

    private String formatRatedMovies(List<Movie> movies) {
        return movies.stream()
                .map(movie -> String.format("🎬 *%s*\n⭐ *Ваша оценка*: %d",
                        movie.getTitle(),
                        movie.getUserRating()))
                .collect(Collectors.joining("\n\n"));
    }

    private String formatMovieFromMap(Map<String, Object> movieData) {
        return String.format("🎬 *%s*\n📖 *Описание*: %s\n⭐ *Рейтинг*: %.1f",
                movieData.getOrDefault("title", "Неизвестно"),
                movieData.getOrDefault("overview", "Нет описания"),
                movieData.getOrDefault("vote_average", 0.0));
    }
}
