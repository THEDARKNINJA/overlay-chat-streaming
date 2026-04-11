package com.chatoverlaystreaming.model;

import java.util.List;

public record ChatMessage(
    String platform,
    String user,
    String text,
    String emotesHeader,
    String badgesHeader,
    String userColor,
    String eventType,
    String eventExtra,
    String targetUser,        // para CLEARCHAT: usuario cuyos mensajes borrar
    String messageId,         // ID del mensaje de Twitch para CLEARMSG
    List<EmoteToken> precomputedTokens
) {
    // Constructor para Twitch normal
    public ChatMessage(String platform, String user, String text,
                       String emotesHeader, String badgesHeader, String userColor) {
        this(platform, user, text, emotesHeader, badgesHeader, userColor,
             null, null, null, null, null);
    }

    // Constructor para Twitch con evento
    public ChatMessage(String platform, String user, String text,
                       String emotesHeader, String badgesHeader, String userColor,
                       String eventType, String eventExtra) {
        this(platform, user, text, emotesHeader, badgesHeader, userColor,
             eventType, eventExtra, null, null, null);
    }

    // Constructor para moderación
    public ChatMessage(String platform, String eventType,
                       String targetUser, String messageId) {
        this(platform, null, null, null, null, null,
             eventType, null, targetUser, messageId, null);
    }

    // Constructor para YouTube
    public ChatMessage(String platform, String user, String text,
                       List<EmoteToken> precomputedTokens) {
        this(platform, user, text, null, null, null,
             null, null, null, null, precomputedTokens);
    }

    // Constructor básico
    public ChatMessage(String platform, String user, String text) {
        this(platform, user, text, null, null, null,
             null, null, null, null, null);
    }
}