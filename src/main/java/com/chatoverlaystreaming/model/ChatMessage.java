package com.chatoverlaystreaming.model;

import java.util.List;

public record ChatMessage(
    String platform,
    String user,
    String text,
    String emotesHeader,
    String badgesHeader,
    String userColor, 
    List<EmoteToken> precomputedTokens
) {
    // Constructor para Twitch
    public ChatMessage(String platform, String user, String text,
                       String emotesHeader, String badgesHeader, String userColor) {
        this(platform, user, text, emotesHeader, badgesHeader, userColor, null);
    }

    // Constructor para YouTube
    public ChatMessage(String platform, String user, String text,
                       List<EmoteToken> precomputedTokens) {
        this(platform, user, text, null, null, null, precomputedTokens);
    }

    // Constructor básico sin nada extra
    public ChatMessage(String platform, String user, String text) {
        this(platform, user, text, null, null, null, null);
    }
}