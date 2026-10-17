package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;
import java.net.http.HttpClient;
import net.mpy.runtime.PyObj;
import org.junit.jupiter.api.Test;

class WebAeHistoryTest {
    @Test void historySamplesWithoutBrowsersAndSurvivesGapsZerosAndRingWrap() throws Exception {
        var h = TkPythonTest.shellHost("""
            import aestorage
            class Ae:
                size = 10
                fail = False
                def getItemsInNetwork(self):
                    return [{'name': 'minecraft:iron_ingot', 'size': self.size}]
                def getFluidsInNetwork(self):
                    if self.fail:
                        raise OSError('fluid failed')
                    return []
            class Components:
                ae = Ae()
                def proxy(self, address):
                    return self.ae
            c = Components()
            clock = [0]
            inv = aestorage.Inventory(c, address='a', clock=lambda: clock[0], history_points=3)
            inv.poll()
            identity = inv.cached['entries'][0]['id']
            assert inv.history(identity)['points'] == [[0, '10']]
            c.ae.size = 9007199254740993
            clock[0] = 2
            inv.snapshot()
            assert inv.history(identity)['points'] == [[0, '10']]
            clock[0] = 5
            inv.poll()
            assert inv.history(identity)['points'][-1] == [5, '9007199254740993']
            c.ae.fail = True
            clock[0] = 10
            inv.poll()
            assert inv.history(identity)['points'][-1] == [10, None]
            c.ae.fail = False
            c.ae.size = 0
            clock[0] = 15
            inv.poll()
            assert inv.cached['entries'] == []
            assert inv.history(identity)['points'] == [[5, '9007199254740993'], [10, None], [15, '0']]
            clock[0] = 0
            inv.poll()
            assert inv.history(identity)['points'] == [[0, '0']]
            inv.address = 'b'
            c.ae.size = 20
            clock[0] = 5
            inv.poll()
            assert inv.history(identity) is None
            assert len(inv.tracks) == 1
            passed = True
            """);
        WebAeTest.modules(h); h.runUntil("passed");
    }

    @Test void identitiesUseUntruncatedNamesMetadataAndNbtAndEvictOldHistories() throws Exception {
        var h = TkPythonTest.shellHost("""
            import aestorage
            class Ae:
                items = []
                def getItemsInNetwork(self):
                    return self.items
                def getFluidsInNetwork(self):
                    return []
            class Components:
                ae = Ae()
                def proxy(self, address):
                    return self.ae
            c = Components()
            clock = [0]
            inv = aestorage.Inventory(c, address='a', clock=lambda: clock[0], history_points=2)
            c.ae.items = [{'name': 'same', 'damage': 1, 'size': 2}, {'name': 'same', 'damage': 2, 'size': 3}, {'name': 'same', 'damage': 1, 'size': 4}, {'name': 'same', 'damage': 1, 'tag': {'a': 1}, 'size': 5}, {'name': 'x' * 150 + 'a', 'size': 6}, {'name': 'x' * 150 + 'b', 'size': 7}]
            s = inv.snapshot()
            assert len(s['entries']) == 5
            assert s['entries'][0]['amount'] == '6'
            assert len(set(e['id'] for e in s['entries'])) == 5
            identity = s['entries'][0]['id']
            c.ae.items = [{'name': 'new:' + str(i), 'size': 1} for i in range(256)] + [{'name': 'same', 'damage': 1, 'size': 99}]
            clock[0] = 5
            inv.poll()
            assert inv.cached['limited']
            assert inv.history(identity)['points'][-1] == [5, '99']
            for batch in range(3):
                c.ae.items = [{'name': str(batch) + ':' + str(i), 'size': i + 1} for i in range(256)]
                clock[0] += 5
                inv.poll()
            assert len(inv.tracks) == 512
            assert inv.history(identity) is None
            assert all(len(t['values']) == 2 for t in inv.tracks.values())
            passed = True
            """);
        WebAeTest.modules(h); h.runUntil("passed");
    }

    @Test void historyEndpointIsAuthenticatedAndSamplingRunsWithoutRequests() throws Exception {
        try (var f = new WebAeTest.Fixture(); var client = HttpClient.newHttpClient()) {
            for (int i = 0; i < 30; i++) f.tick();
            assertEquals(1, f.itemReads);
            var storage = WebAeBoundaryTest.json(f.request(client, "/ae/api/storage", "GET", true));
            var entries = (PyObj.PyList) storage.map.get("entries");
            String id = (String) ((PyObj.PyDict)entries.items.get(0)).map.get("id");
            f.version = 2; f.h.time += 6;
            for (int i = 0; i < 30; i++) f.tick();
            assertEquals(2, f.itemReads, "sampling must run with no browser requests");
            String path = "/ae/api/history/" + id;
            assertEquals(401, f.request(client, path, "GET", false).statusCode());
            var history = f.request(client, path, "GET", true);
            assertEquals(200, history.statusCode());
            assertTrue(history.body().contains("4096")); assertTrue(history.body().contains("4224"));
            assertEquals("", f.request(client, path, "HEAD", true).body());
            assertEquals(404, f.request(client, "/ae/api/history/missing", "GET", true).statusCode());
            assertEquals(405, f.request(client, path, "POST", true).statusCode());
        }
    }
}
