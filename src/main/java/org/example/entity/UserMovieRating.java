package org.example.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Data;

@Entity
@Data
public class UserMovieRating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private Usr user; // Пользователь, который оценил фильм

    @ManyToOne
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie; // Ссылка на фильм

    private int rating; // Рейтинг, выставленный пользователем
}