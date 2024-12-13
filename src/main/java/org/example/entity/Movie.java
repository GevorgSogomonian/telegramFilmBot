package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private Long movieId; // Идентификатор фильма из TMDb API

    private String title; // Название фильма
    @Column(name = "description", columnDefinition = "TEXT") // Или "character varying(5000)"
    private String description;
    private String releaseDate;
    private Double rating; // Средний рейтинг из TMDb API
    private String genreIds; // Ids жанра
}