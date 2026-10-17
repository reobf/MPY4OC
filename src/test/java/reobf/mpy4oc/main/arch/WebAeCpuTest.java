package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;
import static reobf.mpy4oc.main.arch.WebAeCraftingTest.*;
import java.net.http.HttpClient;
import java.math.BigInteger;
import net.mpy.vm.Vm;
import org.junit.jupiter.api.Test;

class WebAeCpuTest {
    @Test void showsExternalTasksWithoutAnyWebOrderAndCachesAcrossReaders() throws Exception {
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()) {
            var list=json(f.request(c,"/ae/api/cpus","GET",true));
            assertEquals(3,list.get("total").getAsInt());assertEquals(2,list.get("busy").getAsInt());
            assertEquals("Assembly CPU",list.getAsJsonArray("entries").get(0).getAsJsonObject().get("name").getAsString());
            assertEquals("Chemical CPU",list.getAsJsonArray("entries").get(1).getAsJsonObject().get("name").getAsString());
            assertEquals(0,f.craftCalls);assertEquals(0,json(f.request(c,"/ae/api/orders","GET",true)).getAsJsonArray("orders").size());
            var detail=json(f.request(c,"/ae/api/cpus/detail/1","GET",true));
            assertEquals("oxygen",detail.getAsJsonObject("output").get("name").getAsString());
            assertEquals("12000",detail.getAsJsonObject("totals").getAsJsonObject("active").get("fluid").getAsString());
            int reads=f.cpuDetailReads;
            f.request(c,"/ae/api/cpus/detail/1","GET",true);f.request(c,"/ae/api/cpus","GET",true);
            assertEquals(reads,f.cpuDetailReads);assertEquals(1,f.cpuReads);
            f.h.time+=3;f.cpuProgress=48;
            var changed=json(f.request(c,"/ae/api/cpus/detail/0","GET",true));
            assertEquals("16",changed.getAsJsonObject("totals").getAsJsonObject("pending").get("items").getAsString());
            assertEquals(2,f.cpuReads);
        }
    }

    @Test void enumeratesEveryCpuAcrossPagesAndHandlesTopologyChanges() throws Exception {
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()) {
            f.cpuCount=130;f.duplicateCpuNames=true;
            var first=json(f.request(c,"/ae/api/cpus","GET",true));
            assertEquals(130,first.get("total").getAsInt());assertEquals(3,first.get("pages").getAsInt());assertEquals(64,first.getAsJsonArray("entries").size());
            var last=json(f.request(c,"/ae/api/cpus/page/2","GET",true));assertEquals(2,last.getAsJsonArray("entries").size());assertEquals(129,last.getAsJsonArray("entries").get(1).getAsJsonObject().get("index").getAsInt());
            assertEquals(200,f.request(c,"/ae/api/cpus/detail/129","GET",true).statusCode());
            f.cpuCount=1;f.cpuEpoch++;f.h.time+=3;
            var shrunk=json(f.request(c,"/ae/api/cpus/page/2","GET",true));assertEquals(0,shrunk.get("page").getAsInt());assertEquals(1,shrunk.get("total").getAsInt());
            assertEquals(400,f.request(c,"/ae/api/cpus/detail/129","GET",true).statusCode());
            assertEquals(200,f.request(c,"/ae/api/cpus/detail/0","GET",true).statusCode());
            f.cpuCount=0;f.h.time+=3;
            assertEquals(0,json(f.request(c,"/ae/api/cpus","GET",true)).get("total").getAsInt());
        }
    }

    @Test void survivesMissingMonitorPartialReadsCompletionAndDisconnects() throws Exception {
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()) {
            f.cpuOutputMissing=true;f.cpuErrorIndex=0;
            var partial=json(f.request(c,"/ae/api/cpus/detail/0","GET",true));
            assertTrue(partial.get("output").isJsonNull());assertEquals(2,partial.getAsJsonArray("warnings").size());
            assertEquals(1,partial.getAsJsonArray("active").size());assertFalse(partial.getAsJsonObject("totals").has("pending"));
            f.cpuOutputMissing=false;f.cpuErrorIndex=-1;f.cpuFinishDuringRead=true;f.h.time+=3;
            var done=json(f.request(c,"/ae/api/cpus/detail/0","GET",true));
            assertFalse(done.getAsJsonObject("cpu").get("busy").getAsBoolean());assertEquals(0,done.getAsJsonArray("active").size());assertTrue(done.get("output").isJsonNull());
            f.cpuOffline=true;f.h.time+=3;assertEquals(503,f.request(c,"/ae/api/cpus","GET",true).statusCode());
            f.cpuOffline=false;f.cpuEpoch++;f.cpuBusyCount=2;f.cpuFinishDuringRead=false;
            assertEquals(200,f.request(c,"/ae/api/cpus/detail/0","GET",true).statusCode());
        }
    }

    @Test void boundsVisibleWorkButKeepsExactTotalsAndReadOnlyAuthentication() throws Exception {
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()) {
            assertEquals(401,f.request(c,"/ae/api/cpus","GET",false).statusCode());
            assertEquals(405,f.request(c,"/ae/api/cpus/detail/0","POST",true).statusCode());
            for(String suffix:new String[]{"detail/-1","detail/abc","detail/999999999999","unknown"})assertEquals(400,f.request(c,"/ae/api/cpus/"+suffix,"GET",true).statusCode());
            f.cpuLarge=true;
            var detail=json(f.request(c,"/ae/api/cpus/detail/0","GET",true));
            assertEquals(128,detail.getAsJsonArray("active").size());assertEquals(3,detail.getAsJsonArray("limited").size());
            var total=detail.getAsJsonObject("totals").getAsJsonObject("active");assertEquals(260,total.get("types").getAsInt());
            assertEquals(new BigInteger("9007199254740993").multiply(BigInteger.valueOf(260)).add(BigInteger.valueOf(259L*260/2)).toString(),total.get("items").getAsString());
            assertEquals(0,f.craftCalls);assertEquals(0,f.cancelCalls);
        }
    }

    @Test void restoredPageDropsOldHandlesAndDiscoversExternalTasksAgain() throws Exception {
        byte[] snapshot;
        try(var old=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()) {
            old.request(c,"/ae/api/cpus/detail/0","GET",true);
            snapshot=old.h.vm.saveSnapshot(old.h.registry);
        }
        try(var fresh=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()) {
            fresh.cpuEpoch=12;fresh.cpuCount=4;fresh.cpuBusyCount=3;
            fresh.service.remove(fresh.latestRoute);
            fresh.h.vm=Vm.fromSnapshot(snapshot,fresh.h.globals,fresh.h.registry);WebAeTest.modules(fresh.h);
            for(int i=0;i<30;i++)fresh.tick();
            var list=json(fresh.request(c,"/ae/api/cpus","GET",true));assertEquals(4,list.get("total").getAsInt());assertEquals(3,list.get("busy").getAsInt());
            var detail=json(fresh.request(c,"/ae/api/cpus/detail/1","GET",true));assertEquals("oxygen",detail.getAsJsonObject("output").get("name").getAsString());
            assertEquals(1,fresh.cpuReads);assertEquals(0,fresh.craftCalls);
        }
    }
}
