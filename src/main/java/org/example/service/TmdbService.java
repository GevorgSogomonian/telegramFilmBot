package org.example.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
public class TmdbService {
    @Value("${spring.tmdb.api.key}")
    private String apiKey;

    @Value("${spring.tmdb.api.url}")
    private String apiUrl;

    private final RestTemplate restTemplate;

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
}
