package com.chatoverlaystreaming.emotes;

import com.chatoverlaystreaming.model.EmoteToken;
import java.util.*;

public class TwitchEmoteCache {

    // URL de la CDN de Twitch para emotes (tamaño 1.0 = 28x28px aprox)
    private static final String CDN = "https://static-cdn.jtvnw.net/emoticons/v2/%s/default/dark/1.0";

    /**
     * Parsea la cabecera "emotes" de IRC y devuelve los tokens del mensaje.
     *
     * @param emotesHeader  el valor de la cabecera @emotes (puede ser "" o null)
     * @param messageText   el texto del mensaje
     */
    public List<EmoteToken> tokenize(String emotesHeader, String messageText) {
        // Mapa de inicio de posición -> (fin, url)
        TreeMap<Integer, int[]> positions = new TreeMap<>();

        if (emotesHeader != null && !emotesHeader.isBlank()) {
            // Formato: id:start-end,start-end/id2:start-end
            for (String emoteEntry : emotesHeader.split("/")) {
                String[] parts = emoteEntry.split(":");
                if (parts.length < 2) continue;
                String emoteId = parts[0];
                String url = CDN.formatted(emoteId);
                for (String range : parts[1].split(",")) {
                    String[] bounds = range.split("-");
                    if (bounds.length < 2) continue;
                    int start = Integer.parseInt(bounds[0]);
                    int end   = Integer.parseInt(bounds[1]);
                    positions.put(start, new int[]{end, url.hashCode()});
                    // Guardamos la url en un mapa paralelo porque int[] no admite String
                    urlByStart.put(start, url);
                }
            }
        }

        return buildTokens(messageText, positions);
    }

    // Mapa auxiliar start -> url (necesario porque TreeMap<Integer, int[]> no almacena String)
    private final Map<Integer, String> urlByStart = new HashMap<>();

    private List<EmoteToken> buildTokens(String text, TreeMap<Integer, int[]> positions) {
        List<EmoteToken> tokens = new ArrayList<>();
        int cursor = 0;

        // Los bytes de Twitch son en UTF-16, pero para ASCII es igual que chars
        char[] chars = text.toCharArray();

        for (Map.Entry<Integer, int[]> entry : positions.entrySet()) {
            int start = entry.getKey();
            int end   = entry.getValue()[0];

            if (start > cursor) {
                tokens.add(new EmoteToken.Text(text.substring(cursor, start)));
            }

            String emoteName = text.substring(start, end + 1);
            String url = urlByStart.get(start);
            tokens.add(new EmoteToken.Emote(emoteName, url));
            cursor = end + 1;
        }

        if (cursor < chars.length) {
            tokens.add(new EmoteToken.Text(text.substring(cursor)));
        }

        urlByStart.clear();
        return tokens;
    }
}