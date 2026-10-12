package reobf.mpy4oc.main;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Minecraft 1.7.10 reads .lang files as plain UTF-8 key=value lines: unlike a Java .properties
 * file, a \\uXXXX escape is not decoded and shows up literally in the game (a CPU tooltip showed
 * "\\u2014"). Forge's config screen alone turns a literal \\n into a line break.
 */
class LangFilesTest {
    private static final Path LANG = Path.of("src/main/resources/assets/mpy4oc/lang");
    private static final Pattern ESCAPE = Pattern.compile("\\\\u[0-9a-fA-F]{4}");

    private static Set<String> keys(String name) throws Exception {
        Set<String> keys = new TreeSet<>();
        List<String> lines = Files.readAllLines(LANG.resolve(name), StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 0) assertFalse(line.startsWith("﻿"), name + " must not start with a BOM (it becomes part of the first key)");
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            assertTrue(eq > 0, name + ":" + (i + 1) + " is not key=value");
            assertFalse(ESCAPE.matcher(line).find(), name + ":" + (i + 1) + " has a \\u escape, which the game shows literally: " + line);
            assertTrue(keys.add(line.substring(0, eq)), name + ":" + (i + 1) + " repeats a key");
        }
        return keys;
    }

    @Test void langFilesAreLiteralUtf8WithTheSameKeys() throws Exception {
        assertEquals(keys("en_US.lang"), keys("zh_CN.lang"));
    }
}
