package com.chatoverlaystreaming.emotes;

import com.chatoverlaystreaming.model.EmoteToken;
import com.google.api.services.youtube.model.LiveChatMessage;
import com.google.api.services.youtube.model.LiveChatMessageSnippet;
import java.util.*;

public class YouTubeEmojiCache {

    /**
     * Convierte un mensaje de YouTube en una lista de tokens,
     * intercalando imágenes de emoji donde corresponda.
     */
    public List<EmoteToken> tokenize(LiveChatMessage message) {
        List<EmoteToken> tokens = new ArrayList<>();
        LiveChatMessageSnippet snippet = message.getSnippet();

        if (!"textMessageEvent".equals(snippet.getType())) {
            return tokens;
        }

        // La API puede devolver el mensaje con runs (fragmentos) si hay emojis
        // En la respuesta JSON, authorChannelId y textMessageDetails.messageText
        // ya contienen el texto plano. Los emojis de membresía vienen en
        // superChatDetails o directamente en el texto con su representación Unicode.

        // Para emojis personalizados (de canal), la API v3 los representa
        // en el campo messageText como su shortcut (ej: ":_nombreEmoji:")
        // y en liveChatMessage.snippet hay un array "emojiRuns" en la respuesta raw.

        // Estrategia práctica: parseamos el texto y detectamos el patrón :_xxx:
        String text = snippet.getTextMessageDetails().getMessageText();
        tokens.addAll(parseEmojiRuns(text, message));

        return tokens;
    }

    private List<EmoteToken> parseEmojiRuns(String text, LiveChatMessage message) {
        List<EmoteToken> tokens = new ArrayList<>();

        // Intentamos extraer los "emojiRuns" del objeto crudo si está disponible
        // La librería de Google los expone a través de getUnknownKeys()
        Object rawRuns = null;
        try {
            rawRuns = message.getSnippet().getUnknownKeys().get("textMessageDetails");
        } catch (Exception ignored) {}

        if (rawRuns instanceof Map<?, ?> details) {
            Object runs = ((Map<?, ?>) details).get("messageRuns");
            if (runs instanceof List<?> runList) {
                return parseRunList(runList);
            }
        }

        // Fallback: si no tenemos runs, devolvemos el texto plano
        // Los emojis Unicode (😂 🔥) se renderizan solos si la fuente los soporta
        tokens.add(new EmoteToken.Text(text));
        return tokens;
    }

    @SuppressWarnings("unchecked")
    private List<EmoteToken> parseRunList(List<?> runs) {
        List<EmoteToken> tokens = new ArrayList<>();
        for (Object run : runs) {
            if (!(run instanceof Map)) continue;
            Map<String, Object> runMap = (Map<String, Object>) run;

            if (runMap.containsKey("text")) {
                // Fragmento de texto normal
                tokens.add(new EmoteToken.Text(runMap.get("text").toString()));

            } else if (runMap.containsKey("emoji")) {
                // Emoji: puede ser Unicode o personalizado de canal
                Map<String, Object> emoji = (Map<String, Object>) runMap.get("emoji");
                String imageUrl = extractEmojiUrl(emoji);

                if (imageUrl != null) {
                    String name = emoji.getOrDefault("shortcuts", List.of("emoji"))
                                       .toString();
                    tokens.add(new EmoteToken.Emote(name, imageUrl));
                } else {
                    // Emoji Unicode estándar — lo dejamos como texto, la fuente lo renderiza
                    Object emojiId = emoji.get("emojiId");
                    if (emojiId != null) {
                        tokens.add(new EmoteToken.Text(emojiId.toString()));
                    }
                }
            }
        }
        return tokens;
    }

    @SuppressWarnings("unchecked")
    private String extractEmojiUrl(Map<String, Object> emoji) {
        try {
            Map<String, Object> image = (Map<String, Object>) emoji.get("image");
            if (image == null) return null;
            List<Map<String, Object>> thumbnails =
                    (List<Map<String, Object>>) image.get("thumbnails");
            if (thumbnails == null || thumbnails.isEmpty()) return null;
            // Preferimos el último thumbnail (mayor resolución)
            return thumbnails.get(thumbnails.size() - 1).get("url").toString();
        } catch (Exception e) {
            return null;
        }
    }
}