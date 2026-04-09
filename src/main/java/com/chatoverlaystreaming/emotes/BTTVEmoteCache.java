package com.chatoverlaystreaming.emotes;

import com.chatoverlaystreaming.model.EmoteToken;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.*;

public class BTTVEmoteCache {

    private static final String GLOBAL_URL  =
            "https://api.betterttv.net/3/cached/emotes/global";
    private static final String CHANNEL_URL =
            "https://api.betterttv.net/3/cached/users/twitch/%s";
    private static final String CDN         =
            "https://cdn.betterttv.net/emote/%s/1x";

    // nombre del emote -> URL de la imagen
    private final Map<String, String> emoteMap = new ConcurrentHashMap<>();

    public BTTVEmoteCache(String twitchChannelId) {
        loadGlobal();
        if (twitchChannelId != null && !twitchChannelId.isBlank()) {
            loadChannel(twitchChannelId);
        }
    }

    private void loadGlobal() {
        try {
            JSONArray arr = fetchArray(GLOBAL_URL);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                emoteMap.put(obj.getString("code"),
                             CDN.formatted(obj.getString("id")));
            }
            System.out.println("[BTTV] " + emoteMap.size() + " emotes globales cargados.");
        } catch (Exception e) {
            System.err.println("[BTTV] Error cargando globales: " + e.getMessage());
        }
    }

    private void loadChannel(String channelId) {
        try {
            JSONObject data = fetchObject(CHANNEL_URL.formatted(channelId));
            addEmotes(data.optJSONArray("channelEmotes"));
            addEmotes(data.optJSONArray("sharedEmotes"));
        } catch (Exception e) {
            System.err.println("[BTTV] Error cargando canal " + channelId + ": " + e.getMessage());
        }
    }

    private void addEmotes(JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            emoteMap.put(obj.getString("code"),
                         CDN.formatted(obj.getString("id")));
        }
    }

    /**
     * Toma una lista de tokens ya procesada por Twitch y sustituye
     * los tokens de texto que contengan emotes de BTTV.
     */
    public List<EmoteToken> process(List<EmoteToken> tokens) {
        List<EmoteToken> result = new ArrayList<>();
        for (EmoteToken token : tokens) {
            if (token instanceof EmoteToken.Text text) {
                result.addAll(splitByBTTV(text.content()));
            } else {
                result.add(token);
            }
        }
        return result;
    }

    private List<EmoteToken> splitByBTTV(String text) {
        List<EmoteToken> tokens = new ArrayList<>();
        String[] words = text.split("(?<=\\s)|(?=\\s)"); // split conservando espacios
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

    // ── Utilidades HTTP ─────────────────────────────────────────────────────

    private JSONArray fetchArray(String url) throws Exception {
        return new JSONArray(fetch(url));
    }

    private JSONObject fetchObject(String url) throws Exception {
        return new JSONObject(fetch(url));
    }

    private String fetch(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "ChatOverlay/1.0");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        try (InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}