package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every BIOS text an earlier commit shipped is recognised; the current one and edited copies are not. */
class BiosVersionsTest {
    private static final String[] FILES = {"src/main/resources/assets/mpy4oc/bios/bios.py", "src/main/resources/assets/mpy4oc/mpyos/bios.py"};

    private static String git(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        for (String a : args) command.add(a);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) { in.transferTo(out); }
        return process.waitFor() == 0 ? out.toString(StandardCharsets.UTF_8) : null;
    }

    @Test void everyEarlierVersionInHistoryIsRecognised() throws Exception {
        String log = null;
        try { log = git("log", "--format=%H", "--", FILES[0], FILES[1]); } catch (Exception noGit) { }
        assumeTrue(log != null && !log.isBlank(), "needs git and the repository history");
        int checked = 0;
        for (String commit : log.trim().split("\\s+")) {
            for (String file : FILES) {
                String text = git("show", commit + ":" + file);
                if (text == null) continue;
                String current = Files.readString(Path.of(file));
                if (text.replace("\r", "").equals(current.replace("\r", ""))) continue;   // not released yet
                assertTrue(BiosVersions.earlierRelease(text), file + " at " + commit + " is missing from BiosVersions.EARLIER");
                checked++;
            }
        }
        assertTrue(checked >= 4, "found " + checked + " earlier versions");
    }

    @Test void theCurrentAndEditedCopiesAreNotEarlierReleases() throws Exception {
        for (String file : FILES) {
            String current = Files.readString(Path.of(file));
            assertFalse(BiosVersions.earlierRelease(current), file + ": its hash belongs in EARLIER only once it changes");
        }
        assertFalse(BiosVersions.earlierRelease(null));
        assertFalse(BiosVersions.earlierRelease("print('my own bios')\n"));
        String log = null;
        try { log = git("show", "01f20db:" + FILES[0]); } catch (Exception noGit) { }
        assumeTrue(log != null);
        assertTrue(BiosVersions.earlierRelease(log.replace("\n", "\r\n")), "line endings do not matter");
        assertFalse(BiosVersions.earlierRelease(log + "# mine\n"), "an edited copy is left alone");
    }
}
