package com.chatoverlaystreaming.model;

import java.util.List;

public record ChatMessage(
    String platform,
    String user,
    String text,
    String emotesHeader,
    String badgesHeader,
    String userColor,
    String eventType,        // null = mensaje normal, "reward", "subgift", "cheer"
    String eventExtra,       // info extra: nombre de la recompensa, cantidad de bits, etc.
    List<EmoteToken> precomputedTokens
) {
    // Constructor para Twitch normal
    public ChatMessage(String platform, String user, String text,
                       String emotesHeader, String badgesHeader, String userColor) {
        this(platform, user, text, emotesHeader, badgesHeader, userColor, null, null, null);
    }

    // Constructor para Twitch con evento
    public ChatMessage(String platform, String user, String text,
                       String emotesHeader, String badgesHeader, String userColor,
                       String eventType, String eventExtra) {
        this(platform, user, text, emotesHeader, badgesHeader, userColor, eventType, eventExtra, null);
    }

    // Constructor para YouTube
    public ChatMessage(String platform, String user, String text,
                       List<EmoteToken> precomputedTokens) {
        this(platform, user, text, null, null, null, null, null, precomputedTokens);
    }

    // Constructor básico
    public ChatMessage(String platform, String user, String text) {
        this(platform, user, text, null, null, null, null, null, null);
    }
    
}