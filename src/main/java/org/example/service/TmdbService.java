package org.example.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final Map<Integer, String> genreCache = new HashMap<>();

    public TmdbService() {
        this.restTemplate = new RestTemplate();
    }

    //Получить список популярных фильмов.
    public Map<String, Object> getPopularMovies() {
        String url = String.format("%s/movie/popular?api_key=%s", apiUrl, apiKey);
        return restTemplate.getForObject(url, Map.class);
    }

    //Поиск фильма по названию.
    public Map<String, Object> searchMovie(String query) {
        try {
            String url = String.format("%s/search/movie?api_key=%s&query=%s", apiUrl, apiKey, query);
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
                genres.forEach(genre -> {
                    genreCache.put(
                            (Integer) genre.get("id"),
                            (String) genre.get("name")
                    );
                });
                log.info("Закэшированные жанры: {}", genreCache);
            } else {
                log.warn("Ответ от TMDb API не содержит ключа 'genres'.");
            }
        } catch (Exception e) {
            log.error("Ошибка при получении списка жанров: {}", e.getMessage(), e);
        }
    }

    // Метод для расшифровки жанров
    public String getGenreNames(String genreIds) {
        if (genreIds == null || genreIds.isEmpty()) {
            return "Жанры неизвестны";
        }

        // Если кэш пустой, повторно загружаем жанры
        if (genreCache.isEmpty()) {
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
                .map(genreId -> genreCache.getOrDefault(Integer.parseInt(genreId), "Неизвестный жанр"))
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

    @PostConstruct
    public void init() {
        fetchAndCacheGenres();
        log.info("Жанры успешно загружены и закэшированы: {}", genreCache);
    }
}
