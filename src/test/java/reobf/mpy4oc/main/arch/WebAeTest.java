package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.mpy.compiler.MpyCross;
import net.mpy.runtime.PyObj;
import net.mpy.runtime.PyException;
import net.mpy.vm.HostFunction;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.web.*;

/** The production Python dashboard, VM, OC conversion and HTTP gateway with only AE hardware mocked. */
public class WebAeTest {
    static final String SCRIPT = """
        import aestorage, webserver
        class Card:
            def open(self,path):
                return __web_open(path)
            def close(self,route):
                return __web_close(route)
            def hasRoute(self,route):
                return __web_has(route)
            def poll(self,route):
                return __web_poll(route)
            def pending(self,request):
                return __web_pending(request)
            def respond(self,*args):
                return __web_reply(*args)
        class Ae:
            def getItemsInNetwork(self):
                return __ae_items()
            def getFluidsInNetwork(self):
                return __ae_fluids()
            def getCraftables(self):
                return [Recipe(i) for i in range(__ae_recipe_count())]
            def getCpus(self):
                values = __ae_cpus()
                for index, row in enumerate(values):
                    row['cpu'] = Cpu(index, __ae_cpu_epoch())
                return values
        class Cpu:
            def __init__(self,index,epoch):
                self.index, self.epoch = index, epoch
            def isBusy(self):
                return __ae_cpu_read(self.index, self.epoch, 'busy')
            def finalOutput(self):
                return __ae_cpu_read(self.index, self.epoch, 'output')
            def activeItems(self):
                return __ae_cpu_read(self.index, self.epoch, 'active')
            def pendingItems(self):
                return __ae_cpu_read(self.index, self.epoch, 'pending')
            def storedItems(self):
                return __ae_cpu_read(self.index, self.epoch, 'stored')
            def getJobId(self):
                return __ae_cpu_read(self.index, self.epoch, 'job')
            def cancelJob(self,job):
                return __ae_cpu_cancel(self.index, self.epoch, job)
        class Recipe:
            def __init__(self,index):
                self.index = index
            def getStack(self):
                return __ae_recipe_stack(self.index)
            def request(self,amount):
                return Status(__ae_craft_request(self.index, amount))
        class Status:
            def __init__(self,identity):
                self.identity = identity
            def isComputing(self):
                return __ae_craft_state(self.identity) == 0
            def hasFailed(self):
                return (__ae_craft_state(self.identity) == 3, 'request failed (missing resources?)')
            def isCanceled(self):
                return __ae_craft_state(self.identity) == 4
            def isDone(self):
                return __ae_craft_state(self.identity) == 2
            def cancel(self):
                return __ae_craft_cancel(self.identity)
        class Components:
            def list(self,kind):
                return {'demo-ae-network': 'me_controller'} if kind == 'me_controller' else {}
            def proxy(self,address):
                assert address == 'demo-ae-network'
                return Ae()
        inventory = aestorage.Inventory(Components())
        server = aestorage.Server(inventory, path='/ae', card=Card())
        ready = True
        while True:
            server.handle_request()
            computer._yield_tick()
        """;

    static void modules(TkPythonTest.Host h) throws Exception {
        Map<String, byte[]> modules = new HashMap<>();
        for (String name : new String[]{"shell", "fs", "osfs", "ospath", "event", "aemon", "webserver", "aecrafting", "aecpus", "aestorage"})
            modules.put(name, MpyCross.compile(Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/lib/" + name + ".py")), name + ".py"));
        h.vm.setModuleFinder(modules::get);
    }

    static final class Fixture implements AutoCloseable {
        final WebServices.Service service;
        final TkPythonTest.Host h;
        final WebGateway gateway;
        int version, itemReads, craftCalls, craftState, lastRecipe, lastAmount, recipeCount = 5;
        String latestRoute;
        boolean offline, empty, large, fluidOffline, extraFluids;
        int cancelCalls;
        int cpuCount=3, cpuBusyCount=2, cpuReads, cpuDetailReads, cpuEpoch, cpuErrorIndex=-1, cpuProgress;
        boolean cpuOffline, cpuOutputMissing, cpuLarge, cpuFinishDuringRead, duplicateCpuNames;
        int cpuJobGeneration, cpuCancelCalls;
        boolean cpuReplaceDuringCancel, cpuReplaceDuringRead;
        final Set<String> canceledCpuJobs = new HashSet<>();
        final Set<Integer> canceledJobs = new HashSet<>();
        Fixture() throws Exception {
            this("ae-test", "local-demo");
        }
        Fixture(String id, String password) throws Exception {
            service = WebServices.register(id, password);
            h = TkPythonTest.shellHost(SCRIPT); modules(h);
            Map<String, HostFunction> calls = new HashMap<>();
            calls.put("__web_open", a -> latestRoute = service.open((String)a[0]));
            calls.put("__web_close", a -> { service.remove((String)a[0]); return PyObj.NONE; });
            calls.put("__web_has", a -> service.hasRoute((String)a[0]));
            calls.put("__web_pending", a -> service.pending((String)a[0]));
            calls.put("__web_poll", a -> { var r = service.poll((String)a[0]); return r == null ? PyObj.NONE : oc(r.data()); });
            calls.put("__web_reply", a -> {
                List<String[]> headers = new ArrayList<>();
                for (String line : ((String)a[2]).split("\r\n")) { int c = line.indexOf(':'); headers.add(new String[]{line.substring(0,c), line.substring(c+1).trim()}); }
                return service.finish((String)a[0], new WebServices.Response(((Number)a[1]).intValue(), headers, ((PyObj.Bytes)a[3]).data));
            });
            calls.put("__ae_items", a -> {
                itemReads++;
                if (offline) throw new PyException("OSError", "AE network disconnected (test fixture)");
                if (empty) return oc(new Object[0]);
                if (large) return inventoryRows(false);
                return oc(new Object[]{
                    Map.of("name", "minecraft:iron_ingot", "label", "Iron Ingot", "size", 4096 + version * 64),
                    Map.of("name", "gregtech:gt.metaitem.01", "label", "Copper Ingot", "size", 12000, "damage", 11035),
                    Map.of("name", "minecraft:glass", "label", "Glass", "size", 256),
                    Map.of("name", "test:tagged", "label", "<img src=x onerror=alert(1)>", "size", 1, "hasTag", true),
                    Map.of("name", "minecraft:diamond", "label", "Diamond", "size", 0)});
            });
            calls.put("__ae_fluids", a -> {
                if (fluidOffline) throw new PyException("OSError", "AE fluid storage unavailable (test fixture)");
                if (large) return inventoryRows(true);
                if (extraFluids && !empty) return oc(new Object[]{
                    Map.of("name", "water", "label", "Water", "amount", 500000),
                    Map.of("name", "lava", "label", "Lava", "amount", 8000),
                    Map.of("name", "oxygen", "label", "Oxygen", "amount", 64000),
                    Map.of("name", "steam", "label", "Steam", "amount", 128000),
                    Map.of("name", "molten.iron", "label", "Molten Iron", "amount", 14400)});
                return oc(empty ? new Object[0] : new Object[]{
                Map.of("name", "water", "label", "Water", "amount", 500000),
                Map.of("name", "lava", "label", "Lava", "amount", 8000)});
            });
            calls.put("__ae_cpus", a -> {
                cpuReads++;
                if(offline || cpuOffline) throw new PyException("OSError","AE CPU network disconnected (test fixture)");
                Object[] values=new Object[cpuCount];
                for(int i=0;i<values.length;i++)values[i]=Map.of("name",duplicateCpuNames?"Same CPU":i==0?"Assembly CPU":i==1?"Chemical CPU":"CPU "+(i+1),"storage",65536L*(i+1),"coprocessors",i+1,"busy",cpuBusy(i));
                return oc(values);
            });
            calls.put("__ae_cpu_epoch", a -> (long)cpuEpoch);
            calls.put("__ae_cpu_read", a -> {
                cpuDetailReads++;
                int i=((Number)a[0]).intValue(),epoch=((Number)a[1]).intValue();String method=(String)a[2];
                if(offline || cpuOffline || epoch!=cpuEpoch || i>=cpuCount)throw new PyException("OSError","Stale CPU handle");
                if(method.equals("busy"))return cpuBusy(i);
                if(method.equals("job"))return cpuBusy(i)?cpuJob(i):PyObj.NONE;
                if(method.equals("output")) {
                    if(cpuOutputMissing)return new PyObj.Tuple(new Object[]{PyObj.NONE,"No crafting monitor"});
                    return oc(i==1?Map.of("name","oxygen","label","Oxygen","amount",64000):Map.of("name","minecraft:iron_ingot","label","Iron Ingot","size",64));
                }
                if(method.equals("pending") && i==cpuErrorIndex)throw new PyException("OSError","Pending data unavailable");
                if(method.equals("pending") && cpuFinishDuringRead)cpuBusyCount=0;
                if(method.equals("pending") && cpuReplaceDuringRead)cpuJobGeneration++;
                if(cpuLarge)return inventoryRows(i==1);
                if(i==1)return oc(new Object[]{Map.of("name",method.equals("stored")?"water":"oxygen","label",method.equals("stored")?"Water":"Oxygen","amount",method.equals("active")?12000:64000)});
                return oc(new Object[]{Map.of("name",method.equals("active")?"minecraft:gold_ingot":"minecraft:iron_ingot","label",method.equals("active")?"Gold Ingot":"Iron Ingot","size",method.equals("active")?32:Math.max(0,64-cpuProgress))});
            });
            calls.put("__ae_cpu_cancel", a -> {
                int i=((Number)a[0]).intValue(),epoch=((Number)a[1]).intValue();
                if(offline || cpuOffline || epoch!=cpuEpoch || i>=cpuCount)throw new PyException("OSError","Stale CPU handle");
                if(cpuReplaceDuringCancel)cpuJobGeneration++;
                if(!cpuBusy(i) || !cpuJob(i).equals(a[2]))return new PyObj.Tuple(new Object[]{false,"Task has finished or changed. Refresh the CPU details."});
                cpuCancelCalls++;canceledCpuJobs.add(cpuJob(i));return true;
            });
            calls.put("__ae_recipe_count", a -> {
                if (offline) throw new PyException("OSError", "AE network disconnected (test fixture)");
                return (long)recipeCount;
            });
            calls.put("__ae_recipe_stack", a -> {
                int index = ((Number)a[0]).intValue();
                return oc(switch (index) {
                    case 0 -> Map.of("name", "minecraft:iron_ingot", "label", "Iron Ingot", "size", 1);
                    case 1 -> Map.of("name", "minecraft:glass", "label", "Glass", "size", 1);
                    case 2 -> Map.of("name", "minecraft:diamond", "label", "Diamond", "size", 1);
                    default -> Map.of("name", "test:tagged", "label", "<img src=x onerror=alert(1)>", "size", 1, "hasTag", true);
                });
            });
            calls.put("__ae_craft_request", a -> {
                craftCalls++; lastRecipe = ((Number)a[0]).intValue(); lastAmount = ((Number)a[1]).intValue();
                if (offline) throw new PyException("OSError", "AE network disconnected during submission");
                return (long)craftCalls;
            });
            calls.put("__ae_craft_state", a -> {
                if (canceledJobs.contains(((Number)a[0]).intValue())) return 4L;
                if (craftState == 5) throw new PyException("OSError", "AE status temporarily unavailable");
                return (long)craftState;
            });
            calls.put("__ae_craft_cancel", a -> {
                int job = ((Number)a[0]).intValue();
                if (canceledJobs.contains(job)) return true;
                if (craftState == 5) throw new PyException("OSError", "AE cancellation temporarily unavailable");
                if (craftState == 0) return new PyObj.Tuple(new Object[]{false, "computing"});
                if (craftState == 2) return new PyObj.Tuple(new Object[]{false, "done"});
                cancelCalls++; canceledJobs.add(job); return true;
            });
            calls.forEach((name, fn) -> { h.registry.register(name, fn); h.vm.globals.put(name, fn); h.globals.put(name, fn); });
            h.runUntil("ready");
            gateway = new WebGateway("127.0.0.1", 0, null, false);
        }
        String cpuJob(int index) { return "cpu-task-"+cpuEpoch+"-"+index+"-"+cpuJobGeneration; }
        boolean cpuBusy(int index) { return index<cpuBusyCount && !canceledCpuJobs.contains(cpuJob(index)); }
        Object inventoryRows(boolean fluid) {
            Object[] entries = new Object[260];
            for (int i = 0; i < entries.length; i++) entries[i] = Map.of(
                "name", (fluid ? "fluid:" : "item:") + "\ud83d\udce6".repeat(140) + i,
                "label", String.format("%s %03d ", fluid ? "Fluid" : "Item", i) + "\ud83d\udce6".repeat(140),
                fluid ? "amount" : "size", 9007199254740993L + i);
            return oc(entries);
        }
        Object oc(Object value) { return h.converter.toPy(li.cil.oc.server.driver.Registry$.MODULE$.convert(new Object[]{value})[0]); }
        void tick() {
            try { h.vm.step(new int[]{10000}); }
            catch (PyException e) { throw new AssertionError(e.getMessage() + "\n" + e.pyTraceback(), e); }
        }
        HttpResponse<String> request(HttpClient client, String path, String method, boolean auth) throws Exception {
            return request(client, path, method, auth ? service.id + ":" + service.password : null);
        }
        HttpResponse<String> request(HttpClient client, String path, String method, String credentials) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gateway.port() + path)).method(method, HttpRequest.BodyPublishers.noBody());
            if (credentials != null) builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
            var future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!future.isDone() && System.nanoTime() < deadline) { tick(); Thread.sleep(1); }
            return future.get(1, TimeUnit.SECONDS);
        }
        @Override public void close() { gateway.close(); service.close(); }
    }

    @Test void authenticatedBrowserRoutesInventoryRefreshAndRecovery() throws Exception {
        try (Fixture f = new Fixture(); HttpClient client = HttpClient.newHttpClient()) {
            var denied = f.request(client, "/ae/", "GET", false);
            assertEquals(401, denied.statusCode()); assertEquals(1, f.itemReads, "background sampling starts without an authenticated browser");
            var page = f.request(client, "/ae/", "GET", true);
            assertEquals(200, page.statusCode()); assertTrue(page.body().contains("AE Storage Monitor"));
            assertTrue(page.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
            var first = f.request(client, "/ae/api/storage", "GET", true);
            assertEquals(200, first.statusCode()); assertTrue(first.body().contains("Iron Ingot"));
            assertTrue(first.body().contains("\"fluid_amount\": \"508000\""));
            assertTrue(first.body().contains("\"item_count\": \"16353\""));
            assertFalse(first.body().contains("Diamond"));
            int reads = f.itemReads;
            f.request(client, "/ae/api/storage", "GET", true); assertEquals(reads, f.itemReads, "two-second cache");
            f.version++; f.h.time += 3;
            assertTrue(f.request(client, "/ae/api/storage", "GET", true).body().contains("\"amount\": \"4160\""));
            f.offline = true; f.h.time += 3;
            var offline = f.request(client, "/ae/api/storage", "GET", true);
            assertEquals(503, offline.statusCode()); assertTrue(offline.body().contains("disconnected"));
            f.offline = false; f.empty = true; f.h.time += 3;
            var empty = f.request(client, "/ae/api/storage", "GET", true);
            assertEquals(200, empty.statusCode()); assertTrue(empty.body().contains("\"entries\": []"));
            assertEquals(405, f.request(client, "/ae/api/storage", "POST", true).statusCode());
            assertEquals(404, f.request(client, "/ae/unknown", "GET", true).statusCode());
            assertEquals("", f.request(client, "/ae/", "HEAD", true).body());
        }
    }

    @Test void vmHandlesSelectionBoundsClockRewindAndFluidFailure() throws Exception {
        var h = TkPythonTest.shellHost("""
            import aestorage
            class Ae:
                fail = False
                def getItemsInNetwork(self):
                    return [{'name': 'item' + str(i), 'label': 'x' * 300, 'size': 9007199254740993} for i in range(260)]
                def getFluidsInNetwork(self):
                    if self.fail:
                        return (None, 'no grid')
                    return {1: {'name': 'water', 'amount': 1000}}
            class Components:
                found = {}
                ae = Ae()
                def list(self,kind):
                    return self.found if kind == 'me_controller' else {}
                def proxy(self,address):
                    return self.ae
            c = Components()
            clock = [10]
            inv = aestorage.Inventory(c, clock=lambda: clock[0])
            assert 'No AE' in inv.snapshot()['error']
            c.found = {'a': 'me_controller', 'b': 'me_controller'}
            clock[0] += 3
            assert 'Multiple' in inv.snapshot()['error']
            inv.address = 'a'
            clock[0] += 3
            s = inv.snapshot()
            assert s['ok'] and s['limited'] and len(s['entries']) == 257
            assert s['item_types'] == 260 and s['fluid_types'] == 1
            assert s['entries'][0]['amount'] == '9007199254740993'
            assert len(s['entries'][0]['label']) == 128
            c.ae.fail = True
            clock[0] = 0
            s = inv.snapshot()
            assert not s['ok'] and s['entries'] == [] and s['error'] == 'no grid'
            passed = True
            """);
        modules(h); h.runUntil("passed");
        MpyCross.compile(Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/usr/share/examples/web_ae.py")), "web_ae.py");
    }

    /** Optional, bounded browser test fixture. No test endpoint is added to the production app. */
    public static void main(String[] args) throws Exception {
        Path control = Path.of("build/web-ae-control.txt");
        Files.writeString(control, "live");
        try (Fixture f = new Fixture()) {
            f.extraFluids = true;
            String url = "http://127.0.0.1:" + f.gateway.port() + "/ae/";
            Files.writeString(Path.of("build/web-ae-browser-url.txt"), url);
            System.out.println("AE_BROWSER_READY " + url + " (mock AE; Basic ae-test/local-demo)");
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(20);
            String previous = "live";
            long next = 0;
            while (System.nanoTime() < deadline) {
                if (System.nanoTime() >= next) {
                    String mode = Files.readString(control).trim();
                    if (mode.equals("stop")) break;
                    if (!mode.equals(previous)) {
                        f.version++; f.h.time += 6; f.offline = mode.equals("offline"); f.empty = mode.equals("empty");
                        f.large = mode.equals("large"); f.fluidOffline = mode.equals("fluid-offline"); previous = mode;
                        f.cpuOffline=mode.equals("cpu-offline");f.cpuOutputMissing=mode.equals("cpu-no-monitor");
                        f.cpuBusyCount=mode.equals("cpu-idle")?0:2;f.cpuProgress=mode.equals("cpu-progress")?48:0;
                        if(mode.equals("cpu-replace"))f.cpuJobGeneration++;
                        if(mode.equals("cpu-new")){f.cpuEpoch++;f.cpuCount=5;f.cpuBusyCount=3;}
                        f.craftState = switch (mode) { case "craft-running" -> 1; case "craft-done" -> 2; case "craft-failed" -> 3; case "craft-canceled" -> 4; case "craft-unavailable" -> 5; default -> 0; };
                        System.out.println("AE_BROWSER_MODE " + mode);
                    }
                    next = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
                }
                f.tick(); Thread.sleep(2);
            }
        }
    }
}
