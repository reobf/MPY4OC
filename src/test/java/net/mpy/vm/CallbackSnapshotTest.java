package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyObj;
import org.junit.jupiter.api.Test;

class CallbackSnapshotTest {
    @Test
    void instanceReachedBeforeItsClassKeepsBoundCallbacksThroughSandboxCycle() {
        String source = """
                ns = {}
                exec_sandbox(SOURCE, ns)
                instance = ns['app']
                ready = True
                result = instance.callback()
                """;
        var module = MpyLoader.load(MpyCross.compile(source, "snapshot-instance.py"), new QstrPool());
        var globals = new HashMap<String, Object>();
        globals.put("SOURCE", """
                class App:
                    def refresh(self):
                        return self.value
                app = App()
                app.value = 42
                app.callback = app.refresh
                """);
        var vm = new Vm(module, globals);
        vm.setCompiler((text, filename, mode) -> MpyCross.compile(text, filename));
        vm.start(new Frame(module.root));
        while (!Boolean.TRUE.equals(vm.globals.get("ready"))) vm.step();
        // Keep the instance on a frame's value stack, so the writer encounters it
        // before its class/defining namespace rather than through globals order.
        vm.step();
        var restored = Vm.fromSnapshot(vm.saveSnapshot());
        for (int i = 0; i < 10000 && !restored.isFinished(); i++) restored.step();
        assertTrue(restored.isFinished());
        assertEquals(42L, restored.globals.get("result"));
    }

    @Test
    void callbackTableAndImportedModuleResumeWithNewHostFunctions() {
        String program = String.join("\n",
                "import json",
                "callbacks = {}",
                "def on_event(value):",
                "    return json.loads(json.dumps({'value': value}))['value']",
                "callbacks[1] = on_event",
                "armed = True",
                "while poll() is None:",
                "    pass",
                "result = callbacks[1](poll())", "");
        MpyModule module = MpyLoader.load(MpyCross.compile(program, "callback_test.py"), new QstrPool());
        Map<String, Object> initialGlobals = new HashMap<>();
        initialGlobals.put("poll", (HostFunction) args -> PyObj.NONE);
        Vm original = new Vm(module, initialGlobals);
        original.start(new Frame(module.root));
        for (int i = 0; i < 50_000 && !Boolean.TRUE.equals(original.globals.get("armed")); i++) original.step();
        assertEquals(Boolean.TRUE, original.globals.get("armed"), "callback table must be set before saving");
        assertFalse(original.isFinished());
        byte[] saved = original.saveSnapshot();

        Map<String, Object> restoredGlobals = new HashMap<>();
        restoredGlobals.put("poll", (HostFunction) args -> "restored event");
        Vm restored = Vm.fromSnapshot(saved, restoredGlobals, HostRegistry.fromGlobals(restoredGlobals));
        for (int i = 0; i < 50_000 && !restored.isFinished(); i++) restored.step();
        assertTrue(restored.isFinished(), "restored callback must finish within its instruction budget");
        assertEquals("restored event", restored.globals.get("result"));
        assertFalse(original.isFinished(), "only the restored VM received an event");
    }
}
