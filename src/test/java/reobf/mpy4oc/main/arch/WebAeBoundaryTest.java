package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import net.mpy.runtime.Json;
import net.mpy.runtime.PyObj;
import net.mpy.vm.Vm;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.web.WebServices;

class WebAeBoundaryTest {
    static PyObj.PyDict json(HttpResponse<String> response) { return (PyObj.PyDict) Json.loads(response.body()); }
    static HttpRequest request(int port, String credentials) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ae/api/storage"))
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8))).build();
    }
    static List<HttpResponse<String>> collect(List<CompletableFuture<HttpResponse<String>>> futures, WebAeTest.Fixture... fixtures) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (futures.stream().anyMatch(f -> !f.isDone()) && System.nanoTime() < deadline) {
            for (var f : fixtures) f.tick();
            Thread.sleep(1);
        }
        List<HttpResponse<String>> results = new ArrayList<>();
        for (var f : futures) results.add(f.get(1, TimeUnit.SECONDS));
        return results;
    }

    @Test void independentAccountsOnOnePortNeverShareInventoryOrAcceptWrongPassword() throws Exception {
        try (var a = new WebAeTest.Fixture(); var b = new WebAeTest.Fixture("second-factory", "second-password"); var client = HttpClient.newHttpClient()) {
            b.version = 100;
            for (String credentials : new String[]{"ae-test:wrong", "unknown:local-demo", "ae-test:second-password", "second-factory:local-demo"})
                assertEquals(401, a.request(client, "/ae/api/storage", "GET", credentials).statusCode());
            assertEquals(1, a.itemReads); assertEquals(0, b.itemReads);
            var requests = List.of(
                client.sendAsync(request(a.gateway.port(), "ae-test:local-demo"), HttpResponse.BodyHandlers.ofString()),
                client.sendAsync(request(a.gateway.port(), "second-factory:second-password"), HttpResponse.BodyHandlers.ofString()));
            var replies = collect(requests, a, b);
            assertEquals(200, replies.get(0).statusCode()); assertEquals(200, replies.get(1).statusCode());
            assertEquals("16353", json(replies.get(0)).map.get("item_count"));
            assertEquals("22753", json(replies.get(1)).map.get("item_count"));
        }
    }

    @Test void eightSimultaneousClientsShareOneInventoryRead() throws Exception {
        try (var f = new WebAeTest.Fixture(); var client = HttpClient.newHttpClient()) {
            List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) futures.add(client.sendAsync(request(f.gateway.port(), "ae-test:local-demo"), HttpResponse.BodyHandlers.ofString()));
            var replies = collect(futures, f);
            for (var r : replies) { assertEquals(200, r.statusCode()); assertEquals(replies.get(0).body(), r.body()); }
            assertEquals(1, f.itemReads, "concurrent readers must share the cached hardware snapshot");
        }
    }

    @Test void largeUnicodeInventoryPreservesIntegersAndStaysWithinResponseBudget() throws Exception {
        try (var f = new WebAeTest.Fixture(); var client = HttpClient.newHttpClient()) {
            f.large = true;
            var r = f.request(client, "/ae/api/storage", "GET", true);
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.body().getBytes(StandardCharsets.UTF_8).length < WebServices.RESPONSE_LIMIT);
            var data = json(r); assertEquals(Boolean.TRUE, data.map.get("limited"));
            assertEquals(260L, data.map.get("item_types")); assertEquals(260L, data.map.get("fluid_types"));
            var entries = (PyObj.PyList)data.map.get("entries"); assertEquals(512, entries.items.size());
            assertEquals("9007199254740993", ((PyObj.PyDict)entries.items.get(0)).map.get("amount"));
            for (Object entry : entries.items) {
                var row = (PyObj.PyDict) entry;
                for (String field : new String[]{"name", "label"}) {
                    String value = (String) row.map.get(field);
                    assertTrue(value.endsWith("\ud83d\udce6"), "truncation must preserve complete Unicode characters: " + field);
                    assertTrue(value.length() <= 128);
                }
            }
            assertEquals("2341871806232691850", data.map.get("item_count"));
            assertEquals(data.map.get("item_count"), data.map.get("fluid_amount"));
        }
    }

    @Test void headPreservesGetRepresentationLengthWithoutSendingBody() throws Exception {
        try (var f = new WebAeTest.Fixture(); var client = HttpClient.newHttpClient()) {
            for (String path : new String[]{"/ae/", "/ae/api/storage"}) {
                var get = f.request(client, path, "GET", true);
                var head = f.request(client, path, "HEAD", true);
                assertEquals(200, head.statusCode()); assertEquals("", head.body());
                assertEquals(get.headers().firstValue("Content-Length"), head.headers().firstValue("Content-Length"));
            }
        }
    }

    @Test void savedDashboardReopensItsRouteAndRefreshesAnExpiredSnapshot() throws Exception {
        byte[] snapshot;
        try (var old = new WebAeTest.Fixture(); var client = HttpClient.newHttpClient()) {
            assertEquals(200, old.request(client, "/ae/api/storage", "GET", true).statusCode());
            snapshot = old.h.vm.saveSnapshot(old.h.registry);
        }
        try (var fresh = new WebAeTest.Fixture(); var client = HttpClient.newHttpClient()) {
            fresh.service.remove(fresh.latestRoute);
            fresh.h.vm = Vm.fromSnapshot(snapshot, fresh.h.globals, fresh.h.registry);
            WebAeTest.modules(fresh.h);
            fresh.version = 10; fresh.h.time = 100;
            for (int i = 0; i < 30; i++) fresh.tick();
            var r = fresh.request(client, "/ae/api/storage", "GET", true);
            assertEquals(200, r.statusCode(), r.body());
            assertEquals("16993", json(r).map.get("item_count"));
            assertEquals(1, fresh.itemReads);
            var history = fresh.request(client, "/ae/api/history/1", "GET", true);
            assertEquals(200, history.statusCode());
            assertTrue(history.body().contains("4096"));
            assertTrue(history.body().contains("4736"));
        }
    }
}
