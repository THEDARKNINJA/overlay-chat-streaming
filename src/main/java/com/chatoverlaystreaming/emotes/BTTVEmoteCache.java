package com.chatoverlaystreaming.emotes;

import com.chatoverlaystreaming.model.EmoteToken;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caché de emotes de BetterTTV (BTTV) para Twitch.
 *
 * Carga en el constructor los emotes globales de BTTV y los específicos
 * del canal indicado. El mapa resultante (nombre → URL de imagen) se usa
 * en {@link #process} para sustituir tokens de texto por emotes.
 *
 * La carga es síncrona y se realiza una sola vez al arrancar. Si falla,
 * se registra el error y el caché queda vacío (sin emotes de BTTV).
 *
 * Compatibilidad con TwitchEmoteCache:
 *   process() recibe la lista de tokens ya tokenizada por TwitchEmoteCache
 *   y expande los tokens de texto que contengan palabras reconocidas como
 *   emotes de BTTV, preservando los EmoteToken.Emote de Twitch intactos.
 */
public class BTTVEmoteCache {

    // ── Constantes ────────────────────────────────────────────────────────────

    private static final String GLOBAL_URL  =
            "https://api.betterttv.net/3/cached/emotes/global";
    private static final String CHANNEL_URL =
            "https://api.betterttv.net/3/cached/users/twitch/%s";

    /** Plantilla de URL de imagen de la CDN de BTTV (tamaño 1x). */
    private static final String CDN_URL =
            "https://cdn.betterttv.net/emote/%s/1x";

    // ── Estado ────────────────────────────────────────────────────────────────

    /** Mapa de nombre de emote → URL de imagen. ConcurrentHashMap por seguridad. */
    private final Map<String, String> emoteMap = new ConcurrentHashMap<>();

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Carga los emotes globales de BTTV y los del canal indicado.
     *
     * @param twitchChannelId ID numérico del canal de Twitch, o null para
     *                        cargar solo los globales.
     */
    public BTTVEmoteCache(String twitchChannelId) {
        loadGlobal();
        if (twitchChannelId != null && !twitchChannelId.isBlank()) {
            loadChannel(twitchChannelId);
        }
    }

    // ── Carga de emotes ───────────────────────────────────────────────────────

    /** Carga los emotes globales de BTTV desde su API pública. */
    private void loadGlobal() {
        try {
            JSONArray arr = fetchArray(GLOBAL_URL);
            for (int i = 0; i < arr.length(); i++) {
                addEmote(arr.getJSONObject(i));
            }
            System.out.println("[BTTV] " + emoteMap.size() + " emotes globales cargados.");
        } catch (Exception e) {
            System.err.println("[BTTV] Error cargando globales: " + e.getMessage());
        }
    }

    /**
     * Carga los emotes del canal (propios y compartidos) desde la API de BTTV.
     *
     * @param channelId ID numérico del canal de Twitch.
     */
    private void loadChannel(String channelId) {
        try {
            JSONObject data = fetchObject(CHANNEL_URL.formatted(channelId));
            addEmotes(data.optJSONArray("channelEmotes"));
            addEmotes(data.optJSONArray("sharedEmotes"));
        } catch (Exception e) {
            System.err.println("[BTTV] Error cargando canal " + channelId
                    + ": " + e.getMessage());
        }
    }

    /** Añade todos los emotes de un array JSON al mapa. */
    private void addEmotes(JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            addEmote(arr.getJSONObject(i));
        }
    }

    /** Añade un emote individual al mapa. */
    private void addEmote(JSONObject obj) {
        emoteMap.put(obj.getString("code"),
                     CDN_URL.formatted(obj.getString("id")));
    }

    // ── Procesado de tokens ───────────────────────────────────────────────────

    /**
     * Expande los tokens de texto que contengan emotes de BTTV.
     *
     * Recorre la lista de tokens ya procesada por TwitchEmoteCache:
     * - Los EmoteToken.Emote (emotes de Twitch) se pasan sin modificar.
     * - Los EmoteToken.Text se dividen por palabras. Si una palabra coincide
     *   con un emote de BTTV, se sustituye por un EmoteToken.Emote.
     *   Los espacios se preservan como tokens de texto separados.
     *
     * @param tokens Lista de tokens producida por TwitchEmoteCache.
     * @return Nueva lista con los emotes de BTTV expandidos.
     */
    public List<EmoteToken> process(List<EmoteToken> tokens) {
        List<EmoteToken> result = new ArrayList<>();
        for (EmoteToken token : tokens) {
            if (token instanceof EmoteToken.Text text) {
                result.addAll(splitByBTTV(text.content()));
            } else {
                result.add(token); // EmoteToken.Emote: pasar intacto
            }
        }
        return result;
    }

    /**
     * Divide un fragmento de texto en tokens, sustituyendo las palabras
     * que coincidan con emotes de BTTV por EmoteToken.Emote.
     * Los espacios entre palabras se preservan como EmoteToken.Text.
     *
     * @param text Fragmento de texto a procesar.
     * @return Lista de tokens con los emotes de BTTV expandidos.
     */
    private List<EmoteToken> splitByBTTV(String text) {
        // Split conservando los separadores (espacios) como tokens propios
        String[]         words  = text.split("(?<=\\s)|(?=\\s)");
        List<EmoteToken> tokens = new ArrayList<>();

        for (String word : words) {
            String trimmed = word.trim();
            if (!trimmed.isEmpty() && emoteMap.containsKey(trimmed)) {
                tokens.add(new EmoteToken.Emote(trimmed, emoteMap.get(trimmed)));
            } else {
                tokens.add(new EmoteToken.Text(word));
            }
        }
        return tokens;
    }

    // ── Utilidades HTTP ───────────────────────────────────────────────────────

    private JSONArray fetchArray(String url) throws Exception {
        return new JSONArray(fetch(url));
    }

    private JSONObject fetchObject(String url) throws Exception {
        return new JSONObject(fetch(url));
    }

    /**
     * Realiza una petición GET y devuelve el cuerpo como String.
     *
     * @param url URL a consultar.
     * @return Cuerpo de la respuesta en UTF-8.
     * @throws Exception Si la conexión falla o el servidor devuelve error.
     */
    private String fetch(String url) throws Exception {
        HttpURLConnection conn =
                (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "ChatOverlay/1.0");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        try (InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}