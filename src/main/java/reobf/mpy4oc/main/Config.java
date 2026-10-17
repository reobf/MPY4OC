package reobf.mpy4oc.main;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    /** SFTP server port. 0 = pick a free one automatically, -1 = disable SFTP. */
    public static int port = 2222;
    public static int webPort = 8080;
    public static String webBind = "127.0.0.1", webKeyStore = "", webKeyStorePassword = "";
    public static boolean webUwsgi = false;
    public static boolean tkSurfaceEnabled = true;
    public static int tkSurfaceDistance = 32;

    /** The live Configuration, kept so the in-game GUI edits the same instance. */
    public static Configuration configuration;

    public static void synchronizeConfiguration(File configFile) {
        if (configuration == null) configuration = new Configuration(configFile);
        loadFromConfig();
    }

    /** (Re)read every value. Called at startup and again after an in-game edit. */
    public static void loadFromConfig() {
        // Properties are fetched (not just read) so each can carry a language key: the
        // in-game config screen then shows a translated label and tooltip instead of the
        // raw property name. See assets/mpy4oc/lang/*.lang.
        net.minecraftforge.common.config.Property portProp = configuration.get(
                Configuration.CATEGORY_GENERAL, "SFTP_Port", port,
                "SFTP Port. 0 for auto, -1 to disable", -1, 65535);
        portProp.setLanguageKey("mpy4oc.config.sftpPort");
        port = portProp.getInt(port);
        net.minecraftforge.common.config.Property surface = configuration.get(Configuration.CATEGORY_GENERAL,
                "Tk_Surface_Enabled", true, "Render Tk display contents on block faces. Disabling on a server also stops surface synchronization; GUI remains available.");
        surface.setLanguageKey("mpy4oc.config.tkSurfaceEnabled");
        tkSurfaceEnabled = surface.getBoolean(true);
        net.minecraftforge.common.config.Property distance = configuration.get(Configuration.CATEGORY_GENERAL,
                "Tk_Surface_Distance", 32, "Maximum Tk surface viewing distance in blocks. Both client and server limits apply.", 8, 64);
        distance.setLanguageKey("mpy4oc.config.tkSurfaceDistance");
        tkSurfaceDistance = Math.max(8, Math.min(64, distance.getInt(32)));

        webPort = configuration.get("web", "Port", 8080, "Shared Web Server Card port; -1 disables, 0 selects a free port. Restart required.", -1, 65535).getInt();
        webBind = configuration.get("web", "Bind", "127.0.0.1", "Listener address. Use 0.0.0.0 to accept remote connections. Restart required.").getString();
        webKeyStore = configuration.get("web", "KeyStore", "", "PKCS12 certificate and private key file. Empty uses plain HTTP for a local TLS reverse proxy. Restart required.").getString();
        webKeyStorePassword = configuration.get("web", "KeyStorePassword", "", "Password for the PKCS12 key store. Restart required.").getString();
        webUwsgi = configuration.get("web", "Uwsgi", false, "Also accept uwsgi modifier 0 packets on the shared port; HTTP_AUTHORIZATION must contain Basic credentials. Restart required.").getBoolean();

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

}
