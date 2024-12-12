package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
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

    /**
     * Fetches popular movies from TMDb API.
     *
     * @return a map containing popular movies.
     */
    public Map<String, Object> getPopularMovies() {
        String url = buildUrl("movie/popular", null);
        return performApiRequest(url);
    }

    /**
     * Searches for a movie by its title.
     *
     * @param query the movie title to search for.
     * @return a map containing the search results.
     */
    public Map<String, Object> searchMovie(String query) {
        String url = buildUrl("search/movie", Map.of("query", query));
        return performApiRequestWithLogging(url, "searching for movie");
    }

    /**
     * Gets a random movie from TMDb.
     *
     * @return a map containing movie data.
     */
    public Map<String, Object> getRandomMovieFromAll() {
        int randomPage = new Random().nextInt(500) + 1; // TMDb limits to 500 pages
        Map<String, Object> response = fetchMoviesFromPage(randomPage);

        if (response != null && response.containsKey("results")) {
            List<Map<String, Object>> movies = (List<Map<String, Object>>) response.get("results");
            if (!movies.isEmpty()) {
                return movies.get(new Random().nextInt(movies.size()));
            }
        }
        return null;
    }

    /**
     * Fetches genres and caches them locally.
     */
    public void fetchAndCacheGenres() {
        String url = buildUrl("genre/movie/list", null);
        Map<String, Object> response = performApiRequest(url);

        if (response != null && response.containsKey("genres")) {
            List<Map<String, Object>> genres = (List<Map<String, Object>>) response.get("genres");
            genres.forEach(genre -> genreCache.put(
                    (Integer) genre.get("id"),
                    (String) genre.get("name")
            ));
            log.info("Genres cached successfully.");
        } else {
            log.warn("Failed to fetch genres or no genres available in the response.");
        }
    }

    /**
     * Decodes genre IDs into genre names using the cached data.
     *
     * @param genreIds comma-separated string of genre IDs.
     * @return a string of genre names or "Unknown genres" if not found.
     */
    public String getGenreNames(String genreIds) {
        if (genreIds == null || genreIds.isEmpty()) {
            return "Unknown genres";
        }

        return Arrays.stream(genreIds.split(","))
                .map(id -> genreCache.getOrDefault(Integer.parseInt(id.trim()), "Unknown genre"))
                .collect(Collectors.joining(", "));
    }

    /**
     * Retrieves a random popular movie from TMDb.
     *
     * @return a map containing random popular movie data.
     */
    public Map<String, Object> getRandomPopularMovie() {
        Map<String, Object> response = getPopularMovies();

        if (response != null && response.containsKey("results")) {
            List<Map<String, Object>> movies = (List<Map<String, Object>>) response.get("results");
            if (!movies.isEmpty()) {
                return movies.get(new Random().nextInt(movies.size()));
            }
        }
        return null;
    }

    /**
     * Builds a URL for the TMDb API.
     *
     * @param endpoint the API endpoint.
     * @param params   additional query parameters.
     * @return the constructed URL.
     */
    private String buildUrl(String endpoint, Map<String, String> params) {
        StringBuilder urlBuilder = new StringBuilder(String.format("%s/%s?api_key=%s", apiUrl, endpoint, apiKey));

        if (params != null) {
            params.forEach((key, value) -> urlBuilder.append("&").append(key).append("=").append(value));
        }

        return urlBuilder.toString();
    }

    /**
     * Fetches movies from a specific page.
     *
     * @param page the page number to fetch.
     * @return a map containing movie data.
     */
    private Map<String, Object> fetchMoviesFromPage(int page) {
        String url = buildUrl("discover/movie", Map.of("page", String.valueOf(page)));
        return performApiRequest(url);
    }

    /**
     * Performs an API request to the given URL.
     *
     * @param url the API endpoint.
     * @return a map containing the response data.
     */
    private Map<String, Object> performApiRequest(String url) {
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                log.warn("API request failed with status: {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Exception occurred during API request: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * Performs an API request with logging.
     *
     * @param url         the API endpoint.
     * @param description the description of the operation.
     * @return a map containing the response data.
     */
    private Map<String, Object> performApiRequestWithLogging(String url, String description) {
        log.info("Performing {} via TMDb API: {}", description, url);
        return performApiRequest(url);
    }
}
