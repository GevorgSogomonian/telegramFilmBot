package org.example.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

@Service
public class TmdbService {
    @Value("${spring.tmdb.api.key}")
    private String apiKey;

    @Value("${spring.tmdb.api.url}")
    private String apiUrl;

    private final RestTemplate restTemplate;

    private final Map<Integer, String> genreCache = new HashMap<>();

    public TmdbService() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * Получить список популярных фильмов.
     */
    public Map<String, Object> getPopularMovies() {
        String url = String.format("%s/movie/popular?api_key=%s", apiUrl, apiKey);
        return restTemplate.getForObject(url, Map.class);
    }

    /**
     * Поиск фильма по названию.
     */
    public Map<String, Object> searchMovie(String query) {
        String url = String.format("%s/search/movie?api_key=%s&query=%s", apiUrl, apiKey, query);
        return restTemplate.getForObject(url, Map.class);
    }

    public Map<String, Object> getRandomMovieFromAll() {
        // Выбираем случайную страницу из диапазона (TMDb ограничивает количество страниц)
        int randomPage = new Random().nextInt(500) + 1; // Например, до 500 страниц
        Map<String, Object> response = fetchMoviesFromAllPages(randomPage);

        if (response != null && response.containsKey("results")) {
            List<Map<String, Object>> movies = (List<Map<String, Object>>) response.get("results");

            if (!movies.isEmpty()) {
                // Возвращаем случайный фильм с данной страницы
                return movies.get(new Random().nextInt(movies.size()));
            }
        }

        return null; // Если ничего не найдено
    }

    private Map<String, Object> fetchMoviesFromAllPages(int page) {
        String url = String.format("https://api.themoviedb.org/3/discover/movie?api_key=%s&page=%d", apiKey, page);
        return performApiRequest(url);
    }

    // Выполнение HTTP-запроса к API
    public Map<String, Object> performApiRequest(String url) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody(); // Возвращаем тело ответа в виде карты
            } else {
                System.err.println("Ошибка при выполнении API-запроса: " + response.getStatusCode());
            }
        } catch (Exception e) {
            System.err.println("Исключение при выполнении API-запроса: " + e.getMessage());
        }

        return null; // Возвращаем null в случае ошибки
    }

    // Получение списка жанров и кэширование
    public void fetchAndCacheGenres() {
        String url = String.format("%s/genre/movie/list?api_key=%s", apiUrl, apiKey);
        try {
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response != null && response.containsKey("genres")) {
                List<Map<String, Object>> genres = (List<Map<String, Object>>) response.get("genres");
                genres.forEach(genre -> genreCache.put(
                        (Integer) genre.get("id"),
                        (String) genre.get("name")
                ));
            }
        } catch (Exception e) {
            System.err.println("Ошибка при получении списка жанров: " + e.getMessage());
        }
    }

    // Метод для расшифровки жанров
    public String getGenreNames(String genreIds) {
        if (genreCache.isEmpty()) {
            fetchAndCacheGenres(); // Запрашиваем жанры, если кэш пуст
        }

        return Arrays.stream(genreIds.split("_"))
                .map(id -> genreCache.getOrDefault(Integer.parseInt(id), "Неизвестный жанр"))
                .collect(Collectors.joining(", "));
    }

    public Map<String, Object> getRandomPopularMovie() {
        Map<String, Object> response = getPopularMovies();

        if (response != null && response.containsKey("results")) {
            List<Map<String, Object>> movies = (List<Map<String, Object>>) response.get("results");

            if (!movies.isEmpty()) {
                // Возвращаем случайный фильм из популярных
                return movies.get(new Random().nextInt(movies.size()));
            }
        }

        return null; // Если ничего не найдено
    }
}
