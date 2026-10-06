package reobf.mpy4oc.main;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    /** SFTP server port. 0 = pick a free one automatically, -1 = disable SFTP. */
    public static int port = 2222;

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

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

}
