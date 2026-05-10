package com.chatoverlaystreaming.emotes;

import com.chatoverlaystreaming.model.EmoteToken;

import java.util.*;

/**
 * Tokenizador de emotes de Twitch a partir de las cabeceras IRC.
 *
 * Twitch envía los emotes en la cabecera @emotes con las posiciones exactas
 * de cada emote en el texto del mensaje, en lugar de requerir búsqueda por nombre.
 * Esto permite tokenizar el mensaje con precisión incluso si el nombre del emote
 * aparece como texto normal en otro contexto.
 *
 * Formato de la cabecera @emotes:
 * <pre>
 *   emoteId:start-end,start-end/emoteId2:start-end
 *   Ejemplo: "25:0-4/1902:6-10,12-16"
 *   → emote 25 en posición 0-4, emote 1902 en posiciones 6-10 y 12-16
 * </pre>
 *
 * Limitación conocida:
 *   Twitch usa offsets en unidades de caracteres UTF-16. Para texto ASCII
 *   (que incluye la mayoría de los nombres de emotes) los offsets coinciden
 *   con los índices de Java. Para texto con caracteres Unicode fuera del BMP
 *   (emojis de 4 bytes) puede haber desplazamiento. Este caso es raro en la
 *   práctica ya que los emotes suelen aparecer al inicio o separados por espacios.
 */
public class TwitchEmoteCache {

    /** Plantilla de URL de la CDN de Twitch para emotes en modo oscuro, tamaño 1x. */
    private static final String CDN_URL =
            "https://static-cdn.jtvnw.net/emoticons/v2/%s/default/dark/1.0";

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Parsea la cabecera @emotes del IRC de Twitch y devuelve los tokens del mensaje.
     *
     * El resultado es una lista ordenada de tokens de texto y emotes que, concatenados,
     * reconstruyen el mensaje original. Los tokens de emotes llevan la URL de la CDN
     * para que {@link ImageCache} descargue la imagen bajo demanda.
     *
     * @param emotesHeader Valor de la cabecera @emotes, ej. "25:0-4/1902:6-10".
     *                     Puede ser null o vacío si el mensaje no contiene emotes.
     * @param messageText  Texto completo del mensaje IRC.
     * @return Lista de tokens en orden de aparición en el mensaje.
     */
    public List<EmoteToken> tokenize(String emotesHeader, String messageText) {
        TreeMap<Integer, EmotePosition> positions = parseEmotePositions(emotesHeader);
        return buildTokens(messageText, positions);
    }

    // ── Parseo de la cabecera ─────────────────────────────────────────────────

    /**
     * Parsea la cabecera @emotes y construye un mapa de posición de inicio → EmotePosition.
     * El TreeMap garantiza que las posiciones estén ordenadas para recorrer el texto de
     * izquierda a derecha.
     *
     * @param emotesHeader Cabecera @emotes raw del IRC.
     * @return Mapa ordenado de offset de inicio → datos del emote en esa posición.
     */
    private TreeMap<Integer, EmotePosition> parseEmotePositions(String emotesHeader) {
        TreeMap<Integer, EmotePosition> positions = new TreeMap<>();
        if (emotesHeader == null || emotesHeader.isBlank()) return positions;

        // Formato: "id:start-end,start-end/id2:start-end"
        for (String emoteEntry : emotesHeader.split("/")) {
            String[] parts = emoteEntry.split(":", 2);
            if (parts.length < 2) continue;

            String emoteId = parts[0];
            String url     = CDN_URL.formatted(emoteId);

            for (String range : parts[1].split(",")) {
                String[] bounds = range.split("-", 2);
                if (bounds.length < 2) continue;
                try {
                    int start = Integer.parseInt(bounds[0]);
                    int end   = Integer.parseInt(bounds[1]);
                    positions.put(start, new EmotePosition(end, url));
                } catch (NumberFormatException e) {
                    System.err.println("[TwitchEmotes] Rango inválido: " + range);
                }
            }
        }
        return positions;
    }

    // ── Construcción de tokens ────────────────────────────────────────────────

    /**
     * Construye la lista de tokens recorriendo el texto y las posiciones de emotes.
     *
     * Para cada emote: inserta el texto anterior como Text, luego el emote como Emote.
     * El texto restante tras el último emote se añade como Text al final.
     *
     * @param text      Texto completo del mensaje.
     * @param positions Mapa ordenado de posición de inicio → datos del emote.
     * @return Lista de tokens en orden de aparición.
     */
    private List<EmoteToken> buildTokens(String text,
                                          TreeMap<Integer, EmotePosition> positions) {
        List<EmoteToken> tokens = new ArrayList<>();
        int cursor = 0;

        for (Map.Entry<Integer, EmotePosition> entry : positions.entrySet()) {
            int           start    = entry.getKey();
            EmotePosition position = entry.getValue();

            // Texto antes del emote
            if (start > cursor) {
                tokens.add(new EmoteToken.Text(text.substring(cursor, start)));
            }

            // El emote
            String emoteName = text.substring(start, position.end() + 1);
            tokens.add(new EmoteToken.Emote(emoteName, position.url()));
            cursor = position.end() + 1;
        }

        // Texto restante tras el último emote
        if (cursor < text.length()) {
            tokens.add(new EmoteToken.Text(text.substring(cursor)));
        }

        return tokens;
    }

    // ── Record auxiliar ───────────────────────────────────────────────────────

    /**
     * Datos de un emote en una posición concreta del mensaje.
     *
     * @param end Offset del último carácter del emote (inclusive).
     * @param url URL de la imagen del emote en la CDN de Twitch.
     */
    private record EmotePosition(int end, String url) {}
}