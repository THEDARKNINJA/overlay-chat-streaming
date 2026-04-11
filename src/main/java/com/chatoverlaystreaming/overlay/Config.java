package com.chatoverlaystreaming.overlay;

import org.json.JSONObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class Config {

    private final JSONObject root;
    private final JSONObject twitch;
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

    }

    public String getTwitchChannel()   { return twitch.getString("channel");   }
    public String getTwitchChannelId() { return twitch.getString("channelId"); }
    public String getTwitchClientId() { return twitch.getString("clientId"); }
    public String getTwitchClientSecret() { return twitch.getString("clientSecret"); }
    public String getYoutubeChannelId()  { return youtube.getString("channelId");  }
    public String getYoutubeVideoId()  { return youtube.getString("videoId");  }
    public String getYoutubeApiKey()   { return getYoutubeApiKeys().get(0);   }
    public String getYoutubePageToken() { return youtube.getString("getPageToken"); }
    public int getPanelX()   { return panel.getInt("x");   }
    public int getPanelY()   { return panel.getInt("y");   }
    public int getPanelWidth()   { return panel.getInt("width");   }
    public int getPanelHeight()   { return panel.getInt("height");   }
    public int getPanelAlpha()   { return panel.getInt("alpha");   }
    public boolean getShowBackground() { return panel.getBoolean("showBackground"); }
    public int getIconSize() { return panel.getInt("iconSize"); }
    public int getMinPollingInterval() { return misc.getInt("minPollingInterval"); }

    public List<String> getYoutubeApiKeys() {
        org.json.JSONArray keys = youtube.getJSONArray("apiKeys");
        List<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < keys.length(); i++) {
            result.add(keys.getString(i));
        }
        return result;
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

    public void saveYouTubePageToken(String pageToken) throws IOException {
        // Actualizar los valores en el objeto JSON en memoria
        JSONObject panel = root.getJSONObject("youtube");
        panel.put("pageToken", pageToken);

        // Escribir al disco con formato legible
        Files.writeString(
            Paths.get("config.json"),
            root.toString(2)  // el 2 es la indentación
        );
    }
}