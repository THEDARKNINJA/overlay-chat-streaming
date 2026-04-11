package com.chatoverlaystreaming.emotes;

import com.chatoverlaystreaming.model.EmoteToken;
import org.json.*;
import java.util.*;

public class YouTubeEmojiCache {

    /**
     * Tokeniza un snippet de mensaje de YouTube a partir del JSON raw,
     * extrayendo tanto texto como emojis personalizados y Unicode.
     */
    public List<EmoteToken> tokenize(JSONObject snippet) {
        List<EmoteToken> tokens = new ArrayList<>();

        try {
            JSONObject textMessageDetails = snippet.optJSONObject("textMessageDetails");
            if (textMessageDetails == null) return tokens;

            // Intentar obtener los messageRuns del JSON raw
            JSONArray messageRuns = textMessageDetails.optJSONArray("messageRuns");

            if (messageRuns != null && messageRuns.length() > 0) {
                return parseMessageRuns(messageRuns);
            }

            // Fallback: texto plano
            String text = textMessageDetails.optString("messageText", "");
            if (!text.isBlank()) {
                tokens.add(new EmoteToken.Text(text));
            }

        } catch (Exception e) {
            System.err.println("[YouTube] Error parseando mensaje: " + e.getMessage());
        }

        return tokens;
    }

    private List<EmoteToken> parseMessageRuns(JSONArray runs) {
        List<EmoteToken> tokens = new ArrayList<>();

        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.getJSONObject(i);

            if (run.has("text")) {
                // Fragmento de texto normal
                String text = run.getString("text");
                if (!text.isEmpty()) {
                    tokens.add(new EmoteToken.Text(text));
                }

            } else if (run.has("emoji")) {
                JSONObject emoji = run.getJSONObject("emoji");
                String imageUrl = extractEmojiUrl(emoji);

                if (imageUrl != null) {
                    // Emoji personalizado de canal con imagen
                    String name = extractEmojiName(emoji);
                    tokens.add(new EmoteToken.Emote(name, imageUrl));
                } else {
                    // Emoji Unicode estándar — extraer el carácter directamente
                    String emojiChar = extractUnicodeEmoji(emoji);
                    if (emojiChar != null && !emojiChar.isBlank()) {
                        tokens.add(new EmoteToken.Text(emojiChar));
                    }
                }
            }
        }

        return tokens;
    }

    private String extractEmojiUrl(JSONObject emoji) {
        try {
            JSONObject image = emoji.optJSONObject("image");
            if (image == null) return null;

            JSONArray thumbnails = image.optJSONArray("thumbnails");
            if (thumbnails == null || thumbnails.length() == 0) return null;

            // Preferimos el último thumbnail (mayor resolución)
            return thumbnails.getJSONObject(thumbnails.length() - 1)
                             .getString("url");
        } catch (Exception e) {
            return null;
        }
    }

    private String extractEmojiName(JSONObject emoji) {
        try {
            // Intentar obtener el shortcut del emoji
            JSONArray shortcuts = emoji.optJSONArray("shortcuts");
            if (shortcuts != null && shortcuts.length() > 0) {
                return shortcuts.getString(0);
            }
            // Fallback al emojiId
            return emoji.optString("emojiId", "emoji");
        } catch (Exception e) {
            return "emoji";
        }
    }

    private String extractUnicodeEmoji(JSONObject emoji) {
        try {
            // El emoji Unicode viene en el campo emojiId como el carácter directamente
            String emojiId = emoji.optString("emojiId", null);
            if (emojiId != null) return emojiId;

            // O puede venir dentro de image.accessibility.accessibilityData.label
            JSONObject image = emoji.optJSONObject("image");
            if (image != null) {
                JSONObject accessibility = image.optJSONObject("accessibility");
                if (accessibility != null) {
                    JSONObject accessibilityData = accessibility.optJSONObject("accessibilityData");
                    if (accessibilityData != null) {
                        return accessibilityData.optString("label", null);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}