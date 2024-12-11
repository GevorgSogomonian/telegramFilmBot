package org.example.repository;

import org.example.entity.UserMovieRating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserMovieRatingRepository extends JpaRepository<UserMovieRating, Long> {
    List<UserMovieRating> findByUserId(Long userId); // Поиск записей по userId
}