package org.example.repository;

import org.example.entity.UserMovieRating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserMovieRatingRepository extends JpaRepository<UserMovieRating, Long> {

    List<UserMovieRating> findByUserId(Long userId);

    Optional<UserMovieRating> findByUserIdAndMovieId(Long userId, Long movieId);
}
