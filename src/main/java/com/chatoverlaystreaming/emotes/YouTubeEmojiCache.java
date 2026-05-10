package com.chatoverlaystreaming.emotes;

import com.chatoverlaystreaming.model.EmoteToken;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Caché y tokenizador de emojis de YouTube.
 *
 * Gestiona dos tipos de emojis:
 *
 * 1. Emojis custom de YouTube (ej. ":hand-pink-waving:"):
 *    Imágenes PNG descargadas previamente en {@code youtube_emojis/}.
 *    Se cargan desde disco bajo demanda y se inyectan en el {@link ImageCache}
 *    compartido con la clave {@code "local:key"} para que EmoteRenderer las pinte.
 *
 * 2. Emojis Unicode estándar (ej. ":grinning_face:"):
 *    Mapeados a caracteres Unicode en {@code youtube_unicode_chars.json}.
 *    Se renderizan directamente con la fuente Segoe UI Emoji sin imagen adicional.
 *
 * Flujo de tokenización:
 *   YouTube API v3 devuelve los mensajes como "messageRuns": una lista de objetos
 *   con campos "text" o "emoji". Si el mensaje viene como texto plano (sin runs),
 *   se genera una lista de runs artificial buscando patrones ":key:" con regex.
 *
 * Resolución de emojis en parseMessageRuns:
 *   1. Buscar imagen local en youtube_emojis/ → EmoteToken.Emote("local:key").
 *   2. Buscar en mapa Unicode → EmoteToken.Text(carácter Unicode).
 *   3. Fallback: usar el emojiId raw como texto.
 */
public class YouTubeEmojiCache {

    // ── Constantes ────────────────────────────────────────────────────────────

    /** Carpeta con las imágenes de emojis custom descargadas, relativa al exe. */
    private static final Path    EMOJI_DIR     = Paths.get("youtube_emojis");
    private static final int     EMOJI_SIZE    = 24;
    private static final String  UNICODE_JSON  = "youtube_unicode_chars.json";

    /** Regex para detectar claves de emoji con formato ":key:" en texto plano. */
    private static final Pattern EMOJI_PATTERN = Pattern.compile(":[^:]+:");

    /** Extensiones buscadas al cargar imágenes locales, en orden de preferencia. */
    private static final List<String> IMAGE_EXTENSIONS = List.of(".png", ".webp", ".gif");

    // ── Estado ────────────────────────────────────────────────────────────────

    /** Mapa de clave ":key:" → carácter Unicode. Cargado desde youtube_unicode_chars.json. */
    private final Map<String, String> unicodeMap = new HashMap<>();

    /** Cache compartido con EmoteRenderer donde se inyectan los iconos locales. */
    private final ImageCache imageCache;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param imageCache Cache compartido con EmoteRenderer. Los emojis locales se
     *                   inyectan aquí con clave "local:key" para que EmoteRenderer
     *                   los encuentre al renderizar.
     */
    public YouTubeEmojiCache(ImageCache imageCache) {
        this.imageCache = imageCache;
        loadUnicodeMap();
    }

    // ── Carga del mapa Unicode ────────────────────────────────────────────────

    /**
     * Carga el mapa de clave → carácter Unicode desde youtube_unicode_chars.json.
     * Busca primero en los resources del jar (para desarrollo) y luego
     * junto al exe (para producción).
     */
    private void loadUnicodeMap() {
        try (InputStream is = openUnicodeJson()) {
            if (is == null) {
                System.err.println("[YouTubeEmoji] No se encontró " + UNICODE_JSON);
                return;
            }
            JSONObject json = new JSONObject(
                    new String(is.readAllBytes(), StandardCharsets.UTF_8));
            for (String key : json.keySet()) {
                unicodeMap.put(key, json.getString(key));
            }
            System.out.println("[YouTubeEmoji] " + unicodeMap.size()
                    + " emojis Unicode cargados.");
        } catch (Exception e) {
            System.err.println("[YouTubeEmoji] Error cargando unicode map: " + e.getMessage());
        }
    }

    /**
     * Abre el archivo youtube_unicode_chars.json desde resources del jar o desde disco.
     * Devuelve null si no se encuentra en ninguna ubicación.
     */
    private InputStream openUnicodeJson() throws Exception {
        InputStream is = getClass().getResourceAsStream("/" + UNICODE_JSON);
        if (is != null) return is;

        Path path = Paths.get(UNICODE_JSON);
        if (Files.exists(path)) return new FileInputStream(path.toFile());

        return null;
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Tokeniza el snippet de un mensaje de YouTube en una lista de EmoteTokens.
     *
     * Si el snippet contiene "messageRuns", los parsea directamente.
     * Si solo contiene "messageText" (texto plano), genera runs artificiales
     * buscando patrones ":key:" con regex.
     *
     * @param snippet JSONObject "snippet" de un item de liveChat.messages.
     * @return Lista de tokens en orden de aparición en el mensaje.
     */
    public List<EmoteToken> tokenize(JSONObject snippet) {
        try {
            JSONObject textMessageDetails = snippet.optJSONObject("textMessageDetails");
            if (textMessageDetails == null) return List.of();

            JSONArray messageRuns = textMessageDetails.optJSONArray("messageRuns");
            if (messageRuns != null && messageRuns.length() > 0) {
                return parseMessageRuns(messageRuns);
            }

            String text = textMessageDetails.optString("messageText", "");
            if (!text.isBlank()) {
                return parseMessageRuns(buildRunsFromPlainText(text));
            }
        } catch (Exception e) {
            System.err.println("[YouTubeEmoji] Error parseando mensaje: " + e.getMessage());
        }
        return List.of();
    }

    /**
     * Resuelve una clave de emoji a un EmoteToken.
     * Útil para resolver emojis individuales fuera del flujo de tokenización.
     * Busca primero imagen local, luego carácter Unicode.
     *
     * @param key Clave del emoji, con o sin dos puntos (ej. ":hand-pink-waving:" o "hand-pink-waving").
     * @return EmoteToken.Emote si hay imagen local, EmoteToken.Text si hay Unicode, null si no se encuentra.
     */
    public EmoteToken resolve(String key) {
        if (key == null || key.isBlank()) return null;
        String normalizedKey = key.startsWith(":") ? key : ":" + key + ":";

        ImageIcon icon = loadLocalImage(normalizedKey);
        if (icon != null) return new EmoteToken.Emote(normalizedKey, "local:" + normalizedKey);

        String unicodeChar = unicodeMap.get(normalizedKey);
        if (unicodeChar != null) return new EmoteToken.Text(unicodeChar);

        return null;
    }

    // ── Parseo de runs ────────────────────────────────────────────────────────

    /**
     * Parsea una lista de messageRuns de la API de YouTube en tokens.
     * Cada run es o bien un objeto de texto o bien un objeto de emoji.
     */
    private List<EmoteToken> parseMessageRuns(JSONArray runs) {
        List<EmoteToken> tokens = new ArrayList<>();

        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.getJSONObject(i);

            if (run.has("text")) {
                String text = run.getString("text");
                if (!text.isEmpty()) tokens.add(new EmoteToken.Text(text));

            } else if (run.has("emoji")) {
                EmoteToken token = resolveEmojiRun(run.getJSONObject("emoji"));
                if (token != null) tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * Resuelve un objeto emoji de un messageRun a un EmoteToken.
     * Prioridad: imagen local → Unicode → emojiId como texto.
     *
     * @param emoji JSONObject "emoji" de un messageRun.
     * @return EmoteToken resuelto, o null si no se puede resolver.
     */
    private EmoteToken resolveEmojiRun(JSONObject emoji) {
        String key = extractEmojiKey(emoji);
        if (key == null) return null;

        // 1. Imagen local
        ImageIcon localIcon = loadLocalImage(key);
        if (localIcon != null) {
            String url = "local:" + key;
            imageCache.put(url, localIcon);
            return new EmoteToken.Emote(key, url);
        }

        // 2. Carácter Unicode
        String unicodeChar = unicodeMap.get(key);
        if (unicodeChar != null) return new EmoteToken.Text(unicodeChar);

        // 3. Fallback: emojiId como texto
        String emojiId = emoji.optString("emojiId", null);
        if (emojiId != null) return new EmoteToken.Text(emojiId);

        return null;
    }

    /**
     * Extrae la clave del emoji de un objeto emoji de messageRun.
     * Usa el primer shortcut si existe, o construye ":emojiId:" como fallback.
     */
    private String extractEmojiKey(JSONObject emoji) {
        JSONArray shortcuts = emoji.optJSONArray("shortcuts");
        if (shortcuts != null && shortcuts.length() > 0) {
            return shortcuts.getString(0);
        }
        String emojiId = emoji.optString("emojiId", null);
        return emojiId != null ? ":" + emojiId + ":" : null;
    }

    /**
     * Convierte texto plano en una lista de messageRuns artificial.
     * Detecta patrones ":key:" con regex y los convierte en runs de emoji.
     * El texto entre emojis se convierte en runs de texto.
     *
     * @param text Texto plano del mensaje.
     * @return JSONArray de runs compatible con {@link #parseMessageRuns}.
     */
    private JSONArray buildRunsFromPlainText(String text) {
        JSONArray runs   = new JSONArray();
        Matcher   m      = EMOJI_PATTERN.matcher(text);
        int       lastEnd = 0;

        while (m.find()) {
            // Texto antes del emoji
            if (m.start() > lastEnd) {
                runs.put(new JSONObject().put("text", text.substring(lastEnd, m.start())));
            }

            // Run de emoji con el shortcut
            String emojiKey = text.substring(m.start(), m.end());
            runs.put(new JSONObject().put("emoji",
                    new JSONObject().put("shortcuts",
                            new JSONArray().put(emojiKey))));

            lastEnd = m.end();
        }

        // Texto después del último emoji
        if (lastEnd < text.length()) {
            runs.put(new JSONObject().put("text", text.substring(lastEnd)));
        }

        return runs;
    }

    // ── Carga de imágenes locales ─────────────────────────────────────────────

    /**
     * Carga la imagen local del emoji desde youtube_emojis/.
     * El nombre del archivo es la clave sin los dos puntos.
     * Busca en orden: .png, .webp, .gif.
     *
     * Si la imagen ya está en el ImageCache compartido, la devuelve directamente
     * sin releer el disco.
     *
     * @param key Clave del emoji con dos puntos (ej. ":hand-pink-waving:").
     * @return ImageIcon escalado a EMOJI_SIZE, o null si no se encuentra.
     */
    private ImageIcon loadLocalImage(String key) {
        String url = "local:" + key;

        // Comprobar en caché compartido primero
        ImageIcon cached = imageCache.get(url);
        if (cached != null) return cached;

        String filename = key.replace(":", "");

        for (String ext : IMAGE_EXTENSIONS) {
            Path filePath = EMOJI_DIR.resolve(filename + ext);
            if (!Files.exists(filePath)) continue;

            try {
                BufferedImage img = ImageIO.read(filePath.toFile());
                if (img == null) continue;

                int scaledWidth = (int)((double) img.getWidth() / img.getHeight() * EMOJI_SIZE);
                Image     scaled = img.getScaledInstance(scaledWidth, EMOJI_SIZE, Image.SCALE_SMOOTH);
                ImageIcon icon   = new ImageIcon(scaled);
                imageCache.put(url, icon);
                return icon;

            } catch (Exception e) {
                System.err.println("[YouTubeEmoji] Error cargando: " + filePath);
            }
        }

        return null;
    }
}