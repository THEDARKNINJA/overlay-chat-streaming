package com.chatoverlaystreaming.overlay;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

// Descargado de emojis custom de YT
//import java.util.Iterator;
//import java.net.URL;


public class Config {

    private final JSONObject root;
    private final JSONObject twitch;
    private final JSONObject twitchRewards;
    private final JSONObject youtube;
    private final JSONObject panel;
    private final JSONObject misc;

    public Config() throws IOException {
        Path configPath = Paths.get("config.json");
        if (!Files.exists(configPath)) {
            throw new IOException(
                "No se encontró config.json. " +
                "Crea el archivo en la carpeta del proyecto."
            );
        }
        String content = Files.readString(configPath);
        root = new JSONObject(content);
        twitch  = root.getJSONObject("twitch");
        youtube = root.getJSONObject("youtube");
        panel = root.getJSONObject("panel");
        misc = root.getJSONObject("misc");
        twitchRewards = getTwitchRewards();
        
    }

    public String getTwitchChannel()   { return twitch.optString("channel", "");   }
    public String getTwitchChannelId() { return twitch.optString("channelId", ""); }
    public String getTwitchClientId() { return twitch.optString("clientId", ""); }
    public String getTwitchClientSecret() { return twitch.optString("clientSecret", ""); }
    public long getTwitchSpectatorUpdate() { return twitch.optLong("timeSpectatorUpdate", 60); }
    public String getTwitchAccessToken() { return twitch.optString("accessToken", null); }
    public String getTwitchRefreshToken() { return twitch.optString("refreshToken", null); }
    public String getRewardType(String rewardId) {
        if (!twitchRewards.has(rewardId)) return null;
        return twitchRewards.getJSONObject(rewardId).optString("type", null);
    }
    public String getRewardFolder(String rewardId) {
        if (!twitchRewards.has(rewardId)) return null;
        return twitchRewards.getJSONObject(rewardId).optString("folder", null);
    }


    public String getYoutubeChannelId()  { return youtube.optString("channelId", "");  }
    public String getYoutubeVideoId()  { return youtube.optString("videoId", "");  }
    public String getYoutubeApiKey()   { return getYoutubeApiKeys().get(0);   }
    public String getYoutubeLastPageToken() { return youtube.optString("lastPageToken", null);  }
    public String getYoutubeLastVideoId() { return youtube.optString("lastVideoId", null); }
    public long getYoutubeSpectatorUpdate()  { return youtube.optLong("timeSpectatorUpdate", 60);  }

    public int getPanelX()   { return panel.optInt("x", 10);   }
    public int getPanelY()   { return panel.optInt("y", 150);   }
    public int getPanelWidth()   { return panel.optInt("width", 400);   }
    public int getPanelHeight()   { return panel.optInt("height", 450);   }
    public int getPanelAlpha()   { return panel.optInt("alpha", 200);   }
    public boolean getShowBackground() { return panel.optBoolean("showBackground", true); }
    public int getIconSize() { return panel.optInt("iconSize", 13); }

    public int getMinPollingInterval() { return misc.optInt("minPollingInterval", 8); }
    public boolean getShowViewerCount() { return misc.optBoolean("showViewerCount", true); }
    public boolean getCanClickLink() { return misc.optBoolean("canClickLink", true); }
    public boolean getLoadBTTV() { return misc.optBoolean("loadBTTV", true); }
    public int getMessageTimeout() { return misc.optInt("messageTimeoutSeconds", 0); } // 0 = no borrar
    public boolean getLogActivity() { return misc.optBoolean("logActivity", false); } // 0 = no borrar

    // Recompensa completa
    public JSONObject getRewardConfig(String rewardId) {
        JSONObject rewards = getTwitchRewards();
        if (!rewards.has(rewardId)) return null;
        return rewards.getJSONObject(rewardId);
    }

    public List<String> getYoutubeApiKeys() {
        // Soportar tanto apiKeys (array) como apiKey (string único)
        if (youtube.has("apiKeys")) {
            org.json.JSONArray keys = youtube.getJSONArray("apiKeys");
            List<String> result = new java.util.ArrayList<>();
            for (int i = 0; i < keys.length(); i++) {
                result.add(keys.getString(i));
            }
            return result;
        } else if (youtube.has("apiKey")) {
            return List.of(youtube.getString("apiKey"));
        }
        throw new RuntimeException("No se encontró ninguna API key de YouTube en el config.");
    }

    public void saveTwitchReward(String rewardId, JSONObject rewardConfig) throws IOException {
        getTwitchRewards().put(rewardId, rewardConfig);
        Files.writeString(Paths.get("config.json"), root.toString(2));
    }

    public void savePanel(int x, int y, int width, int height) throws IOException {
        // Actualizar los valores en el objeto JSON en memoria
        JSONObject panel = root.getJSONObject("panel");
        panel.put("x", x);
        panel.put("y", y);
        panel.put("width", width);
        panel.put("height", height);

        // Escribir al disco con formato legible
        Files.writeString(
            Paths.get("config.json"),
            root.toString(2)  // el 2 es la indentación
        );
    }

    public void saveYouTube(String videoId) throws IOException {
        // Actualizar los valores en el objeto JSON en memoria
        JSONObject panel = root.getJSONObject("youtube");
        panel.put("videoId", videoId);

        // Escribir al disco con formato legible
        Files.writeString(
            Paths.get("config.json"),
            root.toString(2)  // el 2 es la indentación
        );
    }

    public void saveYoutubePageToken(String videoId, String pageToken) throws IOException {
        youtube.put("lastVideoId", videoId);
        youtube.put("lastPageToken", pageToken);
        Files.writeString(Paths.get("config.json"), root.toString(2));
    }


    public void saveTwitchTokens(String accessToken, String refreshToken) throws IOException {
        twitch.put("accessToken", accessToken);
        twitch.put("refreshToken", refreshToken);
        Files.writeString(Paths.get("config.json"), root.toString(2));
    }

    public JSONObject getTwitchRewards() {
        if (!root.has("twitchRewards")) {
            root.put("twitchRewards", new JSONObject());
        }
        return root.getJSONObject("twitchRewards");
    }

    public void saveTwitchReward(String rewardId, String type, String folder) throws IOException {
        JSONObject entry = new JSONObject();
        entry.put("type", type);
        entry.put("folder", folder);
        twitchRewards.put(rewardId, entry);
        Files.writeString(Paths.get("config.json"), root.toString(2));
    }

    public void deleteTwitchReward(String rewardId) throws IOException {
        twitchRewards.remove(rewardId);
        Files.writeString(Paths.get("config.json"), root.toString(2));
    }

    // Si se van a descargar nuevos emojis de youtube,
    // rehacer el JSON y descomentar esto, los imports de arriba
    // y la línea del Main
    /* 
    public void descargarImagenesDesdeJsonObject() {
        try {

        Path configPath = Paths.get("youtube_custom_emojis.json");
        if (!Files.exists(configPath)) {
            throw new IOException(
                "No se encontró config.json. " +
                "Crea el archivo en la carpeta del proyecto."
            );
        }
        String content = Files.readString(configPath);
        JSONObject json = new JSONObject(content);

            // Crear carpeta imgs si no existe
            File carpeta = new File("youtube_emojis");
            if (!carpeta.exists()) {
                carpeta.mkdirs();
            }

            // Iterar keys del JSON
            Iterator<String> keys = json.keys();

            while (keys.hasNext()) {
                String key = keys.next();
                String url = json.getString(key);

                try (InputStream in = new URL(url).openStream()) {

                    // Limpiar caracteres inválidos en nombre de archivo
                    String safeKey = key.replaceAll("[:\\\\/*?\"<>|]", "");

                    // Detectar extensión básica
                    String extension = ".png";

                    String fileName = "youtube_emojis/" + safeKey.replaceAll("^:+|:+$", "") + extension;

                    Files.copy(in, Paths.get(fileName));

                    System.out.println("Descargado: " + fileName);

                } catch (Exception e) {
                    System.err.println("Error con " + key + ": " + e.getMessage());
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    */
    
}