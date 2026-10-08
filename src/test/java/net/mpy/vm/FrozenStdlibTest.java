package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.mpy.compiler.MpyCross;
import net.mpy.compiler.tools.GenStdlib;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrozenStdlibTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void restoredSourcesReproduceEveryFrozenModule() throws Exception {
        Set<String> compiledNames = new HashSet<>();
        try (Stream<Path> paths = Files.list(Path.of("stdlib"))) {
            for (Path source : paths.filter(p -> p.toString().endsWith(".py")).collect(Collectors.toList())) {
                String filename = source.getFileName().toString();
                String name = filename.substring(0, filename.length() - 3);
                compiledNames.add(name);
                assertArrayEquals(StdLibData.MODULES.get(name),
                        MpyCross.compile(Files.readString(source, StandardCharsets.UTF_8), "stdlib/" + filename), name);
            }
        }
        assertEquals(StdLibData.MODULES.keySet(), compiledNames);
    }

    @Test
    void generatorReproducesCheckedInSourceAndRunsRepeatedly() throws Exception {
        Path output = temporaryDirectory.resolve("StdLibData.java");
        GenStdlib.main(new String[] {"stdlib", output.toString()});
        // Git checkouts can differ in line endings; the generated code is always LF.
        String checkedIn = Files.readString(Path.of("src/main/java/net/mpy/vm/StdLibData.java"))
                .replace("\r\n", "\n");
        assertEquals(checkedIn, Files.readString(output));
        byte[] first = Files.readAllBytes(output);
        GenStdlib.main(new String[] {"stdlib", output.toString()});
        assertArrayEquals(first, Files.readAllBytes(output));
    }
}
