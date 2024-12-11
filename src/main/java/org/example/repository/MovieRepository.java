package org.example.repository;

import org.example.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MovieRepository extends JpaRepository<Movie, Long> {
    Optional<Movie> findByMovieId(Long movieId); // Поиск фильма по идентификатору из TMDb API
}
