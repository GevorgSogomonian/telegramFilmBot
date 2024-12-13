package org.example.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class TelegramBotService extends TelegramLongPollingBot {

    private final Map<String, Movie> activeRatings = new ConcurrentHashMap<>();
    private final Map<String, String> waitingForInput = new ConcurrentHashMap<>();
    private final UserMovieRatingRepository userMovieRatingRepository;
    private final CommandProcessingService commandProcessingService;
    private final UsrRepository usrRepository;
    private final MovieRepository movieRepository;
    private final TmdbService tmdbService;

    @Value("${spring.telegram.bot.username}")
    private String botUsername;

    @Value("${spring.telegram.bot.token}")
    private String botToken;

    private final Map<String, Consumer<Update>> commandHandlers = new HashMap<>();

    @PostConstruct
    public void init() {
        System.out.println("Username: " + botUsername);
        System.out.println("Token: " + botToken);

        commandHandlers.put("🔍 Поиск", this::handleSearchCommand);
        commandHandlers.put("🌀 Рандомный фильм", this::handleRateAllCommand);
        commandHandlers.put("🎬 Популярные фильмы", this::handleRatePopularCommand);
        commandHandlers.put("🏆 Лучшее совпадение", this::handleMostPersonalCommand);
        commandHandlers.put("❤️ Рекомендации", this::handlePersonalCommand);
        commandHandlers.put("📜 Мои оценки", this::handleAllRatedCommand);
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
            Long chatId = update.getMessage().getChatId();
            String userMessage = update.getMessage().getText();

            usrRepository.findByChatId(chatId).ifPresentOrElse(
                    usr -> System.out.println("Пользователь уже зарегистрирован: " + usr.getUsername()),
                    () -> registerNewUser(update)
            );

            if (waitingForInput.containsKey(chatId.toString())) {
                String pendingCommand = waitingForInput.remove(chatId.toString());
                if (pendingCommand.equals("search")) {
                    processSearchQuery(update);
                }
                return;
            }

            if (activeRatings.containsKey(chatId.toString())) {
                handleRatingResponse(update);
                return;
            }

            commandHandlers.getOrDefault(userMessage, this::handleUnknownCommand).accept(update);
        }
    }

    private void handleSearchCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();

        ReplyKeyboardRemove removeKeyboard = new ReplyKeyboardRemove();
        removeKeyboard.setRemoveKeyboard(true);

        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText("""
                🔍 *Введите название фильма, который вы хотите найти.*""");
        message.setReplyMarkup(removeKeyboard);

        message.setParseMode("Markdown");
        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
        waitingForInput.put(chatId, "search");
    }

    private void processSearchQuery(Update update) {
        String messageText = update.getMessage().getText();
        String chatId = update.getMessage().getChatId().toString();
        if (messageText == null || messageText.trim().isEmpty()) {
            sendResponse(chatId, """
                    ⚠️ *Название фильма не может быть пустым.*
                    Пожалуйста, попробуйте снова.""");
            waitingForInput.put(chatId, "search");
            return;
        }

        String result = commandProcessingService.searchMovie(update);
        if (result.isEmpty()) {
            sendResponse(chatId, """
                    😔 *Фильмы не найдены.*
                    Попробуйте другой запрос.""");
        } else {
            sendSplitResponse(chatId, String.format("""
                    🎬 *Результаты поиска:*
                    
                    %s""", result));
        }
        handleUnknownCommand(update);
    }

    private void handleRatePopularCommand(Update update) {
        Long chatId = update.getMessage().getChatId();

        Usr user = usrRepository.findByChatId(chatId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден."));
        Map<String, Object> randomMovieData = tmdbService.getRandomPopularMovie();
        Movie randomMovie = commandProcessingService.saveOrUpdateMovie(randomMovieData);
        double similarity = commandProcessingService.computeCosineSimilarity(commandProcessingService.getUserGenres(user),
                commandProcessingService.createGenreVector(randomMovie.getGenreIds()));

        activeRatings.put(chatId.toString(), randomMovie);

        String response = String.format(
                """
                        %s
                        🤝 *Сходство:* %s
                        
                        """,
                commandProcessingService.movietoString(randomMovie),
                similarity != 0 ? String.valueOf(similarity * 100).substring(0, 4) + "%" : "Не известно"
        );

        SendMessage message = new SendMessage();
        message.setChatId(update.getMessage().getChatId().toString());
        message.setText("""
                ❓ *Хотите оценить этот фильм?*""");
        message.setParseMode("Markdown");

        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setResizeKeyboard(true);

        List<KeyboardRow> keyboardRows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton("Да"));
        row1.add(new KeyboardButton("Нет"));

        keyboardRows.add(row1);
        keyboardMarkup.setKeyboard(keyboardRows);
        message.setReplyMarkup(keyboardMarkup);

        sendSplitResponse(chatId.toString(), response);
        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handlePersonalCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        String result = commandProcessingService.getPersonalRecommendation(chatId);

        sendSplitResponse(chatId, String.format("""
                ❤️ *Ваши персональные рекомендации*:
                
                %s""",result));
    }

    private void handleAllRatedCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();

        try {
            String ratedMovies = commandProcessingService.getAllRatedMovies(chatId);

            sendSplitResponse(chatId, String.format("""
                    📋 *Ваши оценки фильмов:*
                    
                    %s""", ratedMovies));
        } catch (Exception e) {
            sendResponse(chatId, """
                    ❌ *Произошла ошибка при получении списка оцененных фильмов.*
                    Попробуйте позже.""");
            e.printStackTrace();
        }
    }

    private void handleMostPersonalCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        String result = commandProcessingService.getMostPersonalRecommendation(chatId);
        sendSplitResponse(chatId, result);
    }

    private void saveUserRating(String chatId, int rating) {
        Long userChatId = Long.parseLong(chatId);
        Usr user = usrRepository.findByChatId(userChatId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден."));
        Movie movie = activeRatings.get(chatId);

        if (movie == null) {
            sendResponse(chatId, """
                    ⚠️ *Фильм для оценки не найден.*
                    
                    Попробуйте эти команды:
                    🎬 *Популярные фильмы*
                    🌀 *Рандомный фильм*""");
            return;
        }

        Optional<UserMovieRating> existingRating = userMovieRatingRepository.findByUserIdAndMovieId(user.getId(), movie.getId());

        if (existingRating.isPresent()) {
            UserMovieRating userMovieRating = existingRating.get();
            userMovieRating.setRating(rating);
            userMovieRatingRepository.save(userMovieRating);
            sendResponse(chatId, String.format("""
                    ✅ *Ваша оценка обновлена!*
                    Вы поставили %s баллов. 🎉""", rating));
        } else {
            UserMovieRating userMovieRating = new UserMovieRating();
            userMovieRating.setUser(user);
            userMovieRating.setMovie(movie);
            userMovieRating.setRating(rating);
            userMovieRatingRepository.save(userMovieRating);
            sendResponse(chatId, String.format("""
                    ⭐ *Спасибо за вашу оценку!*
                    Вы поставили %s баллов. 😊""", rating));
        }

        Map<String, Double> genrePreferences = commandProcessingService.getUserGenres(user);
        user.setGenrePreferences(CommandProcessingService.mapToJson(genrePreferences));
        usrRepository.save(user);

        activeRatings.remove(chatId);
    }

    private void handleRatingResponse(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        String userResponse = update.getMessage().getText().toLowerCase();

        Movie movie = activeRatings.get(chatId);
        if (movie == null) {
            sendResponse(chatId, """
                    😕 *У вас нет активного фильма для оценки.*
                    
                    Попробуйте эти команды:
                    🎬 *Популярные фильмы*
                    🌀 *Рандомный фильм*""");
            return;
        }

        if (userResponse.equals("да")) {
            movieRepository.save(movie);

            sendResponse(chatId, """
                    🎬 Отлично! Как бы вы оценили этот фильм по шкале от 1 до 10? ⭐""");

            SendMessage message = new SendMessage();
            message.setChatId(update.getMessage().getChatId().toString());
            message.setText("Выберите оценку:");
            message.setParseMode("Markdown");

            ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
            keyboardMarkup.setResizeKeyboard(true);

            List<KeyboardRow> keyboardRows = new ArrayList<>();

            KeyboardRow row1 = new KeyboardRow();
            row1.add(new KeyboardButton("1"));
            row1.add(new KeyboardButton("2"));
            row1.add(new KeyboardButton("3"));
            row1.add(new KeyboardButton("4"));
            row1.add(new KeyboardButton("5"));

            KeyboardRow row2 = new KeyboardRow();
            row2.add(new KeyboardButton("6"));
            row2.add(new KeyboardButton("7"));
            row2.add(new KeyboardButton("8"));
            row2.add(new KeyboardButton("9"));
            row2.add(new KeyboardButton("10"));

            keyboardRows.add(row1);
            keyboardRows.add(row2);

            keyboardMarkup.setKeyboard(keyboardRows);

            message.setReplyMarkup(keyboardMarkup);

            try {
                execute(message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (userResponse.equals("нет")) {
            sendResponse(chatId, """
                    🙅‍♂️ *Спасибо за ваш ответ!*
                    Если хотите, попробуйте другой фильм. 🎲""");
            handleUnknownCommand(update);
            activeRatings.remove(chatId);
        } else {
            try {
                int rating = Integer.parseInt(userResponse);
                if (rating >= 1 && rating <= 10) {
                    saveUserRating(chatId, rating);
                    sendResponse(chatId, """
                            🎉 *Хотите попробовать еще раз?*""");

                    handleUnknownCommand(update);
                } else {
                    sendResponse(chatId, """
                            ⚠️ Пожалуйста, введите число от 1 до 10. ⭐""");
                }
            } catch (NumberFormatException e) {
                sendResponse(chatId, """
                        ❓ *Неизвестный ответ.* 🧐""");
            }
        }
    }

    private void handleRateAllCommand(Update update) {
        Long chatId = update.getMessage().getChatId();

        Usr user = usrRepository.findByChatId(chatId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден."));

        try {
            Movie randomMovie = commandProcessingService.getRandomMovieForRating();
            double similarity = commandProcessingService.computeCosineSimilarity(commandProcessingService.getUserGenres(user),
                    commandProcessingService.createGenreVector(randomMovie.getGenreIds()));
            activeRatings.put(chatId.toString(), randomMovie);

            String response = String.format(
                    """
                            🎲 *Случайный фильм для оценки:*
                            %s
                            🤝 *Сходство:* %s
                            
                            """,
                    commandProcessingService.movietoString(randomMovie),
                    similarity != 0 ? String.valueOf(similarity * 100).substring(0, 4) + "%": "Не известно"
            );

            SendMessage message = new SendMessage();
            message.setChatId(update.getMessage().getChatId().toString());
            message.setText("""
                    ❓ *Хотите оценить этот фильм?*""");
            message.setParseMode("Markdown");

            ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
            keyboardMarkup.setResizeKeyboard(true);

            List<KeyboardRow> keyboardRows = new ArrayList<>();

            KeyboardRow row1 = new KeyboardRow();
            row1.add(new KeyboardButton("Да"));
            row1.add(new KeyboardButton("Нет"));

            keyboardRows.add(row1);

            keyboardMarkup.setKeyboard(keyboardRows);

            message.setReplyMarkup(keyboardMarkup);

            sendSplitResponse(chatId.toString(), response);
            try {
                execute(message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            sendResponse(chatId.toString(), """
                    😞 *К сожалению, не удалось получить случайный фильм для оценки.* Попробуйте позже!""");
            e.printStackTrace();
        }
    }

    private String truncateDescription(String description) {
        int maxLength = 500;
        if (description != null && description.length() > maxLength) {
            return description.substring(0, maxLength) + "...";
        }
        return description != null ? description : "Описание недоступно.";
    }

    private void handleUnknownCommand(Update update) {
        SendMessage message = new SendMessage();
        message.setChatId(update.getMessage().getChatId().toString());
        message.setText("Выберите действие:");

        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setResizeKeyboard(true);

        List<KeyboardRow> keyboardRows = new ArrayList<>();

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton("📜 Мои оценки"));
        row2.add(new KeyboardButton("❤️ Рекомендации"));

        KeyboardRow row3 = new KeyboardRow();
        row3.add(new KeyboardButton("🔍 Поиск"));
        row3.add(new KeyboardButton("🏆 Лучшее совпадение"));

        KeyboardRow row4 = new KeyboardRow();
        row4.add(new KeyboardButton("🎬 Популярные фильмы"));

        KeyboardRow row5 = new KeyboardRow();
        row5.add(new KeyboardButton("🌀 Рандомный фильм"));

        keyboardRows.add(row2);
        keyboardRows.add(row3);
        keyboardRows.add(row4);
        keyboardRows.add(row5);

        keyboardMarkup.setKeyboard(keyboardRows);

        message.setReplyMarkup(keyboardMarkup);

        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
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

        message.setParseMode("Markdown");
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

            Usr newUser = new Usr();
            newUser.setChatId(chatId);
            newUser.setUsername(fromUser.getUserName());
            newUser.setFirstName(fromUser.getFirstName());
            newUser.setLastName(fromUser.getLastName());
            newUser.setLanguageCode(fromUser.getLanguageCode());
            newUser.setIsPremium(fromUser.getIsPremium());
            newUser.setIsBot(fromUser.getIsBot());

            usrRepository.save(newUser);

            sendResponse(chatId.toString(), String.format("""
                    Добро пожаловать, *%s*! Вы успешно зарегистрированы.""", newUser.getFirstName()));
        }
    }
}