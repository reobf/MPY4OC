package reobf.mpy4oc.main;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;



import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;


@Mod(modid = MyMod.MODID, version = Tags.VERSION, name = "mpy4oc", acceptedMinecraftVersions = "[1.7.10]",
        guiFactory = "reobf.mpy4oc.main.ModGuiFactory")
public class MyMod {

	
    public static final String MODID = "mpy4oc";
    public static final Logger LOG = LogManager.getLogger(MODID);
 
    private File extractBinary(File targetDir) throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();

        boolean isX64 = arch.equals("amd64") || arch.equals("x86_64");
        if (!isX64) {
            throw new UnsupportedOperationException("Unsupported Arch: " + arch);
        }

        String resourcePath;
        String fileName;
        if (os.contains("linux")) {
            resourcePath = "binary/mpy-cross-linux-x64";
            fileName = "mpy-cross-linux-x64";
        } else if (os.contains("windows")) {
            resourcePath = "binary/mpy-cross-win-x64.exe";
            fileName = "mpy-cross-win-x64.exe";
        } else {
            throw new UnsupportedOperationException("Unsupported OS: " + os);
        }

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Cannot create dir: " + targetDir);
        }

        File target = new File(targetDir, fileName);

        // 文件已存在则跳过拷贝，但仍要保证权限正确
        if (!target.exists() || target.length() == 0) {
            InputStream in = MyMod.class.getClassLoader().getResourceAsStream(resourcePath);
            if (in == null) {
                throw new IOException("Resource not found in jar: " + resourcePath);
            }
            try {
                OutputStream out = new FileOutputStream(target);
                try {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
        }

        // Linux 每次都确保可执行权限（即使文件已存在）
        if (os.contains("linux")) {
            target.setExecutable(true, false);
        }

        return target;
    }


    /** Absolute path of the extracted mpy-cross, or null when none is available. */
    public static String MPY_CROSS;
    /** Why the binary backend is unavailable, for the one-time log line. */
    private static String binaryUnavailableReason;
    private static boolean warnedBinaryMissing;

    /** True when a native mpy-cross was extracted and can be run. */
    public static boolean isBinaryBackendAvailable() {
        return MPY_CROSS != null;
    }

    /**
     * Compile Python source to .mpy bytecode using the configured backend.
     *
     * <p>The backend is read fresh from the config every call, so switching it in the
     * in-game config screen applies to the very next compile.
     *
     * <p>Only a MISSING binary backend falls back to Java. A backend that runs and
     * rejects the source propagates its error: silently retrying on the other backend
     * would turn a real disagreement between the two into a mystery, and would make a
     * genuine syntax error look like it compiled fine.
     */
    static public byte[] compile(String source) throws Exception {
        if (Config.wantsBinaryBackend()) {
            if (isBinaryBackendAvailable()) {
                return compileWithBinary(source);
            }
            if (!warnedBinaryMissing) {
                warnedBinaryMissing = true;
                LOG.warn("mpy4oc: config selects the 'binary' compiler backend, but no usable "
                        + "mpy-cross is available (" + binaryUnavailableReason
                        + "); using the built-in Java backend instead.");
            }
        }
        return compileWithJava(source);
    }

    /** Run the extracted mpy-cross as a subprocess. */
    private static byte[] compileWithBinary(String source) throws Exception {
        Process p = new ProcessBuilder(MPY_CROSS, "-", "-o", "-").start();
        p.getOutputStream().write(source.getBytes("UTF-8"));
        p.getOutputStream().close();
        byte[] out = p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) throw new RuntimeException("compile failed");
        return out;
    }

    /** Compile in-process with the bundled pure-Java compiler. */
    private static byte[] compileWithJava(String source) throws Exception {
        return net.mpy.compiler.MpyCross.compile(source);
    }
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        File configFile = event.getSuggestedConfigurationFile();
        File gameDir = configFile.getParentFile().getParentFile();
        File mpyBinaryDir = new File(gameDir, "mpy_binary");

        // SFTP port etc.
        Config.synchronizeConfiguration(configFile);

        // Re-read the config whenever it is edited from the in-game screen, so the
        // compiler-backend switch applies without a restart.
        cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(new Object() {
            @cpw.mods.fml.common.eventhandler.SubscribeEvent
            public void onConfigChanged(
                    cpw.mods.fml.client.event.ConfigChangedEvent.OnConfigChangedEvent event) {
                if (MODID.equals(event.modID)) {
                    Config.loadFromConfig();
                    LOG.info("mpy4oc: compiler backend is now '" + Config.compilerBackend + "'");
                }
            }
        });

        // The binary is ALWAYS extracted, whichever backend is configured: the setting
        // can be flipped at any time from the in-game screen, and a mod that only
        // unpacked it lazily would then have to do file I/O mid-game. Failing to
        // extract is no longer fatal either -- on a platform with no bundled binary
        // (macOS, ARM, ...) the mod still runs fine on the Java backend.
        try {
            File exe = extractBinary(mpyBinaryDir);
            MPY_CROSS = exe.getAbsolutePath();
            LOG.info("mpy4oc: mpy-cross available at " + MPY_CROSS);
        } catch (Exception e) {
            MPY_CROSS = null;
            binaryUnavailableReason = String.valueOf(e.getMessage());
            LOG.info("mpy4oc: no native mpy-cross for this platform (" + binaryUnavailableReason
                    + "); the 'binary' backend will fall back to the Java one.");
        }

        // The MPY CPU item. Items must be registered during preInit.
        mpyCpu = new reobf.mpy4oc.main.item.ItemMpyCPU();
        cpw.mods.fml.common.registry.GameRegistry.registerItem(mpyCpu, "mpy_cpu");
        LOG.info("Registered item: mpy4oc:mpy_cpu");

        // The MPY APU item: CPU + GPU in one (CPU slot, built-in graphics).
        mpyApu = new reobf.mpy4oc.main.item.ItemMpyAPU();
        cpw.mods.fml.common.registry.GameRegistry.registerItem(mpyApu, "mpy_apu");
        LOG.info("Registered item: mpy4oc:mpy_apu");

        // The ASYNC MPY CPU: same architecture, but runs the VM on OC's computer
        // thread (bounded budget) and defers non-direct calls to the main thread.
        mpyCpuAsync = new reobf.mpy4oc.main.item.ItemMpyCPUAsync();
        cpw.mods.fml.common.registry.GameRegistry.registerItem(mpyCpuAsync, "mpy_cpu_async");
        LOG.info("Registered item: mpy4oc:mpy_cpu_async");

        // The ASYNC MPY APU: async CPU plus OC's built-in graphics card. Its gpu
        // callbacks are direct, so graphics stays fast on the computer thread.
        mpyApuAsync = new reobf.mpy4oc.main.item.ItemMpyAPUAsync();
        cpw.mods.fml.common.registry.GameRegistry.registerItem(mpyApuAsync, "mpy_apu_async");
        LOG.info("Registered item: mpy4oc:mpy_apu_async");

        // The SFTP card: exposes an in-game filesystem over SFTP so you can edit
        // init.py from your desktop instead of in-game. Ported from WASM4OC.
        sftpCard = (reobf.mpy4oc.main.item.ItemSFTPCard) new reobf.mpy4oc.main.item.ItemSFTPCard()
                .setMaxStackSize(1)
                .setUnlocalizedName("mpy4oc.oc.sftpcard")
                .setTextureName("mpy4oc:sftpcard");
        cpw.mods.fml.common.registry.GameRegistry.registerItem(sftpCard, "mpy4oc.oc.sftpcard");

        // The card item IS its own OC item-driver (it implements HostAware).
        li.cil.oc.server.driver.Registry.add((li.cil.oc.api.driver.Item) sftpCard);
        LOG.info("Registered item + driver: mpy4oc:sftpcard (port " + Config.port + ")");
    }

    /** The MPY CPU item, so other code (and /give) can find it. */
    public static reobf.mpy4oc.main.item.ItemMpyCPU mpyCpu;

    /** The MPY APU item (CPU + GPU). */
    public static reobf.mpy4oc.main.item.ItemMpyAPU mpyApu;

    /** The async MPY CPU item. */
    public static reobf.mpy4oc.main.item.ItemMpyCPUAsync mpyCpuAsync;

    /** The async MPY APU item (async CPU + built-in GPU). */
    public static reobf.mpy4oc.main.item.ItemMpyAPUAsync mpyApuAsync;

    /** The SFTP card item. */
    public static reobf.mpy4oc.main.item.ItemSFTPCard sftpCard;

    @Mod.EventHandler
   public void init(FMLInitializationEvent event) {
        // Register the architecture so OC knows the class (and can name it).
        li.cil.oc.api.Machine.add(reobf.mpy4oc.main.arch.MpyArchitecture.class);
        LOG.info("Registered OpenComputers architecture: mpy");

        // Register the driver that makes our CPU item an actual OC processor.
        // The driver hardcodes MpyArchitecture, so this CPU always runs mpy —
        // no NBT, nothing to toggle.
        li.cil.oc.api.Driver.add(new reobf.mpy4oc.main.item.DriverMpyCPU());
        LOG.info("Registered OpenComputers driver: mpy CPU");

        // Driver for the APU (CPU + built-in GPU).
        li.cil.oc.api.Driver.add(new reobf.mpy4oc.main.item.DriverMpyAPU());
        LOG.info("Registered OpenComputers driver: mpy APU");

        // Driver for the async CPU (no ticker: runs on the computer thread).
        li.cil.oc.api.Driver.add(new reobf.mpy4oc.main.item.DriverMpyCPUAsync());
        LOG.info("Registered OpenComputers driver: mpy CPU (async)");

        // Driver for the async APU (graphics half only; VM runs on the computer thread).
        li.cil.oc.api.Driver.add(new reobf.mpy4oc.main.item.DriverMpyAPUAsync());
        LOG.info("Registered OpenComputers driver: mpy APU (async)");

        // Tell OC which environment class backs the SFTP card, so its @Callback
        // methods (start/stop/getPort) are discoverable as component methods.
        final net.minecraft.item.Item card = sftpCard;
        li.cil.oc.api.Driver.add(new li.cil.oc.api.driver.EnvironmentProvider() {
            @Override
            public Class<?> getEnvironment(net.minecraft.item.ItemStack stack) {
                if (stack != null && stack.getItem() == card) {
                    return reobf.mpy4oc.main.item.ItemSFTPCard.APIEnv.class;
                }
                return null;
            }
        });
        LOG.info("Registered OpenComputers environment provider: sftp card");

        // The mpy BIOS: bios.py source lives in the EEPROM code section. The arch
        // compiles and runs it at boot; it finds a bootable disk and runs its
        // /init.py. Registered with an empty data section (no boot address yet) and
        // read-only code (so it is not clobbered by accident).
        byte[] bios = readResourceBytes("assets/mpy4oc/bios/bios.py");
        net.minecraft.item.ItemStack biosStack = null;
        if (bios != null) {
            biosStack = li.cil.oc.api.Items.registerEEPROM("MPYOS BIOS", bios, null, true);
            LOG.info("Registered EEPROM: mpy BIOS (" + bios.length + " bytes)");
        } else {
            LOG.warn("mpy BIOS resource missing; EEPROM not registered");
        }

        // The mpyos LiveCD: a floppy whose contents come from assets/mpy4oc/mpyos/
        // in the jar (read-only, exactly like OpenOS's loot disks). Boot it, run
        // install, and it copies itself onto a hard disk.
        li.cil.oc.api.Items.registerFloppy("MPYOS", 6 /* cyan */,
            new java.util.concurrent.Callable<li.cil.oc.api.fs.FileSystem>() {
                @Override
                public li.cil.oc.api.fs.FileSystem call() {
                    return li.cil.oc.api.FileSystem.fromClass(MyMod.class, "mpy4oc", "mpyos");
                }
            }, true);   // recipe cycling: wrench + any loot disk reaches mpyos in survival
        LOG.info("Registered floppy: mpyos LiveCD");

        // Survival path for the BIOS: any EEPROM + the mpyos floppy = mpy BIOS.
        if (biosStack != null) {
            cpw.mods.fml.common.registry.GameRegistry.addRecipe(
                new reobf.mpy4oc.main.recipe.MpyBiosRecipe(biosStack));
            net.minecraftforge.oredict.RecipeSorter.register(
                "mpy4oc:biosflash", reobf.mpy4oc.main.recipe.MpyBiosRecipe.class,
                net.minecraftforge.oredict.RecipeSorter.Category.SHAPELESS,
                "after:minecraft:shapeless");
        }
    }

    /** Read a classpath resource as bytes, or null if absent. */
    private static byte[] readResourceBytes(String resourcePath) {
        try (InputStream in = MyMod.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
    @Mod.EventHandler
    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {
 
    }

    @Mod.EventHandler
    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {

    }
}
