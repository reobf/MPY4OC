package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;
import static reobf.mpy4oc.main.arch.WebAeCraftingTest.*;
import com.google.gson.JsonObject;
import java.net.http.HttpClient;
import net.mpy.vm.Vm;
import org.junit.jupiter.api.Test;

class WebAeCpuCancellationTest {
    static JsonObject detail(WebAeTest.Fixture f,HttpClient c,int index) throws Exception {
        return json(f.request(c,"/ae/api/cpus/detail/"+index,"GET",true));
    }
    static String body(JsonObject detail) {
        var data=new JsonObject();data.add("job",detail.get("job"));data.add("address",detail.get("address"));return data.toString();
    }
    @Test void cancelsExternalTaskWithoutWebOrdersAndRetriesOnlyThatTask() throws Exception {
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()) {
            f.duplicateCpuNames=true;f.cpuOutputMissing=true;
            var d=detail(f,c,1);String token=d.get("token").getAsString();
            assertFalse(d.get("job").isJsonNull());assertTrue(d.get("output").isJsonNull());
            assertTrue(json(post(f,c,"cpus/cancel/1",body(d),token)).get("canceled").getAsBoolean());
            json(post(f,c,"cpus/cancel/1",body(d),token));assertEquals(1,f.cpuCancelCalls);
            assertFalse(detail(f,c,1).getAsJsonObject("cpu").get("busy").getAsBoolean());
            assertTrue(detail(f,c,0).getAsJsonObject("cpu").get("busy").getAsBoolean());
            assertEquals(0,f.craftCalls);assertEquals(0,f.cancelCalls);
            assertEquals(0,json(f.request(c,"/ae/api/orders","GET",true)).getAsJsonArray("orders").size());
            f.cpuJobGeneration++; // retry of a successful old request never touches the next job
            json(post(f,c,"cpus/cancel/1",body(d),token));assertEquals(1,f.cpuCancelCalls);assertTrue(f.cpuBusy(1));
        }
    }
    @Test void rejectsReplacedTaskEvenAtTheFinalCallbackAndInvalidatesReadCache() throws Exception {
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()) {
            var d=detail(f,c,0);String token=d.get("token").getAsString();
            f.cpuReplaceDuringCancel=true;
            assertEquals(409,post(f,c,"cpus/cancel/0",body(d),token).statusCode());assertEquals(0,f.cpuCancelCalls);
            f.cpuReplaceDuringCancel=false;
            var newer=detail(f,c,0);assertNotEquals(d.get("job"),newer.get("job"));
            json(post(f,c,"cpus/cancel/0",body(newer),token));assertEquals(1,f.cpuCancelCalls);
            assertTrue(detail(f,c,0).get("job").isJsonNull());
        }
    }
    @Test void reorderingCompletionNetworkChangesAndDisconnectsCannotRedirectCancellation() throws Exception {
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()) {
            var d=detail(f,c,0);String token=d.get("token").getAsString();
            f.cpuEpoch++;assertEquals(409,post(f,c,"cpus/cancel/0",body(d),token).statusCode());
            d=detail(f,c,0);f.cpuBusyCount=0;assertEquals(409,post(f,c,"cpus/cancel/0",body(d),token).statusCode());
            f.cpuBusyCount=2;f.h.time+=3;d=detail(f,c,0);
            f.cpuOffline=true;assertEquals(503,post(f,c,"cpus/cancel/0",body(d),token).statusCode());
            f.cpuOffline=false;d.addProperty("address","other-network");assertEquals(409,post(f,c,"cpus/cancel/0",body(d),token).statusCode());
            assertEquals(0,f.cpuCancelCalls);
        }
    }
    @Test void requiresAuthenticatedSameOriginJsonPostAndCurrentRouteToken() throws Exception {
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()) {
            var d=detail(f,c,0);String token=d.get("token").getAsString(),path="cpus/cancel/0",body=body(d);
            for(String method:new String[]{"GET","HEAD"})assertEquals(405,f.request(c,"/ae/api/"+path,method,true).statusCode());
            assertEquals(401,send(f,c,post(f,path,body,token,null,"application/json",false)).statusCode());
            assertEquals(403,post(f,c,path,body,"stale").statusCode());
            assertEquals(403,send(f,c,post(f,path,body,token,"https://foreign.example","application/json",true)).statusCode());
            assertEquals(400,send(f,c,post(f,path,body,token,null,"text/plain",true)).statusCode());
            for(String invalid:new String[]{"{}","[]","{","{\"job\":true,\"address\":\"a\"}"," ".repeat(4097)})assertEquals(400,post(f,c,path,invalid,token).statusCode());
            assertEquals(0,f.cpuCancelCalls);
        }
    }
    @Test void taskReplacementDuringDetailReadHidesMixedItemsAndCancelButton() throws Exception {
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()) {
            f.cpuReplaceDuringRead=true;var d=detail(f,c,0);
            assertTrue(d.get("job").isJsonNull());assertEquals(0,d.getAsJsonArray("active").size());
            assertTrue(d.get("cancel_error").getAsString().contains("Task changed"));
        }
    }
    @Test void savedConfirmationRequiresNewSessionAndFreshCpuDetails() throws Exception {
        byte[] snapshot;JsonObject d;
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()) {
            d=detail(f,c,0);snapshot=f.h.vm.saveSnapshot(f.h.registry);
        }
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()) {
            f.cpuEpoch=7;f.service.remove(f.latestRoute);
            f.h.vm=Vm.fromSnapshot(snapshot,f.h.globals,f.h.registry);WebAeTest.modules(f.h);
            for(int i=0;i<30;i++)f.tick();
            assertEquals(403,post(f,c,"cpus/cancel/0",body(d),d.get("token").getAsString()).statusCode());
            var fresh=detail(f,c,0);assertNotEquals(d.get("job"),fresh.get("job"));
            json(post(f,c,"cpus/cancel/0",body(fresh),fresh.get("token").getAsString()));assertEquals(1,f.cpuCancelCalls);
        }
    }
}
