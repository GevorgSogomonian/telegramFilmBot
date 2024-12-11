package org.example.repository;

import org.example.entity.Usr;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UsrRepository extends JpaRepository<Usr, Long> {
    Optional<Usr> findByChatId(Long chatId); // Поиск пользователя по идентификатору чата
}