package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.NBTTagCompound;
import net.mpy.vm.Vm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.tk.TileTkDisplay;
import reobf.mpy4oc.tk.TkTestRegistration;

class MpyosTaskManagerTest {
    @BeforeAll static void register() { TkTestRegistration.ensure(); }

    @Test void workerThreadsChargeTheirProgramAndRetiredCountersAreReleased() throws Exception {
        var host = TkPythonTest.shellHost("""
                import _process, os
                FAKE_VFS.files['/bin/worker.py'] = '''
                import threading, time
                def work():
                    SHARED['ready'] = True
                    while not SHARED.get('begin'):
                        time.sleep(0.01)
                    value = 0
                    for i in range(3000):
                        value += i
                    SHARED['worked'] = True
                    while not SHARED.get('finish'):
                        time.sleep(0.01)
                threading.Thread(target=work).start()
                '''
                FAKE_VFS.files['/bin/idle.py'] = '''
                import time
                while not SHARED.get('finish'):
                    time.sleep(0.01)
                '''
                worker = os.spawnvp(os.P_NOWAIT, 'worker', ['worker'])
                idle = os.spawnvp(os.P_NOWAIT, 'idle', ['idle'])
                wait(lambda: SHARED.get('ready'))
                first = {p['pid']: p for p in _process.snapshot()['processes']}
                assert first[worker]['threads'] == 2 and first[idle]['threads'] == 1, first
                assert first[0]['command'] == 'Shell / system'
                assert first[worker]['background'] and first[worker]['ppid'] == 0
                SHARED['begin'] = True
                wait(lambda: SHARED.get('worked'))
                second = {p['pid']: p for p in _process.snapshot()['processes']}
                assert second[worker]['ops'] - first[worker]['ops'] > 10000
                assert second[worker]['ops'] - first[worker]['ops'] > second[idle]['ops'] - first[idle]['ops']
                second[worker]['command'] = 'mutated snapshot'
                assert _process.runtime().active[worker]['line'] == 'worker'
                SHARED['finish'] = True
                assert os.waitpid(worker, 0) == (worker, 0)
                assert os.waitpid(idle, 0) == (idle, 0)
                raw = __program_ops()
                assert set(raw['programs']) == {None}, raw
                assert raw['retired'] > 10000 and raw['total'] == raw['retired'] + sum(raw['programs'].values())
                assert len(_process.snapshot()['processes']) == 1
                passed = True
                """);
        host.stepBudget = 73;
        host.runUntil("passed");
    }

    @Test void sampleRatesUseSharedVmTotalAndDoNotMutateOtherViewers() throws Exception {
        var host = TkPythonTest.shellHost("""
                import _process
                original = _process.snapshot
                data = {'total_ops': 100, 'retired_ops': 0, 'processes': [{'pid': 1, 'ops': 20}]}
                _process.snapshot = lambda: data
                try:
                    a, b = _process.ProcessMonitor(), _process.ProcessMonitor()
                    assert a.sample(10)['ops_per_second'] is None
                    assert b.sample(10)['ops_per_second'] is None
                    data = {'total_ops': 300, 'retired_ops': 10, 'processes': [{'pid': 1, 'ops': 120}, {'pid': 2, 'ops': 50}]}
                    one, two = a.sample(12), b.sample(12)
                    assert one == two and one['ops_per_second'] == 100
                    assert one['processes'][0]['ops_per_second'] == 50 and one['processes'][0]['share'] == 50
                    assert one['processes'][1]['share'] == 25
                    one['processes'][0]['ops'] = 9999
                    assert a.previous['processes'][0]['ops'] == 120
                    data = {'total_ops': 500, 'retired_ops': 200, 'processes': [{'pid': 2, 'ops': 150}]}
                    three = a.sample(14)
                    assert len(three['processes']) == 1 and three['processes'][0]['share'] == 50
                    assert a.sample(14)['ops_per_second'] is None
                    assert a.sample(1)['ops_per_second'] is None
                    data = {'total_ops': 10, 'retired_ops': 0, 'processes': []}
                    assert a.sample(2)['ops_per_second'] is None
                finally:
                    _process.snapshot = original
                passed = True
                """);
        host.runUntil("passed");
    }

    private static String app() throws Exception {
        return Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/bin/tkinter/tktaskmgr.py"))
                .replace("manager.root.mainloop()", "SHARED['manager'] = manager\nmanager.root.mainloop()");
    }

    @Test void realTaskManagerSortsPreservesSelectionAndEndsOnlyTheSelectedProcess() throws Exception {
        var tile = new TileTkDisplay();
        var host = TkPythonTest.shellHost("""
                import _process, os
                FAKE_VFS.files['/bin/tkinter/tktaskmgr.py'] = TASKMGR
                FAKE_VFS.files['/bin/victim.py'] = '''
                import tkinter as tk
                root = tk.Tk()
                root.title('Victim')
                SHARED['victim_root'] = root
                try:
                    root.mainloop()
                finally:
                    SHARED['victim_cleaned'] = True
                '''
                victim = os.spawnvp(os.P_NOWAIT, 'victim', ['victim'])
                wait(lambda: 'victim_root' in SHARED and SHARED['victim_root']._token)
                manager_pid = os.spawnvp(os.P_NOWAIT, 'tktaskmgr', ['tktaskmgr'])
                wait(lambda: 'manager' in SHARED and SHARED['manager'].root._token)
                manager = SHARED['manager']
                assert set(manager.rows) == {0, victim, manager_pid}
                manager.tree.selection_set(str(victim))
                manager.select()
                assert str(manager.end_button.cget('state')) == 'normal'
                widgets, callbacks = len(manager.root._widgets), len(manager.root._callbacks)
                manager.sort('pid')
                assert manager.tree.get_children() == tuple(str(p) for p in sorted(manager.rows))
                manager.sort('pid')
                assert manager.tree.get_children() == tuple(str(p) for p in sorted(manager.rows, reverse=True))
                assert manager.tree.selection() == (str(victim),)
                for pid in (0, manager_pid):
                    manager.tree.selection_set(str(pid))
                    manager.select()
                    assert str(manager.end_button.cget('state')) == 'disabled'
                    manager.end_selected()
                    assert victim in _process.runtime().active and manager_pid in _process.runtime().active
                manager.tree.selection_set(str(victim))
                manager.select()
                armed = True
                wait(lambda: computer.uptime() >= 1)
                wait(lambda: manager.rows[victim]['ops_per_second'] is not None)
                for i in range(3):
                    manager.refresh()
                assert manager.tree.selection() == (str(victim),)
                assert len(manager.root._widgets) == widgets and len(manager.root._callbacks) == callbacks
                end_button_id = manager.end_button._id
                ready_to_end = True
                wait(lambda: SHARED.get('victim_cleaned'))
                assert os.waitpid(victim, 0) == (victim, 15)
                assert not SHARED['victim_root']._alive
                assert manager_pid in _process.runtime().active and manager.root._alive
                ended = True
                wait(lambda: computer.uptime() >= 3)
                wait(lambda: victim not in manager.rows and not manager.tree.exists(str(victim)) and not manager.tree.selection())
                assert not manager.tree.exists(str(victim)) and manager.tree.selection() == ()
                manager.root.after_idle(manager.close)
                assert os.waitpid(manager_pid, 0) == (manager_pid, 0)
                assert not manager.root._callbacks and not manager.root._widgets
                assert set(__program_ops()['programs']) == {None}
                passed = True
                """, tile);
        host.vm.globals.put("TASKMGR", app());
        host.runUntil("armed");
        host.time = 1;
        host.runUntil("ready_to_end");
        host.click(tile, ((Number) host.vm.globals.get("end_button_id")).intValue());
        host.runUntil("ended");
        host.time = 3;
        host.runUntil("passed");
        assertTrue(tile.application().tokens().isEmpty());
    }

    @Test void managerUiAndCountersResumeTogetherFromSnapshot() throws Exception {
        var tile = new TileTkDisplay();
        var host = TkPythonTest.shellHost("""
                import os, _process
                FAKE_VFS.files['/bin/tkinter/tktaskmgr.py'] = TASKMGR
                manager_pid = os.spawnvp(os.P_NOWAIT, 'tktaskmgr', ['tktaskmgr'])
                wait(lambda: 'manager' in SHARED and SHARED['manager'].root._token)
                manager = SHARED['manager']
                manager.tree.selection_set(str(manager_pid))
                manager.select()
                before = __program_ops()['total']
                armed = True
                wait(lambda: globals().get('RESTORED'))
                assert __program_ops()['total'] >= before
                wait(lambda: manager.rows[manager_pid]['ops_per_second'] is not None)
                assert manager.tree.selection() == (str(manager_pid),)
                manager.root.after_idle(manager.close)
                assert os.waitpid(manager_pid, 0) == (manager_pid, 0)
                assert not manager.root._callbacks
                assert set(__program_ops()['programs']) == {None}
                passed = True
                """, tile);
        host.vm.globals.put("TASKMGR", app());
        host.runUntil("armed");
        var before = host.vm.programOpsSnapshot();
        byte[] snapshot = host.vm.saveSnapshot(host.registry);
        var nbt = new NBTTagCompound();
        tile.writeToNBT(nbt);
        var copy = new TileTkDisplay();
        copy.readFromNBT(nbt);
        var restored = new TkPythonTest.Host();
        restored.stepBudget = 300;
        restored.bootstrap = true;
        restored.start("pass");
        restored.tiles.put("screen-a", copy);
        restored.vm = Vm.fromSnapshot(snapshot, restored.globals, restored.registry);
        assertEquals(before.map.get("total"), restored.vm.programOpsSnapshot().map.get("total"));
        restored.vm.globals.put("RESTORED", true);
        restored.time = 2;
        restored.runUntil("passed");
        assertTrue(copy.application().tokens().isEmpty());
    }
}
