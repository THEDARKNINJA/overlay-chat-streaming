package com.chatoverlaystreaming.emotes;

import com.chatoverlaystreaming.model.EmoteToken;
import org.json.*;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YouTubeEmojiCache {

    // Carpeta con las imágenes descargadas, relativa al jar
    private static final Path EMOJI_DIR = Paths.get("youtube_emojis");
    private static final int EMOJI_SIZE = 24;
    private static final Pattern EMOJI_PATTERN = Pattern.compile(":[^:]+:");


    // Mapa de key -> carácter Unicode (cargado desde JSON)
    private final Map<String, String> unicodeMap = new HashMap<>();

    // Caché en memoria de imágenes ya cargadas desde disco
    // private final Map<String, ImageIcon> imageCache = new HashMap<>();
    private final ImageCache imageCache;
    //private final Map<String, ImageIcon> localCache = new HashMap<>();

    public YouTubeEmojiCache(ImageCache imageCache) {
        this.imageCache = imageCache;
        loadUnicodeMap();
    }

    private void loadUnicodeMap() {
        try {
            // Intentar cargar desde recursos del jar primero
            InputStream is = getClass().getResourceAsStream("/youtube_unicode_chars.json");
            if (is == null) {
                // Fallback: buscar junto al jar
                Path path = Paths.get("youtube_unicode_chars.json");
                if (!Files.exists(path)) {
                    System.err.println("[YouTubeEmoji] No se encontró youtube_unicode_chars.json");
                    return;
                }
                is = new FileInputStream(path.toFile());
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(content);
            for (String key : json.keySet()) {
                unicodeMap.put(key, json.getString(key));
            }
            System.out.println("[YouTubeEmoji] " + unicodeMap.size() + " emojis Unicode cargados.");
        } catch (Exception e) {
            System.err.println("[YouTubeEmoji] Error cargando unicode map: " + e.getMessage());
        }
    }

    /**
     * Resuelve una key de emoji de YouTube a un EmoteToken.
     * Busca primero imagen local, luego carácter Unicode.
     * Devuelve null si no encuentra nada.
     */
    public EmoteToken resolve(String key) {
        if (key == null || key.isBlank()) return null;

        // Normalizar la key: asegurarse de que tiene los dos puntos
        String normalizedKey = key.startsWith(":") ? key : ":" + key + ":";

        // 1. Buscar imagen local en youtube_emojis/
        ImageIcon icon = loadLocalImage(normalizedKey);
        if (icon != null) {
            /*
            return new EmoteToken.Emote(normalizedKey, null) {
                // Subclase anónima no es lo ideal, mejor usar un campo especial
                // Ver nota abajo sobre cómo pasar el icon
            }; */
            return new EmoteToken.Emote(normalizedKey, "local:" + normalizedKey);
        }

        // 2. Buscar en mapa Unicode
        String unicodeChar = unicodeMap.get(normalizedKey);
        if (unicodeChar != null) {
            return new EmoteToken.Text(unicodeChar);
        }

        return null;
    }

    /**
     * Carga una imagen local desde la carpeta youtube_emojis/.
     * El nombre del archivo es la key sin los dos puntos, con extensión .png o .webp.
     */
    private ImageIcon loadLocalImage(String key) {
        String filename = key.replace(":", "");
        String url = "local:" + key;

        // Comprobar en caché compartido primero
        ImageIcon cached = imageCache.get(url);
        if (cached != null) return cached;

        // Buscar el archivo en disco
        for (String ext : List.of(".png", ".webp", ".gif")) {
            Path filePath = EMOJI_DIR.resolve(filename + ext);
            if (Files.exists(filePath)) {
                try {
                    BufferedImage img = ImageIO.read(filePath.toFile());
                    if (img != null) {
                        int scaledWidth = (int) ((double) img.getWidth() / img.getHeight() * EMOJI_SIZE);
                        Image scaled = img.getScaledInstance(scaledWidth, EMOJI_SIZE, Image.SCALE_SMOOTH);
                        ImageIcon icon = new ImageIcon(scaled);
                        imageCache.put(url, icon);
                        return icon;
                    }
                } catch (Exception e) {
                    System.err.println("[YouTubeEmoji] Error cargando imagen: " + filePath);
                }
            }
        }

        return null;
    }

    /**
     * Tokeniza un snippet de mensaje de YouTube.
     */
    public List<EmoteToken> tokenize(org.json.JSONObject snippet) {
        List<EmoteToken> tokens = new ArrayList<>();
        try {
            JSONObject textMessageDetails = snippet.optJSONObject("textMessageDetails");
            if (textMessageDetails == null) return tokens;

            JSONArray messageRuns = textMessageDetails.optJSONArray("messageRuns");
            if (messageRuns != null && messageRuns.length() > 0) {
                return parseMessageRuns(messageRuns);
            }
/*
            String text = textMessageDetails.optString("messageText", "");
            if (!text.isBlank()) {
                tokens.add(new EmoteToken.Text(text));
            }
                */
            String text = textMessageDetails.optString("messageText", "");
            if (!text.isBlank()) {
                JSONArray generatedRuns = buildMessageRunsFromPlainText(text);
                return parseMessageRuns(generatedRuns);
            }

        } catch (Exception e) {
            System.err.println("[YouTubeEmoji] Error parseando mensaje: " + e.getMessage());
        }

        return tokens;
    }

    private JSONArray buildMessageRunsFromPlainText(String text) {
        JSONArray runs = new JSONArray();
        Matcher m = EMOJI_PATTERN.matcher(text);

        int lastEnd = 0;

        while (m.find()) {
            int start = m.start();
            int end = m.end();

            // Texto antes del emoji
            if (start > lastEnd) {
                String before = text.substring(lastEnd, start);
                if (!before.isEmpty()) {
                    JSONObject textObj = new JSONObject();
                    textObj.put("text", before);
                    runs.put(textObj);
                }
            }

            // El emoji completo, incluyendo los dos puntos
            String emojiKey = text.substring(start, end);

            JSONObject emojiObj = new JSONObject();
            JSONObject emojiData = new JSONObject();
            emojiData.put("shortcuts", new JSONArray().put(emojiKey));
            emojiObj.put("emoji", emojiData);

            runs.put(emojiObj);

            lastEnd = end;
        }

        // Texto después del último emoji
        if (lastEnd < text.length()) {
            String after = text.substring(lastEnd);
            if (!after.isEmpty()) {
                JSONObject textObj = new JSONObject();
                textObj.put("text", after);
                runs.put(textObj);
            }
        }

        return runs;
    }


    private List<EmoteToken> parseMessageRuns(JSONArray runs) {
        List<EmoteToken> tokens = new ArrayList<>();

        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.getJSONObject(i);

            if (run.has("text")) {
                String text = run.getString("text");
                if (!text.isEmpty()) tokens.add(new EmoteToken.Text(text));

            } else if (run.has("emoji")) {
                JSONObject emoji = run.getJSONObject("emoji");
                String emojiKey = extractEmojiKey(emoji);

                if (emojiKey != null) {
                    // Intentar resolver la key
                    ImageIcon localIcon = loadLocalImage(emojiKey);
                    /*
                    if (localIcon != null) {
                        // Hay imagen local: crear Emote con URL especial para distinguirlo
                        final ImageIcon iconRef = localIcon;
                        // Guardamos el icon en el cache con la key como identificador
                        imageCache.put(emojiKey.replace(":", ""), iconRef);
                        tokens.add(new EmoteToken.Emote(emojiKey, "local:" + emojiKey));
                    } */
                    if (localIcon != null) {
                        String url = "local:" + emojiKey;
                        imageCache.put(url, localIcon);  // inyectar en el cache de EmoteRenderer
                        tokens.add(new EmoteToken.Emote(emojiKey, url));
                    } else {
                        // Buscar en Unicode
                        String unicodeChar = unicodeMap.get(emojiKey);
                        if (unicodeChar != null) {
                            tokens.add(new EmoteToken.Text(unicodeChar));
                        } else {
                            // Último fallback: emojiId directo
                            String emojiId = emoji.optString("emojiId", null);
                            if (emojiId != null) tokens.add(new EmoteToken.Text(emojiId));
                        }
                    }
                }
            }
        }

        return tokens;
    }

    private String extractEmojiKey(JSONObject emoji) {
        // Intentar obtener el shortcut
        JSONArray shortcuts = emoji.optJSONArray("shortcuts");
        if (shortcuts != null && shortcuts.length() > 0) {
            return shortcuts.getString(0);
        }
        // Fallback al emojiId
        String emojiId = emoji.optString("emojiId", null);
        if (emojiId != null) return ":" + emojiId + ":";
        return null;
    }

    /**
     * Devuelve el ImageIcon cacheado para un token Emote con URL "local:key".
     * Llamado desde EmoteRenderer para obtener la imagen sin pasar por ImageCache.
     */
    public ImageIcon getLocalIcon(String key) {
        String filename = key.replace(":", "");
        return imageCache.get(filename);
    }
}