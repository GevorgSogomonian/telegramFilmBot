package org.example.controller;

import org.example.service.TmdbService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class MovieController {
    private final TmdbService tmdbService;

    public MovieController(TmdbService tmdbService) {
        this.tmdbService = tmdbService;
    }

    @GetMapping("/movies/popular")
    public Map<String, Object> getPopularMovies() {
        return tmdbService.getPopularMovies();
    }

    @GetMapping("/movies/search")
    public Map<String, Object> searchMovie(@RequestParam String query) {
        return tmdbService.searchMovie(query);
    }
}
