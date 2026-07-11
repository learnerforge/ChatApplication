package chat.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central configuration for the chat application.
 * Loads values from {@code config.properties} on the classpath,
 * falling back to built-in defaults when keys are missing.
 */
public final class Config {

    private static final Logger LOG = Logger.getLogger(Config.class.getName());
    private static final Properties PROPS = new Properties();

    static {
        try (InputStream is = Config.class.getResourceAsStream("/config.properties")) {
            if (is != null) {
                PROPS.load(is);
                LOG.info("Loaded config.properties from classpath");
            } else {
                LOG.info("No config.properties found — using built-in defaults");
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to load config.properties", e);
        }
    }

    // ── Server ──────────────────────────────────────────────
    public static final int PORT = getInt("server.port", 5000);
    public static final String HOST = getString("server.host", "0.0.0.0");
    public static final int MAX_CLIENTS = getInt("server.max_clients", 100);
    public static final int BACKLOG = getInt("server.backlog", 50);

    // ── Database ────────────────────────────────────────────
    public static final String DB_URL = getString("database.url", "jdbc:sqlite:chatapp.db");

    // ── History ─────────────────────────────────────────────
    public static final int HISTORY_LIMIT = getInt("history.limit", 50);

    // ── TLS / SSL ───────────────────────────────────────────
    public static final boolean SSL_ENABLED  = getBoolean("server.ssl.enabled", false);
    public static final String  SSL_KEYSTORE = getString("server.ssl.keystore", "chatapp.jks");
    public static final String  SSL_KEYSTORE_PASSWORD = getString("server.ssl.keystore.password", "changeit");
    public static final int     SSL_PORT     = getInt("server.ssl.port", 5443);

    // ── UI (web) ────────────────────────────────────────────
    public static final String APP_NAME = "Real-Time Chat";

    // ── Colours (used by both server log and client theming) ──
    public static final String COLOR_PRIMARY = "#00AAFF";
    public static final String COLOR_SUCCESS = "#00C853";
    public static final String COLOR_ERROR   = "#FF5252";
    public static final String COLOR_BG      = "#121212";
    public static final String COLOR_CARD    = "#1E1E1E";
    public static final String COLOR_SECONDARY = "#2C2C2C";

    // ── Helpers ─────────────────────────────────────────────
    private Config() { }

    private static String getString(String key, String def) {
        return PROPS.getProperty(key, def);
    }

    private static int getInt(String key, int def) {
        String val = PROPS.getProperty(key);
        if (val == null) return def;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            LOG.log(Level.WARNING, "Invalid integer for config key '{0}': {1}", new Object[]{key, val});
            return def;
        }
    }

    private static boolean getBoolean(String key, boolean def) {
        String val = PROPS.getProperty(key);
        if (val == null) return def;
        return Boolean.parseBoolean(val.trim());
    }

    /** Pretty-print the active configuration to the server log. */
    public static String toSummary() {
        return String.format(
            "Config { port=%d, host='%s', maxClients=%d, db='%s', historyLimit=%d, ssl=%b, sslPort=%d }",
            PORT, HOST, MAX_CLIENTS, DB_URL, HISTORY_LIMIT, SSL_ENABLED, SSL_PORT
        );
    }
}
