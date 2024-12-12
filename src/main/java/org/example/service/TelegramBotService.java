package org.example.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.example.entity.Movie;
import org.example.entity.UserMovieRating;
import org.example.entity.Usr;
import org.example.repository.MovieRepository;
import org.example.repository.UserMovieRatingRepository;
import org.example.repository.UsrRepository;
import org.example.util.MessageFormatter;
import org.example.util.UserStateManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class TelegramBotService extends TelegramLongPollingBot {

    private final Map<String, Movie> activeRatings = new HashMap<>();
    private final UserStateManager userStateManager;
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

        commandHandlers.put("/search", this::handleSearchCommand);
        commandHandlers.put("/popular", this::handlePopularCommand);
        commandHandlers.put("/random", this::handleRandomCommand);
        commandHandlers.put("/mostpersonal", this::handleMostPersonalCommand);
        commandHandlers.put("/ratepopular", this::handleRatePopularCommand);
        commandHandlers.put("/personal", this::handlePersonalCommand);
        commandHandlers.put("/rateall", this::handleRateAllCommand);
        commandHandlers.put("/allrated", this::handleAllRatedCommand);
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
                    usr -> System.out.println("User already registered: " + usr.getUsername()),
                    () -> registerNewUser(update)
            );

            if (userStateManager.isWaitingForInput(chatId.toString())) {
                String pendingCommand = userStateManager.getPendingCommand(chatId.toString());
                userStateManager.clearState(chatId.toString());
                if ("/search".equals(pendingCommand)) {
                    processSearchQuery(chatId.toString(), userMessage);
                }
                return;
            }

            String command = userMessage.split(" ")[0].toLowerCase();
            commandHandlers.getOrDefault(command, this::handleUnknownCommand).accept(update);
        }
    }

    private void handleSearchCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        sendResponse(chatId, "🔍 *Введите название фильма, который вы хотите найти.*");
        userStateManager.setPendingCommand(chatId, "/search");
    }

    private void processSearchQuery(String chatId, String query) {
        if (query == null || query.trim().isEmpty()) {
            sendResponse(chatId, "⚠️ *Название фильма не может быть пустым.* Попробуйте снова.");
            userStateManager.setPendingCommand(chatId, "/search");
            return;
        }

        String result = commandProcessingService.searchMovie(query.trim());
        sendSplitResponse(chatId, result.isEmpty() ? "😔 *Фильмы не найдены.* Попробуйте другой запрос." : result);
    }

    private void handlePopularCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        String result = commandProcessingService.getPopularMoviesRandom();
        sendSplitResponse(chatId, result.isEmpty() ? "😔 *Не удалось получить популярные фильмы.* Попробуйте позже." : result);
    }

    private void handleRandomCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        String result = commandProcessingService.getRandomMovie();
        sendSplitResponse(chatId, result);
    }

    private void handleRatePopularCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        Movie randomMovie = commandProcessingService.saveOrUpdateMovie(tmdbService.getRandomPopularMovie());
        activeRatings.put(chatId, randomMovie);
        sendSplitResponse(chatId, MessageFormatter.formatMovieForRating(randomMovie));
    }

    private void handlePersonalCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        String result = commandProcessingService.getPersonalRecommendation(chatId);
        sendSplitResponse(chatId, result.isEmpty() ? "😔 *Нет подходящих фильмов.*" : result);
    }

    private void handleRateAllCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        Movie randomMovie = commandProcessingService.getRandomMovieForRating();
        activeRatings.put(chatId, randomMovie);
        sendSplitResponse(chatId, MessageFormatter.formatMovieForRating(randomMovie));
    }

    private void handleAllRatedCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        String ratedMovies = commandProcessingService.getAllRatedMovies(chatId);
        sendSplitResponse(chatId, ratedMovies.isEmpty() ? "😔 *Вы ещё не оценили фильмы.*" : ratedMovies);
    }

    private void handleMostPersonalCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        String result = commandProcessingService.getMostPersonalRecommendation(chatId);
        sendSplitResponse(chatId, result);
    }

    private void handleUnknownCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        sendSplitResponse(chatId, MessageFormatter.getHelpMessage());
    }

    private void sendSplitResponse(String chatId, String text) {
        int maxMessageLength = 4096;
        for (int i = 0; i < text.length(); i += maxMessageLength) {
            sendResponse(chatId, text.substring(i, Math.min(text.length(), i + maxMessageLength)));
        }
    }

    private void sendResponse(String chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        message.enableMarkdown(true);

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
            sendResponse(chatId.toString(), "Добро пожаловать, " + newUser.getFirstName() + "! Вы успешно зарегистрированы.");
        }
    }
}
