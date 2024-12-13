package org.example.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Entity
@Data
public class Usr {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private Long chatId; // Идентификатор чата (уникальный)

    private String username; // Никнейм пользователя
    private String firstName; // Имя пользователя
    private String lastName; // Фамилия пользователя
    private String languageCode; // Код языка пользователя (например, "ru", "en")
    private Boolean isPremium; // Информация о премиум-аккаунте
    private Boolean isBot; // Является ли пользователь ботом
    private String genrePreferences;

    // Оцененные фильмы
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<UserMovieRating> ratings = new HashSet<>();
}