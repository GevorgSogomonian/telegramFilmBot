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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
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
    private final Map<String, String> waitingForInput = new ConcurrentHashMap<>(); // Отслеживание состояния пользователя
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
        commandHandlers.put("🌟 Популярные фильмы", this::handlePopularCommand);
        commandHandlers.put("🎲 Случайный фильм", this::handleRandomCommand);
        commandHandlers.put("🏆 Лучшее совпадение", this::handleMostPersonalCommand);
        commandHandlers.put("🎬 Оценить популярный фильм", this::handleRatePopularCommand);
        commandHandlers.put("❤️ Рекомендации", this::handlePersonalCommand);
        commandHandlers.put("🌀 Оценить рандомный фильм", this::handleRateAllCommand);
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

            // Проверяем, существует ли пользователь в базе
            usrRepository.findByChatId(chatId).ifPresentOrElse(
                    usr -> System.out.println("Пользователь уже зарегистрирован: " + usr.getUsername()),
                    () -> registerNewUser(update) // Регистрация нового пользователя
            );

            // Проверка на состояние ожидания ввода
            if (waitingForInput.containsKey(chatId.toString())) {
                String pendingCommand = waitingForInput.remove(chatId.toString());
                if (pendingCommand.equals("search")) {
                    processSearchQuery(update, userMessage);
                }
                return;
            }

            // Проверяем, есть ли активный фильм для оценки
            if (activeRatings.containsKey(chatId.toString())) {
                handleRatingResponse(update);
                return;
            }

            // Обработка команды (если это команда)
//            String command = userMessage.split(" ")[0].toLowerCase();
            commandHandlers.getOrDefault(userMessage, this::handleUnknownCommand).accept(update);
        }
    }

    private void handleSearchCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
//        sendResponse(chatId, "🔍 *Введите название фильма, который вы хотите найти.*");


        ReplyKeyboardRemove removeKeyboard = new ReplyKeyboardRemove();
        removeKeyboard.setRemoveKeyboard(true);

        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText("🔍 *Введите название фильма, который вы хотите найти.*");
        message.setReplyMarkup(removeKeyboard);

        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
        waitingForInput.put(chatId, "search");
    }

    private void processSearchQuery(Update update, String query) {
        if (query == null || query.trim().isEmpty()) {
            sendResponse(update.getMessage().getChatId().toString(), "⚠️ *Название фильма не может быть пустым.* Пожалуйста, попробуйте снова.");
            waitingForInput.put(update.getMessage().getChatId().toString(), "search"); // Возвращаем пользователя в состояние ожидания
            return;
        }

        // Выполняем поиск фильмов
        String result = commandProcessingService.searchMovie(query.trim());
        if (result.isEmpty()) {
            sendResponse(update.getMessage().getChatId().toString(), "😔 *Фильмы не найдены.* Попробуйте другой запрос.");
            handleUnknownCommand(update);
        } else {
            sendSplitResponse(update.getMessage().getChatId().toString(), "🎬 *Результаты поиска:*\n\n" + result);
            handleUnknownCommand(update);
        }
    }

    private void handlePopularCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        String result = commandProcessingService.getPopularMoviesRandom();

        if (!result.isEmpty()) {
            sendSplitResponse(chatId, "🌟 *Популярные фильмы прямо сейчас*:\n\n" + result);
        } else {
            sendResponse(chatId, "😔 *Не удалось получить список популярных фильмов.* Попробуйте позже.");
        }
    }

    private void handleRandomCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        String result = commandProcessingService.getRandomMovie();

        sendSplitResponse(chatId, result);
    }

    private void handleRatePopularCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();

        // Получаем случайный популярный фильм
        Map<String, Object> randomMovieData = tmdbService.getRandomPopularMovie();
        Movie randomMovie = commandProcessingService.saveOrUpdateMovie(randomMovieData);

        // Сохраняем фильм для дальнейшей оценки
        activeRatings.put(chatId, randomMovie);

        // Формируем сообщение с описанием, жанрами и рейтингом
        String response = String.format(
                "🎥 *Мы предлагаем вам фильм:*\n" +
                        "🎬 *Название*: %s\n📖 *Описание*: %s\n🎭 *Жанры*: %s\n⭐ *Рейтинг*: %s\n\n",
//                        "❓ *Вы уже видели этот фильм?* Ответьте 'да' или 'нет'.",
                randomMovie.getTitle(),
                truncateDescription(randomMovie.getDescription()),
                tmdbService.getGenreNames(randomMovie.getGenreIds()), // Жанры
                randomMovie.getRating() != null ? randomMovie.getRating().toString() : "Нет рейтинга"
        );

        SendMessage message = new SendMessage();
        message.setChatId(update.getMessage().getChatId().toString());
        message.setText("❓ *Вы уже видели этот фильм?*");

        // Создаем клавиатуру
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setResizeKeyboard(true); // Делает кнопки компактными

        // Создаем строки клавиатуры
        List<KeyboardRow> keyboardRows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton("Да"));
        row1.add(new KeyboardButton("Нет"));

        // Добавляем строки в клавиатуру
        keyboardRows.add(row1);

        keyboardMarkup.setKeyboard(keyboardRows);

        // Присоединяем клавиатуру к сообщению
        message.setReplyMarkup(keyboardMarkup);

        sendSplitResponse(chatId, response);
        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handlePersonalCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        String result = commandProcessingService.getPersonalRecommendation(chatId);

        sendSplitResponse(chatId, "❤️ *Ваши персональные рекомендации*:\n\n" + result);
    }

    private void handleAllRatedCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();

        try {
            String ratedMovies = commandProcessingService.getAllRatedMovies(chatId);

            sendSplitResponse(chatId, "📋 *Ваши оценки фильмов:*\n\n" + ratedMovies);
        } catch (Exception e) {
            sendResponse(chatId, "❌ *Произошла ошибка при получении списка оцененных фильмов.* Попробуйте позже.");
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
            sendResponse(chatId, "⚠️ *Фильм для оценки не найден.* Попробуйте команду /rate или /rateall.");
            return;
        }

        Optional<UserMovieRating> existingRating = userMovieRatingRepository.findByUserIdAndMovieId(user.getId(), movie.getId());

        if (existingRating.isPresent()) {
            UserMovieRating userMovieRating = existingRating.get();
            userMovieRating.setRating(rating);
            userMovieRatingRepository.save(userMovieRating);
            sendResponse(chatId, "✅ *Ваша оценка обновлена!* Вы поставили " + rating + " баллов. 🎉");
        } else {
            UserMovieRating userMovieRating = new UserMovieRating();
            userMovieRating.setUser(user);
            userMovieRating.setMovie(movie);
            userMovieRating.setRating(rating);
            userMovieRatingRepository.save(userMovieRating);
            sendResponse(chatId, "⭐ *Спасибо за вашу оценку!* Вы поставили " + rating + " баллов. 😊");
        }

        activeRatings.remove(chatId);
    }

    private void handleRatingResponse(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        String userResponse = update.getMessage().getText().toLowerCase();

        // Проверяем, есть ли активный фильм для пользователя
        Movie movie = activeRatings.get(chatId);
        if (movie == null) {
            sendResponse(chatId, "😕 *У вас нет активного фильма для оценки.*\n\n" +
                    "Попробуйте команды эти команды:\n" +
                    "*🎬 Оценить популярный фильм*\n" +
                    "*🌀 Оценить рандомный фильм*");
            return;
        }

        if (userResponse.equals("да")) {
            // Сохраняем фильм в базу (если не был сохранен ранее)
            movieRepository.save(movie);

            sendResponse(chatId, "🎬 Отлично! Как бы вы оценили этот фильм по шкале от 1 до 10? ⭐");
            handleUnknownCommand(update);

            SendMessage message = new SendMessage();
            message.setChatId(update.getMessage().getChatId().toString());
            message.setText("Выберите оценку:");

            // Создаем клавиатуру
            ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
            keyboardMarkup.setResizeKeyboard(true); // Делает кнопки компактными

            // Создаем строки клавиатуры
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

            // Добавляем строки в клавиатуру
            keyboardRows.add(row1);
            keyboardRows.add(row2);

            keyboardMarkup.setKeyboard(keyboardRows);

            // Присоединяем клавиатуру к сообщению
            message.setReplyMarkup(keyboardMarkup);

            try {
                execute(message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (userResponse.equals("нет")) {
            sendResponse(chatId, "🙅‍♂️ *Спасибо за ваш ответ!* Если хотите, попробуйте другой фильм. 🎲");
            handleUnknownCommand(update);
            activeRatings.remove(chatId); // Удаляем из активных рейтингов
        } else {
            try {
                int rating = Integer.parseInt(userResponse);
                if (rating >= 1 && rating <= 10) {
                    saveUserRating(chatId, rating);
                    sendResponse(chatId, "🎉 *Спасибо за вашу оценку!*\n" +
                            "Хотите попробовать еще раз?");

                    handleUnknownCommand(update);
                } else {
                    sendResponse(chatId, "⚠️ Пожалуйста, введите число от 1 до 10. ⭐");
                }
            } catch (NumberFormatException e) {
                sendResponse(chatId, "❓ *Неизвестный ответ.* 🧐");
            }
        }
    }

    private void handleRateAllCommand(Update update) {
        String chatId = update.getMessage().getChatId().toString();

        try {
            Movie randomMovie = commandProcessingService.getRandomMovieForRating();
            activeRatings.put(chatId, randomMovie);

            String response = String.format(
                    "🎲 *Случайный фильм для оценки:*\n" +
                            "🎬 *Название*: %s\n📖 *Описание*: %s\n🎭 *Жанры*: %s\n⭐ *Рейтинг*: %s\n\n",
//                            "❓ *Вы уже видели этот фильм?* Ответьте 'да' или 'нет'.",
                    randomMovie.getTitle(),
                    truncateDescription(randomMovie.getDescription()),
                    tmdbService.getGenreNames(randomMovie.getGenreIds()), // Добавление жанров
                    randomMovie.getRating() != null ? randomMovie.getRating().toString() : "Нет рейтинга"
            );

            SendMessage message = new SendMessage();
            message.setChatId(update.getMessage().getChatId().toString());
            message.setText("❓ *Вы уже видели этот фильм?*");

            // Создаем клавиатуру
            ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
            keyboardMarkup.setResizeKeyboard(true); // Делает кнопки компактными

            // Создаем строки клавиатуры
            List<KeyboardRow> keyboardRows = new ArrayList<>();

            KeyboardRow row1 = new KeyboardRow();
            row1.add(new KeyboardButton("Да"));
            row1.add(new KeyboardButton("Нет"));

            // Добавляем строки в клавиатуру
            keyboardRows.add(row1);

            keyboardMarkup.setKeyboard(keyboardRows);

            // Присоединяем клавиатуру к сообщению
            message.setReplyMarkup(keyboardMarkup);

            sendSplitResponse(chatId, response);
            try {
                execute(message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            sendResponse(chatId, "😞 *К сожалению, не удалось получить случайный фильм для оценки.* Попробуйте позже!");
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

//    private void handleUnknownCommand(Update update) {
//        String chatId = update.getMessage().getChatId().toString();
//
//        String response = """
//        🐾 *Добро пожаловать в вашего личного помощника по фильмам!* 🎥✨
//
//        _Вот список доступных команд, которые вы можете использовать:_
//
//        🔍 `/search` — Найти фильм по названию и получить информацию о нем.
//
//        🌟 `/popular` — Получить список случайных популярных фильмов прямо сейчас.
//
//        🎲 `/random` — Увидеть абсолютно случайный фильм из всех доступных в базе TMDb.
//
//        ❤️ `/personal` — Получить рекомендации фильмов, которые максимально соответствуют вашим предпочтениям.
//
//        🏆 `/mostpersonal` — Узнать самый подходящий вам фильм на основе ваших оценок.
//
//        🎬 `/ratepopular` — Оцените случайный популярный фильм. Вы уже видели его? Расскажите нам!
//
//        🌀 `/rateall` — Оцените абсолютно случайный фильм, не обязательно популярный.
//
//        📜 `/allrated` — Посмотрите список всех фильмов, которые вы оценили, и их оценки.
//
//        🛠️ _Пример использования:_ Просто введите нужную команду, например, `/random`, чтобы получить фильм!
//
//        🧡 _Спасибо, что пользуетесь нашим ботом! Мы здесь, чтобы сделать ваш просмотр фильмов ещё более увлекательным._ 😊
//        """;
//
//        sendSplitResponse(chatId, response);
//    }

//    private void handleUnknownCommand(Update update) {
//        SendMessage message = new SendMessage();
//        message.setChatId(update.getMessage().getChatId().toString());
//        message.setText("Выберите действие:");
//
//        // Создаем Inline-клавиатуру
//        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();
//
//        // Создаем кнопки
//        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
//
//        List<InlineKeyboardButton> row1 = new ArrayList<>();
//        row1.add(InlineKeyboardButton.builder()
//                .text("Популярные фильмы 🎥")
//                .callbackData("/popular")
//                .build());
//        row1.add(InlineKeyboardButton.builder()
//                .text("Случайный фильм 🎲")
//                .callbackData("/random")
//                .build());
//
//        List<InlineKeyboardButton> row2 = new ArrayList<>();
//        row2.add(InlineKeyboardButton.builder()
//                .text("Мои оценки ⭐")
//                .callbackData("/allrated")
//                .build());
//
//        // Добавляем строки в клавиатуру
//        rowsInline.add(row1);
//        rowsInline.add(row2);
//
//        inlineKeyboardMarkup.setKeyboard(rowsInline);
//
//        // Присоединяем Inline-клавиатуру к сообщению
//        message.setReplyMarkup(inlineKeyboardMarkup);
//
//        try {
//            execute(message);
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }

    private void handleUnknownCommand(Update update) {
        SendMessage message = new SendMessage();
        message.setChatId(update.getMessage().getChatId().toString());
        message.setText("Выберите действие:");

        // Создаем клавиатуру
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setResizeKeyboard(true); // Делает кнопки компактными

        // Создаем строки клавиатуры
        List<KeyboardRow> keyboardRows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton("🌟 Популярные фильмы"));
        row1.add(new KeyboardButton("🎲 Случайный фильм"));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton("📜 Мои оценки"));
        row2.add(new KeyboardButton("❤️ Рекомендации"));

        KeyboardRow row3 = new KeyboardRow();
        row3.add(new KeyboardButton("🔍 Поиск"));
        row3.add(new KeyboardButton("🏆 Лучшее совпадение"));

        KeyboardRow row4 = new KeyboardRow();
        row4.add(new KeyboardButton("🎬 Оценить популярный фильм"));

        KeyboardRow row5 = new KeyboardRow();
        row5.add(new KeyboardButton("🌀 Оценить рандомный фильм"));


        //        🔍 `/search` — Найти фильм по названию и получить информацию о нем.
//
//        🌟 `/popular` — Получить список случайных популярных фильмов прямо сейчас.
//
//        🎲 `/random` — Увидеть абсолютно случайный фильм из всех доступных в базе TMDb.
//
//        ❤️ `/personal` — Получить рекомендации фильмов, которые максимально соответствуют вашим предпочтениям.
//
//        🏆 `/mostpersonal` — Узнать самый подходящий вам фильм на основе ваших оценок.
//
//        🎬 `/ratepopular` — Оцените случайный популярный фильм. Вы уже видели его? Расскажите нам!
//
//        🌀 `/rateall` — Оцените абсолютно случайный фильм, не обязательно популярный.
//
//        📜 `/allrated` — Посмотрите список всех фильмов, которые вы оценили, и их оценки.
//
//        🛠️ _Пример использования:_ Просто введите нужную команду, например, `/random`, чтобы получить фильм!
//
//        🧡 _Спасибо, что пользуетесь нашим ботом! Мы здесь, чтобы сделать ваш просмотр фильмов ещё более увлекательным._ 😊
//        """;
        // Добавляем строки в клавиатуру
        keyboardRows.add(row1);
        keyboardRows.add(row2);
        keyboardRows.add(row3);
        keyboardRows.add(row4);
        keyboardRows.add(row5);

        keyboardMarkup.setKeyboard(keyboardRows);

        // Присоединяем клавиатуру к сообщению
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