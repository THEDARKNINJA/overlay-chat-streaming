package com.chatoverlaystreaming.model;

import java.util.List;

/**
 * Modelo inmutable que representa un mensaje del chat o un evento de plataforma.
 *
 * Se usa como mensaje en la BlockingQueue compartida entre los readers
 * (TwitchChatReader, YouTubeChatReader) y el consumidor (ChatOverlay).
 *
 * Campos principales:
 * <ul>
 *   <li>{@code platform} — "twitch" o "youtube".</li>
 *   <li>{@code user} — Nombre del usuario que envió el mensaje.</li>
 *   <li>{@code text} — Texto plano del mensaje.</li>
 *   <li>{@code emotesHeader} — Cabecera IRC "@emotes=" de Twitch (posiciones de emotes).</li>
 *   <li>{@code badgesHeader} — Cabecera IRC "@badges=" de Twitch.</li>
 *   <li>{@code userColor} — Color del nombre del usuario en formato "#RRGGBB".</li>
 *   <li>{@code eventType} — Tipo de evento especial: "reward", "cheer", "subgift",
 *       "clearchat", "clearall", "clearmsg". Null para mensajes normales.</li>
 *   <li>{@code eventExtra} — Datos adicionales del evento. Para "reward": "titulo|rewardId|redemptionId".
 *       Para "cheer": cantidad de bits. Para "subgift": descripción del regalo.</li>
 *   <li>{@code targetUser} — Usuario objetivo en eventos de moderación (CLEARCHAT).</li>
 *   <li>{@code messageId} — ID único del mensaje de Twitch para CLEARMSG.</li>
 *   <li>{@code precomputedTokens} — Tokens de emotes ya procesados (YouTube).
 *       Si no es null, se usan directamente sin tokenizar.</li>
 * </ul>
 *
 * Se proporcionan constructores de conveniencia para los casos de uso más comunes,
 * todos delegando en el constructor canónico con 11 parámetros.
 */
public record ChatMessage(
        String           platform,
        String           user,
        String           text,
        String           emotesHeader,
        String           badgesHeader,
        String           userColor,
        String           eventType,
        String           eventExtra,
        String           targetUser,
        String           messageId,
        List<EmoteToken> precomputedTokens
) {
    /**
     * Mensaje normal de Twitch con emotes y badges.
     *
     * @param platform     "twitch"
     * @param user         Nombre del usuario.
     * @param text         Texto del mensaje.
     * @param emotesHeader Cabecera IRC @emotes (puede ser null o vacía).
     * @param badgesHeader Cabecera IRC @badges (puede ser null o vacía).
     * @param userColor    Color en "#RRGGBB" o null si no tiene.
     */
    public ChatMessage(String platform, String user, String text,
                       String emotesHeader, String badgesHeader, String userColor) {
        this(platform, user, text, emotesHeader, badgesHeader, userColor,
                null, null, null, null, null);
    }

    /**
     * Mensaje de Twitch asociado a un evento (recompensa, cheer, subgift).
     *
     * @param eventType  Tipo de evento: "reward", "cheer", "subgift".
     * @param eventExtra Datos del evento (título de recompensa, bits, etc.).
     */
    public ChatMessage(String platform, String user, String text,
                       String emotesHeader, String badgesHeader, String userColor,
                       String eventType, String eventExtra) {
        this(platform, user, text, emotesHeader, badgesHeader, userColor,
                eventType, eventExtra, null, null, null);
    }

    /**
     * Evento de moderación de Twitch sin mensaje de usuario.
     * Usado para CLEARCHAT (ban/timeout) y CLEARMSG (borrar mensaje).
     *
     * @param eventType  "clearchat", "clearall" o "clearmsg".
     * @param targetUser Usuario afectado (CLEARCHAT) o null (CLEARALL).
     * @param messageId  ID del mensaje a borrar (CLEARMSG) o null.
     */
    public ChatMessage(String platform, String eventType,
                       String targetUser, String messageId) {
        this(platform, null, null, null, null, null,
                eventType, null, targetUser, messageId, null);
    }

    /**
     * Mensaje de YouTube con tokens de emotes ya procesados.
     * YouTubeChatReader tokeniza los emojis antes de encolar el mensaje.
     *
     * @param precomputedTokens Tokens ya procesados por YouTubeEmojiCache.
     */
    public ChatMessage(String platform, String user, String text,
                       List<EmoteToken> precomputedTokens) {
        this(platform, user, text, null, null, null,
                null, null, null, null, precomputedTokens);
    }

    /**
     * Mensaje de texto plano sin emotes ni eventos.
     * Usado para mensajes de sistema o casos simples.
     */
    public ChatMessage(String platform, String user, String text) {
        this(platform, user, text, null, null, null,
                null, null, null, null, null);
    }
}