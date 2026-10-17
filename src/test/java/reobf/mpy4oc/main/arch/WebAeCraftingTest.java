package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import net.mpy.vm.Vm;
import org.junit.jupiter.api.Test;

/** Real VM + HTTP requests; only the AE controller/recipe/status hardware is mocked. */
class WebAeCraftingTest {
    static JsonObject json(HttpResponse<String> r) {
        assertEquals(200, r.statusCode(), r.body());
        return new JsonParser().parse(r.body()).getAsJsonObject();
    }
    static HttpRequest post(WebAeTest.Fixture f, String path, String body, String token, String origin, String type, boolean auth) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + f.gateway.port() + "/ae/api/" + path))
            .POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", type);
        if (auth) builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((f.service.id+":"+f.service.password).getBytes(StandardCharsets.UTF_8)));
        if (token != null) builder.header("X-AE-Token", token);
        if (origin != null) builder.header("Origin", origin);
        return builder.build();
    }
    static HttpResponse<String> send(WebAeTest.Fixture f, HttpClient client, HttpRequest request) throws Exception {
        var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!future.isDone() && System.nanoTime() < deadline) { f.tick(); Thread.sleep(1); }
        return future.get(1, TimeUnit.SECONDS);
    }
    static HttpResponse<String> post(WebAeTest.Fixture f, HttpClient c, String path, String body, String token) throws Exception {
        return send(f, c, post(f,path,body,token,null,"application/json",true));
    }
    static JsonObject catalog(WebAeTest.Fixture f, HttpClient c) throws Exception { return json(f.request(c,"/ae/api/craftables","GET",true)); }
    static JsonObject prepare(WebAeTest.Fixture f, HttpClient c, JsonObject catalog, int index, String amount) throws Exception {
        String id = catalog.getAsJsonArray("entries").get(index).getAsJsonObject().get("id").getAsString();
        return json(post(f,c,"orders/prepare","{\"recipe\":\""+id+"\",\"amount\":\""+amount+"\"}",catalog.get("token").getAsString())).getAsJsonObject("order");
    }
    static String state(WebAeTest.Fixture f, HttpClient c) throws Exception {
        var orders = json(f.request(c,"/ae/api/orders","GET",true)).getAsJsonArray("orders");
        return orders.get(orders.size()-1).getAsJsonObject().get("state").getAsString();
    }

    @Test void confirmationExactRecipeDuplicateRequestsAndStatusLifecycle() throws Exception {
        try (var f = new WebAeTest.Fixture(); var c = HttpClient.newHttpClient()) {
            var catalog = catalog(f,c);
            assertEquals("Diamond", catalog.getAsJsonArray("entries").get(2).getAsJsonObject().get("label").getAsString(), "zero-stock recipes must be available");
            String token=catalog.get("token").getAsString();
            var draft=prepare(f,c,catalog,4,"64");
            assertEquals(0,f.craftCalls, "prepare is not a crafting side effect");
            assertEquals("draft",draft.get("state").getAsString());
            String id=draft.get("id").getAsString();
            assertEquals(405,f.request(c,"/ae/api/orders/"+id,"GET",true).statusCode());
            var futures = new ArrayList<CompletableFuture<HttpResponse<String>>>();
            for(int i=0;i<4;i++) futures.add(c.sendAsync(post(f,"orders/"+id,"{}",token,null,"application/json",true),HttpResponse.BodyHandlers.ofString()));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(futures.stream().anyMatch(x->!x.isDone())&&System.nanoTime()<deadline){f.tick();Thread.sleep(1);}
            for(var future:futures) assertEquals("computing",json(future.get(1,TimeUnit.SECONDS)).getAsJsonObject("order").get("state").getAsString());
            assertEquals(1,f.craftCalls); assertEquals(4,f.lastRecipe,"same-label NBT variants must keep their exact handles"); assertEquals(64,f.lastAmount);
            f.craftState=1;f.h.time+=3;assertEquals("running",state(f,c),"false,reason tuples must not be truthy failures");
            f.craftState=5;f.h.time+=3;assertEquals("unavailable",state(f,c));
            f.craftState=2;f.h.time+=3;assertEquals("done",state(f,c));
            assertEquals("done",json(post(f,c,"orders/"+id,"{}",token)).getAsJsonObject("order").get("state").getAsString());
            assertEquals(1,f.craftCalls);
        }
    }

    @Test void rejectsUnauthorizedCrossOriginMalformedExpiredAndOverlargeOrders() throws Exception {
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()){
            assertEquals(401,f.request(c,"/ae/api/craftables","GET",false).statusCode());
            var catalog=catalog(f,c);String token=catalog.get("token").getAsString();
            assertEquals(401,send(f,c,post(f,"orders/prepare","{}",token,null,"application/json",false)).statusCode());
            assertEquals(403,post(f,c,"orders/prepare","{}",null).statusCode());
            assertEquals(403,post(f,c,"orders/prepare","{}","old-route").statusCode());
            assertEquals(403,send(f,c,post(f,"orders/prepare","{}",token,"https://foreign.example","application/json",true)).statusCode());
            assertEquals(400,send(f,c,post(f,"orders/prepare","{}",token,null,"text/plain",true)).statusCode());
            for(String amount:List.of("\"0\"","\"-1\"","\"1000001\"","\"1.2\"","\"1e3\"","true","2","[]","null","\"\"")){
                var r=post(f,c,"orders/prepare","{\"recipe\":\"1\",\"amount\":"+amount+"}",token);
                assertEquals(400,r.statusCode(),amount+": "+r.body());
            }
            for(String body:List.of("[]","{","{}"," ".repeat(4097)))assertEquals(400,post(f,c,"orders/prepare",body,token).statusCode());
            var draft=prepare(f,c,catalog,0,"1000000");f.h.time+=61;
            var result=json(post(f,c,"orders/"+draft.get("id").getAsString(),"{}",token));
            assertEquals("expired",result.getAsJsonObject("order").get("state").getAsString());assertEquals(0,f.craftCalls);
            assertEquals(400,post(f,c,"orders/prepare","{\"recipe\":\"1\",\"amount\":\"1\"}",token).statusCode());
        }
    }

    @Test void failureCancellationAmbiguousSubmissionAndBoundedActiveJobs() throws Exception {
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()){
            var cat=catalog(f,c);String token=cat.get("token").getAsString();
            for(int mode:new int[]{3,4}){
                var order=prepare(f,c,cat,2,"1");json(post(f,c,"orders/"+order.get("id").getAsString(),"{}",token));
                f.craftState=mode;f.h.time+=3;assertEquals(mode==3?"failed":"canceled",state(f,c));
            }
            var order=prepare(f,c,cat,0,"1");f.offline=true;
            String path="orders/"+order.get("id").getAsString();
            assertEquals("unknown",json(post(f,c,path,"{}",token)).getAsJsonObject("order").get("state").getAsString());
            int calls=f.craftCalls;f.offline=false;json(post(f,c,path,"{}",token));assertEquals(calls,f.craftCalls);
            f.craftState=0;
            for(int i=0;i<8;i++){var d=prepare(f,c,cat,0,"1");json(post(f,c,"orders/"+d.get("id").getAsString(),"{}",token));}
            var d=prepare(f,c,cat,0,"1");assertEquals(400,post(f,c,"orders/"+d.get("id").getAsString(),"{}",token).statusCode());assertEquals(calls+8,f.craftCalls);
        }
    }

    @Test void snapshotRestoreMarksActiveOrderUnknownAndRejectsOldSession() throws Exception {
        byte[] snapshot;String token,id;
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()){
            var cat=catalog(f,c);token=cat.get("token").getAsString();id=prepare(f,c,cat,0,"10").get("id").getAsString();
            json(post(f,c,"orders/"+id,"{}",token));snapshot=f.h.vm.saveSnapshot(f.h.registry);
        }
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()){
            f.service.remove(f.latestRoute);
            f.h.vm=Vm.fromSnapshot(snapshot,f.h.globals,f.h.registry);WebAeTest.modules(f.h);f.h.time=100;
            for(int i=0;i<30;i++)f.tick();
            assertEquals(0,json(f.request(c,"/ae/api/orders","GET",true)).getAsJsonArray("orders").size());
            assertEquals(403,post(f,c,"orders/"+id,"{}",token).statusCode());
            String newToken=catalog(f,c).get("token").getAsString();
            assertEquals("unknown",json(post(f,c,"orders/"+id,"{}",newToken)).getAsJsonObject("order").get("state").getAsString());
            assertEquals(0,f.craftCalls);
        }
    }

    @Test void boundedCatalogDraftEvictionAndClockRewindNeverReuseOldIds() throws Exception {
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()){
            f.recipeCount=514;var cat=catalog(f,c);String token=cat.get("token").getAsString();
            assertEquals(512,cat.getAsJsonArray("entries").size());assertTrue(cat.get("limited").getAsBoolean());
            String old=prepare(f,c,cat,0,"1").get("id").getAsString();
            for(int i=0;i<64;i++)prepare(f,c,cat,0,"1");
            assertEquals(400,post(f,c,"orders/"+old,"{}",token).statusCode());
            String recent=prepare(f,c,cat,0,"1").get("id").getAsString();f.h.time=-10;
            assertEquals("expired",json(post(f,c,"orders/"+recent,"{}",token)).getAsJsonObject("order").get("state").getAsString());
            assertEquals(0,f.craftCalls);
        }
    }

    @Test void networkChangeAndExplicitOcRejectionDoNotSubmitAnotherRecipe() throws Exception {
        var h=TkPythonTest.shellHost("""
            import aestorage, aecrafting
            class Recipe:
                calls = 0
                def getStack(self):
                    return {'name': 'water', 'label': 'Water', 'amount': 1000}
                def request(self, amount):
                    self.calls += 1
                    assert amount == 250
                    return (None, 'no controller')
            class Ae:
                recipe = Recipe()
                fail = False
                def getCraftables(self):
                    if self.fail:
                        return (None, 'no network')
                    return [self.recipe]
            class Components:
                ae = Ae()
                def proxy(self,address):
                    return self.ae
            c = Components()
            clock = [10]
            inventory = aestorage.Inventory(c, address='a', clock=lambda: clock[0])
            craft = aecrafting.Crafting(inventory)
            craft.bind('route')
            entry = craft.catalog()['entries'][0]
            assert entry['kind'] == 'fluid'
            identity = craft.prepare({'recipe': entry['id'], 'amount': '250'})['order']['id']
            inventory.address = 'b'
            try:
                craft.submit(identity)
                assert False
            except ValueError:
                pass
            assert c.ae.recipe.calls == 0
            inventory.address = 'a'
            result = craft.submit(identity)['order']
            assert result['state'] == 'failed' and result['message'] == 'no controller'
            craft.submit(identity)
            assert c.ae.recipe.calls == 1
            c.ae.fail = True
            clock[0] += 16
            try:
                craft.catalog()
                assert False
            except OSError:
                pass
            try:
                craft.prepare({'recipe': entry['id'], 'amount': '250'})
                assert False
            except ValueError:
                pass
            assert c.ae.recipe.calls == 1
            passed = True
            """);
        WebAeTest.modules(h);h.runUntil("passed");
    }

    @Test void realOcStatusAutosaveKeepsLiveJobAndLostLinkBecomesUnknown() throws Exception {
        // Exercise the installed OC implementation, including its save() side
        // effect, instead of trusting a mock of the three boolean callbacks.
        Class<?> type=Class.forName("li.cil.oc.integration.appeng.NetworkControl$CraftingStatus");
        var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);
        boolean[] done={false};
        var link=(appeng.api.networking.crafting.ICraftingLink)java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{appeng.api.networking.crafting.ICraftingLink.class},(proxy,method,args)->switch(method.getName()){
            case "isDone" -> done[0];case "isCanceled" -> false;case "hashCode" -> System.identityHashCode(proxy);case "equals" -> proxy==args[0];default -> null;
        });
        Object[] status={ctor.newInstance()};
        type.getMethod("setLink",appeng.api.networking.crafting.ICraftingLink.class).invoke(status[0],link);
        var saved=new net.minecraft.nbt.NBTTagCompound();
        var h=TkPythonTest.shellHost("""
            import aestorage, aecrafting
            class Status:
                def isComputing(self):
                    return __native_status('isComputing')
                def hasFailed(self):
                    return __native_status('hasFailed')
                def isCanceled(self):
                    return __native_status('isCanceled')
                def isDone(self):
                    return __native_status('isDone')
            class Recipe:
                def getStack(self):
                    return {'name': 'test:item', 'size': 1}
                def request(self, amount):
                    return Status()
            class Ae:
                def getCraftables(self):
                    return [Recipe()]
            class Components:
                def proxy(self, address):
                    return Ae()
            clock = [0]
            inventory = aestorage.Inventory(Components(), address='a', clock=lambda: clock[0])
            craft = aecrafting.Crafting(inventory)
            recipe = craft.catalog()['entries'][0]['id']
            first = craft.prepare({'recipe': recipe, 'amount': '1'})['order']['id']
            craft.submit(first)
            clock[0] += 3
            assert craft.history()['orders'][-1]['state'] == 'running'
            __native_control('save')
            assert __native_status('hasFailed') == (True, 'no link')
            clock[0] += 3
            assert craft.history()['orders'][-1]['state'] == 'running'
            __native_control('finish')
            clock[0] += 3
            assert craft.history()['orders'][-1]['state'] == 'done'
            __native_control('reset')
            second = craft.prepare({'recipe': recipe, 'amount': '1'})['order']['id']
            craft.submit(second)
            __native_control('save')
            __native_control('restore')
            clock[0] += 3
            assert all(order['id'] != second for order in craft.history()['orders'])
            assert craft.history()['orders'][0]['state'] == 'done'
            assert craft.orders[second]['state'] == 'unknown'
            assert 'handle' not in craft.orders[second]
            passed = True
            """);
        Map<String,net.mpy.vm.HostFunction> calls=new HashMap<>();
        calls.put("__native_status",args->{
            try{
                Object[] result=(Object[])type.getMethod((String)args[0],li.cil.oc.api.machine.Context.class,li.cil.oc.api.machine.Arguments.class).invoke(status[0],null,null);
                return result.length==1?result[0]:new net.mpy.runtime.PyObj.Tuple(result);
            }catch(ReflectiveOperationException e){throw new AssertionError(e);}
        });
        calls.put("__native_control",args->{
            try{
                switch((String)args[0]){
                    case "save" -> ((li.cil.oc.api.machine.Value)status[0]).save(saved);
                    case "finish" -> done[0]=true;
                    case "reset" -> {done[0]=false;status[0]=ctor.newInstance();type.getMethod("setLink",appeng.api.networking.crafting.ICraftingLink.class).invoke(status[0],link);}
                    case "restore" -> {status[0]=ctor.newInstance();((li.cil.oc.api.machine.Value)status[0]).load(saved);}
                    default -> throw new AssertionError(args[0]);
                }
                return net.mpy.runtime.PyObj.NONE;
            }catch(ReflectiveOperationException e){throw new AssertionError(e);}
        });
        calls.forEach((name,fn)->{h.registry.register(name,fn);h.vm.globals.put(name,fn);h.globals.put(name,fn);});
        WebAeTest.modules(h);h.runUntil("passed");
    }
}
