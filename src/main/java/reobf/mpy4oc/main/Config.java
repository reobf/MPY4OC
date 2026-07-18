package reobf.mpy4oc.main;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    /** Backend that turns Python source into .mpy bytecode. */
    public static final String BACKEND_JAVA = "java";
    public static final String BACKEND_BINARY = "binary";
    public static final String[] BACKEND_VALUES = { BACKEND_JAVA, BACKEND_BINARY };

    /** SFTP server port. 0 = pick a free one automatically, -1 = disable SFTP. */
    public static int port = 2222;

    /**
     * Which compiler turns Python source into .mpy bytecode. Defaults to the built-in
     * Java one: it needs no native binary, so it works wherever the game runs, and it
     * compiles in-process instead of spawning mpy-cross once per file.
     *
     * <p>This is read on every compile rather than cached, so flipping it in the
     * in-game config screen takes effect immediately -- no restart, no file editing.
     *
     * <p>Choosing {@code binary} when no usable mpy-cross was extracted falls back to
     * the Java backend (see {@link MyMod#compile}). That fallback covers a MISSING
     * backend only: if the chosen backend runs and rejects the source, the error is
     * reported as-is rather than silently retried on the other one, which would hide
     * genuine differences between them.
     */
    public static String compilerBackend = BACKEND_JAVA;

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

        net.minecraftforge.common.config.Property backendProp = configuration.get(
                Configuration.CATEGORY_GENERAL, "CompilerBackend", BACKEND_JAVA,
                "Which backend compiles Python to .mpy bytecode.\n"
                        + "  java   - built-in pure-Java compiler (default): no native binary needed,\n"
                        + "           works on any platform, compiles in-process.\n"
                        + "  binary - the bundled mpy-cross executable, run as a subprocess.\n"
                        + "Takes effect immediately; no restart required.\n"
                        + "If 'binary' is selected but no usable mpy-cross exists for this platform,\n"
                        + "the java backend is used instead.",
                BACKEND_VALUES);
        backendProp.setLanguageKey("mpy4oc.config.compilerBackend");
        compilerBackend = backendProp.getString();

        if (!BACKEND_BINARY.equals(compilerBackend)) compilerBackend = BACKEND_JAVA;

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    /** True if the config asks for the native mpy-cross. */
    public static boolean wantsBinaryBackend() {
        return BACKEND_BINARY.equals(compilerBackend);
    }
}
