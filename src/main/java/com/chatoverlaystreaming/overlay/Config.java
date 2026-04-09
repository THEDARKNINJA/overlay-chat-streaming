package com.chatoverlaystreaming.overlay;

import org.json.JSONObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Config {

    private final JSONObject twitch;
    private final JSONObject youtube;
    private final JSONObject panel;

    public Config() throws IOException {
        Path configPath = Paths.get("config.json");
        if (!Files.exists(configPath)) {
            throw new IOException(
                "No se encontró config.json. " +
                "Crea el archivo en la carpeta del proyecto."
            );
        }
        String content = Files.readString(configPath);
        JSONObject root = new JSONObject(content);
        twitch  = root.getJSONObject("twitch");
        youtube = root.getJSONObject("youtube");
        panel = root.getJSONObject("panel");

    }

    public String getTwitchChannel()   { return twitch.getString("channel");   }
    public String getTwitchChannelId() { return twitch.getString("channelId"); }
    public String getYoutubeVideoId()  { return youtube.getString("videoId");  }
    public String getYoutubeApiKey()   { return youtube.getString("apiKey");   }
    public int getPanelX()   { return panel.getInt("x");   }
    public int getPanelY()   { return panel.getInt("y");   }
    public int getPanelWidth()   { return panel.getInt("width");   }
    public int getPanelHeight()   { return panel.getInt("height");   }
}