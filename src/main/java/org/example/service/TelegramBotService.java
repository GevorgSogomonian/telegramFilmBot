package org.example.service;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.example.entity.Movie;
import org.example.entity.UserMovieRating;
import org.example.entity.Usr;
import org.example.repository.MovieRepository;
import org.example.repository.UserMovieRatingRepository;
import org.example.repository.UsrRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TelegramBotService extends TelegramLongPollingBot {

    private final Map<String, Movie> activeRatings = new ConcurrentHashMap<>();
    private final UserMovieRatingRepository userMovieRatingRepository;
    private final CommandProcessingService commandProcessingService;
    private final UsrRepository usrRepository;
    private final MovieRepository movieRepository;

    @Value("${spring.telegram.bot.username}")
    private String botUsername;

    @Value("${spring.telegram.bot.token}")
    private String botToken;

    private final Map<String, Consumer<Update>> commandHandlers = new HashMap<>();

    @PostConstruct
    public void init() {
        System.out.println("Username: " + botUsername);
        System.out.println("Token: " + botToken);

        commandHandlers.put("/search", this::handleSearchCommand);
        commandHandlers.put("/popular", this::handlePopularCommand);
        commandHandlers.put("/random", this::handleRandomCommand);
        commandHandlers.put("/rate", this::handleRateCommand);
        commandHandlers.put("/personal", this::handlePersonalCommand);
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String chatIdStr = update.getMessage().getChatId().toString();
            Long chatId = update.getMessage().getChatId();
            String userMessage = update.getMessage().getText();

            // Проверяем, существует ли пользователь в базе
            usrRepository.findByChatId(chatId).ifPresentOrElse(
                    usr -> System.out.println("Пользователь уже зарегистрирован: " + usr.getUsername()),
                    () -> registerNewUser(update) // Регистрация нового пользователя
            );

            if (activeRatings.containsKey(chatId.toString())) {
                handleRatingResponse(update); // Обрабатываем дальнейшее взаимодействие
                return;
            }

            // Обработка команды (если это команда)
            String command = userMessage.split(" ")[0].toLowerCase();
            commandHandlers.getOrDefault(command, this::handleUnknownCommand).accept(update);
        }
    }

    private void handleSearchCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        String query = update.getMessage().getText().replace("/search ", "");
        String result = commandProcessingService.searchMovie(query);
        sendSplitResponse(chatId, result);
    }

    private void handlePopularCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        String result = commandProcessingService.getPopularMoviesRandom();
        sendSplitResponse(chatId, result);
    }

    private void handleRandomCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        String result = commandProcessingService.getRandomMovie();
        sendSplitResponse(chatId, result);
    }

    private void handleRateCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();

        // Получаем случайный фильм
        Movie randomMovie = commandProcessingService.getOrCreateMovie();

        // Сохраняем фильм для дальнейшей оценки
        activeRatings.put(chatId, randomMovie);

        // Отправляем фильм пользователю
        sendResponse(chatId, "Мы предлагаем вам фильм: " + randomMovie.getTitle());
        sendResponse(chatId, "Вы уже видели этот фильм? Ответьте 'да' или 'нет'.");
    }

    private void handlePersonalCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        String result = commandProcessingService.getPersonalRecommendation(chatId);
        sendResponse(chatId, result);
    }

    private void saveUserRating(String chatId, int rating) {
        Long userChatId = Long.parseLong(chatId);
        Usr user = usrRepository.findByChatId(userChatId).orElseThrow(() -> new IllegalArgumentException("Пользователь не найден."));
        Movie movie = activeRatings.get(chatId);

        if (movie == null) {
            sendResponse(chatId, "Фильм для оценки не найден. Попробуйте команду /rate.");
            return;
        }

        // Создаем запись оценки
        UserMovieRating userMovieRating = new UserMovieRating();
        userMovieRating.setUser(user);
        userMovieRating.setMovie(movie);
        userMovieRating.setRating(rating);

        // Сохраняем в базу
        userMovieRatingRepository.save(userMovieRating);

        // Удаляем фильм из активных рейтингов
        activeRatings.remove(chatId);
    }

    private void handleRatingResponse(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        String userResponse = update.getMessage().getText().toLowerCase();

        // Проверяем, есть ли активный фильм для пользователя
        Movie movie = activeRatings.get(chatId);
        if (movie == null) {
            sendResponse(chatId, "У вас нет активного фильма для оценки. Используйте команду /rate, чтобы начать.");
            return;
        }

        if (userResponse.equals("да")) {
            movieRepository.save(activeRatings.get(chatId));

            sendResponse(chatId, "Как бы вы оценили этот фильм по шкале от 1 до 10?");
        } else if (userResponse.equals("нет")) {
            sendResponse(chatId, "Спасибо! Если хотите, попробуйте другой фильм.");
            activeRatings.remove(chatId); // Удаляем из активных рейтингов
        } else {
            try {
                int rating = Integer.parseInt(userResponse);
                if (rating >= 1 && rating <= 10) {
                    saveUserRating(chatId, rating);
                    sendResponse(chatId, "Спасибо за вашу оценку! Вы поставили " + rating + " баллов.");
                } else {
                    sendResponse(chatId, "Пожалуйста, введите число от 1 до 10.");
                }
            } catch (NumberFormatException e) {
                sendResponse(chatId, "Неизвестный ответ. Пожалуйста, напишите 'да', 'нет' или число от 1 до 10.");
            }
        }
    }

    private void handleUnknownCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        sendSplitResponse(chatId, "Неизвестная команда. Попробуйте /search, /popular, /random, /rate или /personal.");
    }

    private void sendSplitResponse(String chatId, String text) {
        int maxMessageLength = 4096;
        for (int i = 0; i < text.length(); i += maxMessageLength) {
            String part = text.substring(i, Math.min(text.length(), i + maxMessageLength));
            sendResponse(chatId, part);
        }
    }

    private void sendResponse(String chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void registerNewUser(Update update) {
        if (update.hasMessage() && update.getMessage().getFrom() != null) {
            Long chatId = update.getMessage().getChatId();
            org.telegram.telegrambots.meta.api.objects.User fromUser = update.getMessage().getFrom();

            // Создаем нового пользователя
            Usr newUser = new Usr();
            newUser.setChatId(chatId);
            newUser.setUsername(fromUser.getUserName());
            newUser.setFirstName(fromUser.getFirstName());
            newUser.setLastName(fromUser.getLastName());
            newUser.setLanguageCode(fromUser.getLanguageCode());
            newUser.setIsPremium(fromUser.getIsPremium());
            newUser.setIsBot(fromUser.getIsBot());

            // Сохраняем пользователя в базу
            usrRepository.save(newUser);

            // Отправляем приветственное сообщение
            sendResponse(chatId.toString(), "Добро пожаловать, " + newUser.getFirstName() + "! Вы успешно зарегистрированы.");
        }
    }
}