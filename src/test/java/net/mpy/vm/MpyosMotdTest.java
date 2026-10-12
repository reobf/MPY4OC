package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyObj;
import org.junit.jupiter.api.Test;

/** The boot tip (MPYOS init.py, lib/motd.py, usr/misc/greetings.txt), as OpenOS's greetings. */
class MpyosMotdTest {
    private static final Path MPYOS = Path.of("src/main/resources/assets/mpy4oc/mpyos");

    static List<String> tips() throws Exception {
        return Files.readAllLines(MPYOS.resolve("usr/misc/greetings.txt"), StandardCharsets.UTF_8).stream()
                .map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#")).collect(Collectors.toList());
    }

    @Test void theTipsAreEnglishAndFitAnEightyColumnScreen() throws Exception {
        List<String> tips = tips();
        assertTrue(tips.size() >= 20, tips.size() + " tips");
        assertEquals(tips.size(), new HashSet<>(tips).size(), "no tip twice");
        for (String tip : tips) {
            assertTrue(tip.chars().allMatch(c -> c >= 32 && c < 127), "plain ASCII: " + tip);
            assertTrue(("Tip: " + tip).length() <= 80, "fits 80 columns: " + tip);
        }
    }

    @Test void bootsASecondApartShowDifferentTips() throws Exception {
        String program = String.join("\n",
                "import motd",
                "assert motd.tips(b'') == [] and motd.pick([], 5) is None",
                "assert motd.tips(b'# a comment\\n\\n  first  \\nsecond\\n') == ['first', 'second']",
                "tips = motd.tips(DATA)",
                "assert len(tips) == COUNT, len(tips)",
                "picked = [motd.pick(tips, 1000000 + 20 * i) for i in range(40)]",   // 20 ticks: a second
                "assert all(tip in tips for tip in picked)",
                "assert motd.pick(tips, 123) == motd.pick(tips, 123)",
                "distinct = len(set(picked))",
                "");
        MpyModule module = MpyLoader.load(MpyCross.compile(program, "motd_test.py"), new QstrPool());
        Map<String, Object> globals = new HashMap<>();
        globals.put("DATA", new PyObj.Bytes(Files.readAllBytes(MPYOS.resolve("usr/misc/greetings.txt"))));
        globals.put("COUNT", (long) tips().size());
        Vm vm = new Vm(module, globals);
        byte[] motd = MpyCross.compile(Files.readString(MPYOS.resolve("lib/motd.py"), StandardCharsets.UTF_8), "motd.py");
        vm.setModuleFinder(name -> name.equals("motd") ? motd : null);
        vm.start(new Frame(module.root));
        for (int i = 0; i < 2_000_000 && !vm.isFinished(); i++) vm.step(new int[] {1000});
        assertTrue(vm.isFinished());
        long distinct = (Long) vm.globals.get("distinct");
        assertTrue(distinct >= tips().size() / 2, distinct + " different tips in 40 boots");
    }
}
