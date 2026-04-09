package com.chatoverlaystreaming.model;

import com.chatoverlaystreaming.model.EmoteToken;
import java.util.List;

public record ChatMessage(
    String platform,
    String user,
    String text,
    String emotesHeader,
    List<EmoteToken> precomputedTokens
) {
    // Constructor para Twitch (sin tokens precomputados)
    public ChatMessage(String platform, String user, String text, String emotesHeader) {
        this(platform, user, text, emotesHeader, null);
    }

    // Constructor para texto plano sin nada extra
    public ChatMessage(String platform, String user, String text) {
        this(platform, user, text, null, null);
    }
}