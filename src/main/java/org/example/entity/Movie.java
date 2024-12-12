package org.example.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String title;

    @Column(length = 1000)
    private String description;

    private String genreIds;

    private Double rating;

    @Column(nullable = false, unique = true)
    private Long tmdbId;

    public Movie(Long tmdbId, String title) {
        this.title = title;
        this.tmdbId = tmdbId;
    }
}