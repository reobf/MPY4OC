package reobf.mpy4oc.main.arch;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.mpy.runtime.PyObj;
import net.mpy.vm.Vm;

/** OC terminal shortcuts. Modifier state belongs to each keyboard, never to Tk events. */
final class KeyboardInterruptInput {
    private final Map<Object, Set<Integer>> pressed = new HashMap<>();
    private final Set<Object> consumed = new HashSet<>();

    void clear() { pressed.clear(); consumed.clear(); }

    boolean accept(PyObj.Tuple signal, Vm vm) {
        Object[] parts = signal.items;
        if (parts.length < 2) return false;
        if ("component_removed".equals(parts[0])) {
            pressed.remove(parts[1]);
            consumed.remove(parts[1]);
            return false;
        }
        if (parts.length < 4 || !(parts[2] instanceof Number) || !(parts[3] instanceof Number)) return false;
        boolean down = "key_down".equals(parts[0]);
        if (!down && !"key_up".equals(parts[0])) return false;
        Set<Integer> keys = pressed.computeIfAbsent(parts[1], key -> new HashSet<>());
        int code = ((Number) parts[3]).intValue();
        if (!down) {
            keys.remove(code);
            if (code == 46) consumed.remove(parts[1]);
            if (keys.isEmpty()) pressed.remove(parts[1]);
            return false;
        }
        boolean repeated = !keys.add(code);
        boolean control = keys.contains(29) || keys.contains(157);
        boolean interrupt = ((Number) parts[2]).intValue() == 3 || (code == 46 && control);
        if (!interrupt) return false;
        // Suppress key repeat only when a foreground program owns the shortcut.
        if (repeated) return consumed.contains(parts[1]);
        boolean accepted = vm.requestKeyboardInterrupt(keys.contains(56) || keys.contains(184));
        if (accepted) consumed.add(parts[1]); else consumed.remove(parts[1]);
        return accepted;
    }
}
