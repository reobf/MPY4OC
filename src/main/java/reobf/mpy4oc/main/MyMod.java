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


@Mod(modid = MyMod.MODID, version = Tags.VERSION, name = "mpy4oc", acceptedMinecraftVersions = "[1.7.10]")
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


    public static String MPY_CROSS;
    static public byte[] compile(String source) throws Exception  {
        Process p = new ProcessBuilder(MPY_CROSS, "-", "-o", "-").start();
        p.getOutputStream().write(source.getBytes("UTF-8"));
        p.getOutputStream().close();
        byte[] mpy = p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) throw new RuntimeException("compile failed");
        return mpy;
    }
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        File configFile = event.getSuggestedConfigurationFile();
        File gameDir = configFile.getParentFile().getParentFile();
        File mpyBinaryDir = new File(gameDir, "mpy_binary");

        try {
            File exe = extractBinary(mpyBinaryDir);
            MPY_CROSS = exe.getAbsolutePath();   // 关键：设置为绝对路径
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract mpy-cross binary", e);
        }
    }
    @Mod.EventHandler
   public void init(FMLInitializationEvent event) {
    
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
