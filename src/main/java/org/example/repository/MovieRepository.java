package org.example.repository;

import org.example.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MovieRepository extends JpaRepository<Movie, Long> {
    Optional<Movie> findByMovieId(Long movieId); // Поиск фильма по идентификатору из TMDb API

    @Query(value = """
    SELECT m.* FROM movie m
        JOIN (
            SELECT id FROM movie
            ORDER BY RAND() LIMIT :limit
        ) AS random_movies ON m.id = random_movies.id;
""", nativeQuery = true)
    List<Movie> findRandomMovies(@Param("limit") int limit);
}
