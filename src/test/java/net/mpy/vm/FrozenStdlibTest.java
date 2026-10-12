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
        Path stdlib = Path.of("stdlib");
        // Subdirectories too: stdlib/tkinter/ttk.py is frozen as "tkinter/ttk".
        try (Stream<Path> paths = Files.walk(stdlib)) {
            for (Path source : paths.filter(p -> p.toString().endsWith(".py")).collect(Collectors.toList())) {
                String relative = stdlib.relativize(source).toString().replace('\\', '/');
                String name = relative.substring(0, relative.length() - 3);
                compiledNames.add(name);
                assertArrayEquals(StdLibData.MODULES.get(name),
                        MpyCross.compile(Files.readString(source, StandardCharsets.UTF_8), "stdlib/" + relative), name);
            }
        }
        assertTrue(compiledNames.contains("tkinter/ttk"), "the ttk submodule is frozen under its path");
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

    @Test
    void longModulesAreSplitIntoSeveralConstantsAndRejoin() throws Exception {
        // A module whose base64 passes one class-file constant (65,535 bytes) would not compile.
        Path dir = temporaryDirectory.resolve("lib");
        Files.createDirectories(dir.resolve("pkg"));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 6000; i++) big.append("name_").append(i).append(" = 'value number ").append(i).append("'\n");
        Files.writeString(dir.resolve("big.py"), big.toString());
        Files.writeString(dir.resolve("pkg").resolve("small.py"), "x = 1\n");
        Path output = temporaryDirectory.resolve("Out.java");
        GenStdlib.main(new String[] {dir.toString(), output.toString()});
        String generated = Files.readString(output);
        // Line by line: a regex repeating over thousands of lines recurses and can overflow the stack.
        java.util.Map<String, String> constants = new java.util.HashMap<>();
        String[] lines = generated.split("\n");
        java.util.regex.Pattern head = java.util.regex.Pattern.compile("\\s*private static final String (\\w+) =");
        java.util.regex.Pattern piece = java.util.regex.Pattern.compile("\\s+\"([^\"]*)\"( \\+|;)");
        for (int i = 0; i < lines.length; i++) {
            java.util.regex.Matcher constant = head.matcher(lines[i]);
            if (!constant.matches()) continue;
            // Join the quoted pieces (base64 has its own '+' characters, so do not strip those).
            StringBuilder pieces = new StringBuilder();
            java.util.regex.Matcher quoted;
            do {
                quoted = piece.matcher(lines[++i]);
                if (!quoted.matches()) break;
                pieces.append(quoted.group(1));
            } while (quoted.group(2).equals(" +"));
            String value = pieces.toString();
            assertTrue(value.length() <= GenStdlib.CONSTANT_LIMIT, constant.group(1) + " fits a class-file constant");
            constants.put(constant.group(1), value);
        }
        assertTrue(constants.containsKey("BIG_B64_1"), "the long module is split");
        assertTrue(generated.contains("Map.entry(\"pkg/small\", dec(PKG_SMALL_B64))"), "a subdirectory module keeps its path");
        java.util.regex.Matcher entry = java.util.regex.Pattern.compile("Map\\.entry\\(\"big\", dec\\(([^)]*)\\)\\)").matcher(generated);
        assertTrue(entry.find());
        StringBuilder joined = new StringBuilder();
        for (String part : entry.group(1).split(", ")) joined.append(constants.get(part));
        assertArrayEquals(MpyCross.compile(big.toString(), "stdlib/big.py"), java.util.Base64.getDecoder().decode(joined.toString()));
    }
}
