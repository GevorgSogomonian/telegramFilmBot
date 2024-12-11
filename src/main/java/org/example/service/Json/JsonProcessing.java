package org.example.service.Json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class JsonProcessing {
    public Long extractMovieIdFromResponse(String randomMovieResponse) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> movieData = objectMapper.readValue(randomMovieResponse, Map.class);
            return Long.valueOf(movieData.getOrDefault("id", 0).toString());
        } catch (Exception e) {
            throw new RuntimeException("Не удалось извлечь id фильма из ответа: " + e.getMessage());
        }
    }

    public String extractTitleFromResponse(String randomMovieResponse) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> movieData = objectMapper.readValue(randomMovieResponse, Map.class);
            return movieData.getOrDefault("title", "Неизвестное название").toString();
        } catch (Exception e) {
            throw new RuntimeException("Не удалось извлечь название фильма из ответа: " + e.getMessage());
        }
    }

    public String extractDescriptionFromResponse(String randomMovieResponse) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> movieData = objectMapper.readValue(randomMovieResponse, Map.class);
            return movieData.getOrDefault("overview", "Описание недоступно").toString();
        } catch (Exception e) {
            throw new RuntimeException("Не удалось извлечь описание фильма из ответа: " + e.getMessage());
        }
    }

    public Double extractRatingFromResponse(String randomMovieResponse) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> movieData = objectMapper.readValue(randomMovieResponse, Map.class);
            return Double.valueOf(movieData.getOrDefault("vote_average", "0.0").toString());
        } catch (Exception e) {
            throw new RuntimeException("Не удалось извлечь рейтинг фильма из ответа: " + e.getMessage());
        }
    }
}
