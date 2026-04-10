package com.chatoverlaystreaming.model;

import java.util.List;

public record ChatMessage(
    String platform,
    String user,
    String text,
    String emotesHeader,
    String badgesHeader,
    List<EmoteToken> precomputedTokens
) {
    // Constructor para Twitch
    public ChatMessage(String platform, String user, String text,
                       String emotesHeader, String badgesHeader) {
        this(platform, user, text, emotesHeader, badgesHeader, null);
    }

    // Constructor para YouTube
    public ChatMessage(String platform, String user, String text,
                       List<EmoteToken> precomputedTokens) {
        this(platform, user, text, null, null, precomputedTokens);
    }

    // Constructor básico sin nada extra
    public ChatMessage(String platform, String user, String text) {
        this(platform, user, text, null, null, null);
    }
}