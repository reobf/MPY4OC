package net.mpy.vm;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyException;
import org.junit.jupiter.api.Test;

class MpyosAemonTest {
    private static final String FIXTURE = """
            class Cpu:
                def __init__(self):
                    self.busy = True
                    self.output = {'name': 'minecraft:iron_ingot', 'damage': 0, 'label': 'Iron', 'size': 100}
                    self.active = [{'name': 'minecraft:iron_ingot', 'label': 'Iron', 'size': 4}]
                    self.pending = {1: {'name': 'water', 'label': 'Water', 'amount': 12000, 'size': 12000}, 'n': 1}
                    self.stored = []
                    self.broken = False
                    self.reads = 0
                def isBusy(self):
                    self.reads += 1
                    if self.broken:
                        raise OSError('Disconnected')
                    return self.busy
                def activeItems(self):
                    return self.active
                def pendingItems(self):
                    return self.pending
                def storedItems(self):
                    return self.stored
                def finalOutput(self):
                    return self.output
                def cancel(self):
                    raise AssertionError('monitor must never cancel a job')
            class Components:
                def __init__(self):
                    self.names = {'addr-b': 'me_interface', 'addr-a': 'me_controller', 'unrelated': 'me_importbus'}
                    self.cpu = Cpu()
                    self.enumerations = 0
                def list(self, kind):
                    return {k: v for k, v in self.names.items() if kind in v}
                def proxy(self, address):
                    assert address in self.names
                    return self
                def getCpus(self):
                    self.enumerations += 1
                    return [{'name': '', 'storage': 65536, 'coprocessors': 4, 'busy': self.cpu.busy, 'cpu': self.cpu}]
            components = Components()
            monitor = Monitor(components)
            monitor.connect('addr-a')
            """;

    @Test void progressCountsActiveAndPendingWorkAsAeDoes() throws Exception {
        run("""
                assert monitor.networks() == [('addr-a', 'me_controller'), ('addr-b', 'me_interface')]
                assert monitor.cpus[0]['name'] == 'CPU 1'
                first = monitor.sample(0)
                assert first['busy'] is True and first['progress'] == 0 and not first['warning']
                assert first['remaining'] == first['start'] == 12004, 'fluids count in mB, as AE counts them'
                assert quantity(first['active'][0]) == 4
                assert count_text(first['pending'][0]) == '12000 mB'
                assert first['output']['name'] == 'minecraft:iron_ingot'
                # A job for one machine: the final output stays 1 until it is delivered, but the
                # work below it moves (the bug: progress used to follow the final output only).
                components.cpu.output = {'name': 'gregtech:machine', 'label': 'Machine', 'size': 1}
                components.cpu.active = [{'name': 'a', 'size': 30}]
                components.cpu.pending = [{'name': 'b', 'size': 70}]
                monitor.reset_progress()
                assert monitor.sample(0)['progress'] == 0
                components.cpu.active = [{'name': 'a', 'size': 10}]
                components.cpu.pending = [{'name': 'b', 'size': 15}]
                sample = monitor.sample(0)
                assert sample['progress'] == 75 and sample['remaining'] == 25 and sample['start'] == 100
                assert sample['output']['size'] == 1
                # A job merged into the CPU adds its work to the start (CraftingCPUCluster.prepareStepCount).
                components.cpu.pending = [{'name': 'b', 'size': 55}]
                sample = monitor.sample(0)
                assert sample['start'] == 140 and sample['remaining'] == 65 and abs(sample['progress'] - 75 * 100 / 140) < 1e-9
                components.cpu.active, components.cpu.pending = [], []
                assert monitor.sample(0)['progress'] == 100
                monitor.reset_progress()
                components.cpu.active = [{'name': 'a', 'size': 5}]
                assert monitor.sample(0)['progress'] == 0, 'selection/rescan starts a new observation'
                components.cpu.busy = False
                sample = monitor.sample(0)
                assert sample['busy'] is False and sample['output'] is None and sample['progress'] is None and sample['remaining'] is None
                assert sample['active'] == sample['pending'] == sample['stored'] == []
                components.cpu.busy = True
                assert monitor.sample(0)['progress'] == 0
                passed = True
                """);
    }

    @Test void progressWorksWithoutAMonitorAndReadErrorsNeverShowStaleProgress() throws Exception {
        run("""
                monitor.sample(0)
                components.cpu.output = (None, 'No crafting monitor')
                components.cpu.active = [{'name': 'a', 'size': 2}]
                components.cpu.pending = []
                sample = monitor.sample(0)
                assert sample['busy'] is True and sample['output'] is None
                assert sample['progress'] is not None, 'the lists give progress without a Crafting Monitor'
                assert 'No crafting monitor' in sample['warning'] and len(sample['active']) == 1
                components.cpu.pending = (None, 'grid unavailable')
                sample = monitor.sample(0)
                assert 'pending: grid unavailable' in sample['warning'] and sample['pending'] == []
                assert sample['progress'] is None and monitor.baseline is None, 'half the work unknown: no progress'
                components.cpu.broken = True
                sample = monitor.sample(0)
                assert sample['busy'] is None and sample['warning'] == 'Disconnected'
                assert not sample['active'] and sample['progress'] is None
                components.cpu.broken = False
                components.cpu.pending = []
                assert monitor.sample(0)['progress'] == 0
                monitor.disconnect()
                assert monitor.address is None and monitor.cpus == [] and monitor.baseline is None
                monitor.connect('addr-b')
                assert monitor.sample(0)['progress'] == 0
                passed = True
                """);
    }

    @Test void pollingReusesOcValuesAndLimitsOverviewWork() throws Exception {
        run("""
                for i in range(50):
                    monitor.sample(0)
                assert components.enumerations == 1, 'polling must not create fresh OC userdata each second'
                monitor.cpus = [dict(monitor.cpus[0]) for i in range(30)]
                before = components.cpu.reads
                monitor.poll_others(0)
                assert components.cpu.reads - before == 3
                before = components.cpu.reads
                monitor.poll_others(0)
                assert components.cpu.reads - before == 4
                monitor.connect('addr-a')
                assert components.enumerations == 2
                passed = True
                """);
    }

    @Test void taskCardsTrackObservedCompletionWithoutCountingStorageOrStateTransfers() throws Exception {
        run("""
                components.cpu.active = [{'name': 'a', 'size': 2}]
                components.cpu.pending = [{'name': 'a', 'size': 8}, {'name': 'water', 'amount': 1000}]
                components.cpu.stored = [{'name': 'ingredient', 'size': 999}]
                first = monitor.sample(0)
                assert len(first['tasks']) == 2
                a = next(r for r in first['tasks'] if r['stack']['name'] == 'a')
                assert (a['active'], a['pending'], a['done']) == (2, 8, 0)
                # Moving from pending to active is not completion.
                components.cpu.active[0]['size'] = 5
                components.cpu.pending[0]['size'] = 5
                assert next(r for r in monitor.sample(0)['tasks'] if r['stack']['name'] == 'a')['done'] == 0
                components.cpu.active[0]['size'] = 1
                sample = monitor.sample(0)
                a = next(r for r in sample['tasks'] if r['stack']['name'] == 'a')
                assert (a['active'], a['pending'], a['done'], a['total']) == (1, 5, 4, 10)
                assert first['tasks'][0]['done'] == 0, 'prior samples are immutable snapshots'
                # A merged batch adds outstanding work, leaving the observed done count alone.
                components.cpu.pending[0]['size'] = 15
                assert next(r for r in monitor.sample(0)['tasks'] if r['stack']['name'] == 'a')['done'] == 4
                components.cpu.active, components.cpu.pending = [], []
                sample = monitor.sample(0)
                assert next(r for r in sample['tasks'] if r['stack']['name'] == 'a')['done'] == 20
                assert next(r for r in sample['tasks'] if is_fluid(r['stack']))['done'] == 1000
                assert len(sample['tasks']) == 2, 'pre-existing CPU storage is not completed work'
                components.cpu.busy = False
                assert monitor.sample(0)['tasks'] == [] and not monitor.work, 'idle is not proof of success'
                passed = True
                """);
    }

    @Test void taskIdentityIncludesMetadataAndNbtAndObservationRestartsAfterErrors() throws Exception {
        run("""
                components.cpu.active = [{'name': 'same', 'damage': 0, 'size': 2, 'tag': [1]},
                                         {'name': 'same', 'damage': 1, 'size': 3, 'tag': [1]},
                                         {'name': 'same', 'damage': 0, 'size': 4, 'tag': [2]},
                                         {'name': 'same', 'amount': 1000}]
                components.cpu.pending = []
                assert len(monitor.sample(0)['tasks']) == 4
                components.cpu.active[0]['size'] = 1
                assert sum(r['done'] for r in monitor.sample(0)['tasks']) == 1
                components.cpu.output = {'name': 'new-output', 'size': 1}
                assert sum(r['done'] for r in monitor.sample(0)['tasks']) == 0, 'different target means fresh observation'
                components.cpu.pending = (None, 'offline')
                assert monitor.sample(0)['tasks'] == [] and not monitor.work
                components.cpu.pending = []
                assert sum(r['done'] for r in monitor.sample(0)['tasks']) == 0
                monitor.reset_progress()
                assert not monitor.work and monitor.target is None
                assert stack_key({'name': 'a', 'size': 1, 'nbt': {'b': 2, 'a': 1}}) == stack_key({'name': 'a', 'size': 99, 'nbt': {'a': 1, 'b': 2}})
                assert compact_count(12500) == '12.5k'
                passed = True
                """);
    }

    @Test void completedTaskHistoryIsBoundedAndFinalPollCannotInventSuccess() throws Exception {
        run("""
                components.cpu.active = [{'name': 'item' + str(i), 'size': 1} for i in range(520)]
                components.cpu.pending = []
                sample = monitor.sample(0)
                assert len(sample['tasks']) == 512 and '512' in sample['warning']
                assert sample['remaining'] == 520, 'overall progress still includes every type'
                def finish_during_poll():
                    components.cpu.busy = False
                    return []
                components.cpu.pendingItems = finish_during_poll
                sample = monitor.sample(0)
                assert not sample['tasks'] and sample['progress'] is None and not monitor.work
                passed = True
                """);
    }

    private static void run(String source) throws Exception {
        String model = Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/lib/aemon.py"));
        MpyModule main = MpyLoader.load(MpyCross.compile(model + "\n" + FIXTURE + source, "aemon_test.py"), new QstrPool());
        Vm vm = new Vm(main, new HashMap<>());
        vm.start(new Frame(main.root));
        try {
            for (int i = 0; i < 2_000_000 && !vm.isFinished(); i++) vm.step();
        } catch (PyException error) {
            throw new AssertionError(error.getMessage() + "\n" + error.pyTraceback(), error);
        }
        assertTrue(vm.isFinished());
        assertEquals(Boolean.TRUE, vm.globals.get("passed"));
    }
}
