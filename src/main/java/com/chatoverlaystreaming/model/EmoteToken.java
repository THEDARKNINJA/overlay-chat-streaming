package com.chatoverlaystreaming.model;

public sealed interface EmoteToken permits EmoteToken.Text, EmoteToken.Emote {

    record Text(String content) implements EmoteToken {}

    record Emote(String name, String url) implements EmoteToken {}
}