package net.mpy;

import net.mpy.bytecode.Disassembler;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyObj;
import net.mpy.vm.Frame;
import net.mpy.vm.PyFunction;
import net.mpy.vm.Vm;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Command-line entry point.
 *
 *   java net.mpy.Main &lt;file.mpy&gt;                       disassemble (mpy-tool.py compatible)
 *   java net.mpy.Main run &lt;file.mpy&gt; &lt;func&gt; [int...]     run a top-level function on the step VM
 *
 * The runner accepts integer arguments (sufficient for a CLI demo); the Phase 2
 * harness shows how to pass lists/tuples/strings programmatically.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        // deterministic UTF-8 output regardless of the console's charset
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        if (args.length >= 1 && args[0].equals("run")) {
            runMode(args);
            return;
        }
        if (args.length < 1) {
            System.err.println("usage: java net.mpy.Main <file.mpy>");
            System.err.println("       java net.mpy.Main run <file.mpy> <function> [int args...]");
            System.exit(2);
        }
        String path = args[0];
        MpyModule mod = MpyLoader.loadFile(Path.of(path), new QstrPool());
        System.out.print(Disassembler.disassemble(mod, path));
    }

    private static void runMode(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: java net.mpy.Main run <file.mpy> <function> [int args...]");
            System.exit(2);
        }
        String path = args[1], fn = args[2];
        MpyModule mod = MpyLoader.loadFile(Path.of(path), new QstrPool());

        // Define the module's top-level functions into globals (skip any __main__).
        Map<String, Object> globals = new HashMap<>();
        globals.put("__name__", "__mpy_java__");
        Vm vm = new Vm(mod, globals);
        vm.runToCompletion(new Frame(mod.root));

        Object callable = globals.get(fn);
        if (!(callable instanceof PyFunction)) {
            System.err.println("no top-level function named '" + fn + "'");
            System.exit(1);
        }

        Object[] callArgs = new Object[args.length - 3];
        for (int i = 3; i < args.length; i++) callArgs[i - 3] = parseInt(args[i]);

        Object result = new Vm(mod, globals).invoke((PyFunction) callable, callArgs);
        System.out.println(PyObj.repr(result));
    }

    private static Object parseInt(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return PyObj.normInt(new BigInteger(s));
        }
    }
}
