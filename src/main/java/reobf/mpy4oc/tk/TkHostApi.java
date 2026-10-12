package reobf.mpy4oc.tk;

import java.util.Arrays;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.mpy.runtime.PyException;
import net.mpy.runtime.PyObj;
import net.mpy.vm.HostFunction;
import net.mpy.vm.HostRegistry;
import net.mpy.vm.PyModule;

/** Thin VM binding. Component calls retain the architecture's synchronized-call semantics. */
public final class TkHostApi {
    private TkHostApi() {}

    public static void install(Map<String, Object> globals, HostRegistry hosts,
            BiFunction<String, Object[], Object> invoke, Supplier<Map<String, String>> components,
            Supplier<Object> computer) {
        PyModule module = new PyModule("_tk", "<host>", null);
        register(module, hosts, "call", args -> {
            if (args.length < 3 || !(args[0] instanceof String) || !(args[1] instanceof String)
                    || !(args[2] instanceof String)) throw new PyException("TypeError", "Tk call requires address, operation and token");
            return invoke.apply((String) args[0], Arrays.copyOfRange(args, 1, args.length));
        });
        register(module, hosts, "screens", args -> {
            PyObj.PyList result = new PyObj.PyList();
            for (Map.Entry<String, String> entry : components.get().entrySet())
                if (TileTkDisplay.COMPONENT_NAME.equals(entry.getValue())) result.items.add(entry.getKey());
            return result;
        });
        register(module, hosts, "poll", args -> {
            Object current = computer.get();
            Object signals = current instanceof PyModule ? ((PyModule) current).ns.get("_signals") : null;
            if (signals instanceof PyObj.PyList) {
                java.util.List<Object> list = ((PyObj.PyList) signals).items;
                for (int i = 0; i < list.size(); i++) {
                    Object signal = list.get(i);
                    if (isTkSignal(signal)) return list.remove(i);
                }
            }
            return PyObj.NONE;
        });
        globals.put("_tk", module);
    }

    /** A signal a Tk display sends its program (queued with the computer's other signals). */
    public static boolean isTkSignal(Object signal) {
        if (!(signal instanceof PyObj.Tuple)) return false;
        Object[] parts = ((PyObj.Tuple) signal).items;
        return parts.length > 0 && ("tk_command".equals(parts[0]) || "tk_event".equals(parts[0])
                || "tk_changed".equals(parts[0]) || "tk_close".equals(parts[0]));
    }

    private static void register(PyModule module, HostRegistry hosts, String name, HostFunction function) {
        module.ns.put(name, function); hosts.register(module.name + "." + name, function);
    }
}
