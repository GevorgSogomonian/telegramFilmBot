package org.example.repository;

import org.example.entity.UserMovieRating;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMovieRatingRepository extends JpaRepository<UserMovieRating, Long> {
}