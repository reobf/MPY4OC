package reobf.mpy4oc.main.arch;

import java.nio.file.Files;
import java.nio.file.Path;
import net.mpy.vm.Vm;
import org.junit.jupiter.api.Test;

class MpyosFinalStdlibTest {
    @Test void backgroundLoggingAndHelpUseTheJobOutputQueue() throws Exception {
        var h = TkPythonTest.shellHost("""
            import _process
            FAKE_VFS.files['/home/log.py'] = '''
            import argparse, logging, sys
            p = argparse.ArgumentParser(prog='background')
            p.add_argument('--number', type=int)
            ns = p.parse_args()
            logging.basicConfig(level=logging.INFO, format='%(message)s', force=True)
            logging.info('number=%s', ns.number)
            p.print_help()
            '''
            pid = os.spawnv(os.P_NOWAIT, '/home/log.py', ['log', '--number', '7'])
            result = os.waitpid(pid,0)
            assert result == (pid,0), result
            text = ''.join(_process.runtime()._job_output)
            assert 'number=7' in text and 'usage: background' in text and '--number' in text, text
            passed = True
            """);
        h.runUntil("passed");
    }
    private TkPythonTest.Host host(String source) throws Exception {
        return TkPythonTest.shellHost(Files.readString(Path.of("src/test/resources/stdio-fixture.py")) + "\n"
                + Files.readString(Path.of("src/test/resources/files-disk-fixture.py")) + "\n" + source);
    }
    @Test void logFilesUseVirtualCwdAndSurviveSnapshot() throws Exception {
        var h = host("""
            import logging, argparse
            from pathlib import Path
            os.chdir('/home')
            handler = logging.FileHandler(Path('program.log'), delay=True)
            assert not Path('program.log').exists()
            logger = logging.getLogger('disk')
            logger.propagate = False
            logger.setLevel(logging.INFO)
            handler.setFormatter(logging.Formatter('%(levelname)s:%(message)s'))
            logger.addHandler(handler)
            logger.info('before')
            assert Path('program.log').read_text() == 'INFO:before\\n'
            ready = True
            while not globals().get('resume'):
                computer._yield_tick()
            logger.info('after')
            handler.close()
            assert Path('program.log').read_text() == 'INFO:before\\nINFO:after\\n'
            parser = argparse.ArgumentParser(prog='read')
            parser.add_argument('source', type=argparse.FileType('r'))
            parsed = parser.parse_args(['program.log'])
            assert parsed.source.readline() == 'INFO:before\\n'
            parsed.source.close()
            raises(OSError, lambda: logging.FileHandler('/mnt/ro/error.log'))
            assert not boot.handles and not readonly.handles
            passed = True
            """);
        h.runUntil("ready");
        byte[] saved = h.vm.saveSnapshot(h.registry);
        var restored = new TkPythonTest.Host();
        restored.stepBudget = 300;
        restored.bootstrap = true;
        restored.start("pass");
        restored.vm = Vm.fromSnapshot(saved, restored.globals, restored.registry);
        restored.vm.globals.put("resume", true);
        restored.runUntil("passed");
    }
}
