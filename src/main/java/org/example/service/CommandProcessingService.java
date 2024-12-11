package org.example.service;

import lombok.RequiredArgsConstructor;
import org.example.entity.Movie;
import org.example.repository.MovieRepository;
import org.example.service.Json.JsonProcessing;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommandProcessingService {
    private final JsonProcessing jsonProcessing;
    private final TmdbService tmdbService;
    private final MovieRepository movieRepository;

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
            return newMovie;
        });
    }

}
