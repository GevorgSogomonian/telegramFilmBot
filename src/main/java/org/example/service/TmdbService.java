package org.example.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TmdbService {
    @Value("${spring.tmdb.api.key}")
    private String apiKey;

    @Value("${spring.tmdb.api.url}")
    private String apiUrl;

    private final RestTemplate restTemplate;
    private final Map<Integer, String> genresCache = new HashMap<>();
    private final List<Map<String, Object>> popularMoviesCache = new ArrayList<>();

    public TmdbService() {
        this.restTemplate = new RestTemplate();
    }

    public Map<String, Object> getPopularMovies(Integer page) {
//        String url1 = String.format("%s/movie/popular?api_key=%s&language=ru&page=%s", apiUrl, apiKey, page.toString());
//        String url = String.format("%s/trending/all/week?api_key=%s&language=ru", apiUrl, apiKey);
        String url1 = String.format("%s/movie/top_rated?api_key=%s&language=ru&page=%s", apiUrl, apiKey, page.toString());

        return restTemplate.getForObject(url1, Map.class);
    }

    public Map<String, Object> searchMovie(String query) {
        try {
            String url = String.format("%s/search/movie?api_key=%s&query=%s&language=ru", apiUrl, apiKey, query);
            log.info("Выполняется запрос к TMDb API: {}", url);

            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response != null && response.containsKey("results")) {
                log.info("Запрос к TMDb выполнен успешно. Найдено результатов: {}",
                        ((List<?>) response.get("results")).size());
                return response;
            } else {
                log.warn("Ответ TMDb API не содержит ключ 'results'.");
            }
        } catch (Exception e) {
            log.error("Ошибка при запросе к TMDb API для поиска фильма: {}", e.getMessage(), e);
        }
        return null;
    }

    public Map<String, Object> getRandomMovieFromAll() {
        int randomPage = new Random().nextInt(500) + 1;
        Map<String, Object> response = fetchMoviesFromAllPages(randomPage);

        if (response != null && response.containsKey("results")) {
            List<Map<String, Object>> movies = (List<Map<String, Object>>) response.get("results");

            if (!movies.isEmpty()) {
                return movies.get(new Random().nextInt(movies.size()));
            }
        }

        return null;
    }

    private Map<String, Object> fetchMoviesFromAllPages(int page) {
        String url = String.format("https://api.themoviedb.org/3/discover/movie?api_key=%s&page=%d&language=ru", apiKey, page);
        return performApiRequest(url);
    }

    public Map<String, Object> performApiRequest(String url) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                System.err.println("Ошибка при выполнении API-запроса: " + response.getStatusCode());
            }
        } catch (Exception e) {
            System.err.println("Исключение при выполнении API-запроса: " + e.getMessage());
        }

        return null;
    }

    public String getGenreNames(String genreIds) {
        if (genreIds == null || genreIds.isEmpty()) {
            return "Жанры неизвестны";
        }

        if (genresCache.isEmpty()) {
            fetchAndCacheGenres();
        }

        return Arrays.stream(genreIds.split("_"))
                .filter(genreId -> {
                    try {
                        Integer.parseInt(genreId);
                        return true;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
                .map(genreId -> genresCache.getOrDefault(Integer.parseInt(genreId), "Неизвестный жанр"))
                .collect(Collectors.joining(", "));
    }

    public Map<String, Object> getRandomPopularMovie() {
        return popularMoviesCache.get(new Random().nextInt(popularMoviesCache.size()));
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void fetchAndCacheGenres() {
        String url = String.format("%s/genre/movie/list?api_key=%s&language=ru", apiUrl, apiKey);
        try {
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response != null && response.containsKey("genres")) {
                List<Map<String, Object>> genres = (List<Map<String, Object>>) response.get("genres");
                genres.forEach(genre -> {
                    genresCache.put(
                            (Integer) genre.get("id"),
                            (String) genre.get("name")
                    );
                });
                log.info("Закэшированные жанры: {}", genresCache);
            } else {
                log.warn("Ответ от TMDb API не содержит ключа 'genres'.");
            }
        } catch (Exception e) {
            log.error("Ошибка при получении списка жанров: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void cachePopularMovies() {
        for (int i = 1; i <= 10; i++) {
            Map<String, Object> response = getPopularMovies(i);

            if (response != null && response.containsKey("results")) {
                popularMoviesCache.addAll((List<Map<String, Object>>) response.get("results"));
            }
        }
    }

    @PostConstruct
    public void init() {
        fetchAndCacheGenres();
        log.info("Жанры успешно загружены и закэшированы: {}", genresCache);
        cachePopularMovies();
        log.info("Популярные фильмы успешно загружены и закэшированы: {}", popularMoviesCache);
    }
}
