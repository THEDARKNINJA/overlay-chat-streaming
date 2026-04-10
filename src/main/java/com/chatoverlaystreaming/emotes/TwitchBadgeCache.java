package com.chatoverlaystreaming.emotes;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TwitchBadgeCache {

    private static final String GLOBAL_URL  =
            "https://api.twitch.tv/helix/chat/badges/global";
    private static final String CHANNEL_URL =
            "https://api.twitch.tv/helix/chat/badges?broadcaster_id=%s";

    // "moderator/1" -> URL de la imagen
    private final Map<String, String> badgeMap = new ConcurrentHashMap<>();
    private final String clientId;
    private final String clientSecret;

    private String cachedToken = null;

    public TwitchBadgeCache(String clientId, String clientSecret, String channelId) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        loadGlobal();
        if (channelId != null && !channelId.isBlank()) {
            loadChannel(channelId);
        }
    }

    private void loadGlobal() {
        try {
            JSONObject response = fetchObject(GLOBAL_URL);
            parseBadges(response);
            System.out.println("[Badges] " + badgeMap.size() + " badges globales cargados.");
        } catch (Exception e) {
            System.err.println("[Badges] Error cargando globales: " + e.getMessage());
        }
    }

    private void loadChannel(String channelId) {
        try {
            JSONObject response = fetchObject(CHANNEL_URL.formatted(channelId));
            parseBadges(response);
        } catch (Exception e) {
            System.err.println("[Badges] Error cargando badges del canal: " + e.getMessage());
        }
    }

    private void parseBadges(JSONObject response) {
        JSONArray data = response.getJSONArray("data");
        for (int i = 0; i < data.length(); i++) {
            JSONObject badgeSet = data.getJSONObject(i);
            String setId = badgeSet.getString("set_id"); // ej: "moderator"
            JSONArray versions = badgeSet.getJSONArray("versions");
            for (int j = 0; j < versions.length(); j++) {
                JSONObject version = versions.getJSONObject(j);
                String versionId = version.getString("id"); // ej: "1"
                String imageUrl  = version.getString("image_url_1x");
                // Clave: "moderator/1", igual que en las cabeceras IRC
                badgeMap.put(setId + "/" + versionId, imageUrl);
            }
        }
    }

    /**
     * Parsea la cabecera "badges" del IRC y devuelve las URLs de las imágenes.
     * Formato de entrada: "moderator/1,subscriber/12,vip/1"
     */
    public java.util.List<String> getBadgeUrls(String badgesHeader) {
        java.util.List<String> urls = new java.util.ArrayList<>();
        if (badgesHeader == null || badgesHeader.isBlank()) return urls;
        for (String badge : badgesHeader.split(",")) {
            String url = badgeMap.get(badge.trim());
            if (url != null) urls.add(url);
        }
        return urls;
    }

    private JSONObject fetchObject(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("Client-Id", clientId);
        conn.setRequestProperty("Authorization", "Bearer " + getAppToken());
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        try (InputStream is = conn.getInputStream()) {
            return new JSONObject(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // App token sin usuario — solo necesita Client-ID y Client-Secret
    // Esto requiere el Client-Secret de tu aplicación de Twitch
    /*
    private String getAppToken() throws Exception {
        String url = "https://id.twitch.tv/oauth2/token";
        String body = "client_id=" + clientId +
                    "&client_secret=" + clientSecret +
                    "&grant_type=client_credentials";

        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

        try (InputStream is = conn.getInputStream()) {
            JSONObject response = new JSONObject(
                new String(is.readAllBytes(), StandardCharsets.UTF_8));
            return response.getString("access_token");
        }
    }
         */
    private String getAppToken() throws Exception {
        if (cachedToken != null) return cachedToken;

        System.err.println("[Badges] Usando clientId: '" + clientId + "'");
        System.err.println("[Badges] Longitud clientId: " + clientId.length());
        System.err.println("[Badges] Longitud clientSecret: " + clientSecret.length());

        String url = "https://id.twitch.tv/oauth2/token";
        String body = "client_id=" + clientId.trim() +
                    "&client_secret=" + clientSecret.trim() +
                    "&grant_type=client_credentials";

        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            // Leer el cuerpo del error para ver qué dice Twitch exactamente
            try (InputStream err = conn.getErrorStream()) {
                String errorBody = err != null
                    ? new String(err.readAllBytes(), StandardCharsets.UTF_8)
                    : "sin detalle";
                throw new Exception("HTTP " + responseCode + ": " + errorBody);
            }
        }

        try (InputStream is = conn.getInputStream()) {
            JSONObject response = new JSONObject(
                new String(is.readAllBytes(), StandardCharsets.UTF_8));
            cachedToken = response.getString("access_token");
            return cachedToken;
        }
    }
}