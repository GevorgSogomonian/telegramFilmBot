package org.example.util;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserStateManager {

    private final ConcurrentHashMap<String, String> userStates = new ConcurrentHashMap<>();

    public void setPendingCommand(String chatId, String command) {
        userStates.put(chatId, command);
    }

    public boolean isWaitingForInput(String chatId) {
        return userStates.containsKey(chatId);
    }

    public String getPendingCommand(String chatId) {
        return userStates.get(chatId);
    }

    public void clearState(String chatId) {
        userStates.remove(chatId);
    }
}
