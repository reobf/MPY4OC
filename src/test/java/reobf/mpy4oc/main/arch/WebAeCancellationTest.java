package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;
import static reobf.mpy4oc.main.arch.WebAeCraftingTest.*;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class WebAeCancellationTest {
    @Test void cancelsOnlySelectedOrderAndRetriesAreIdempotent() throws Exception {
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()) {
            var cat=catalog(f,c);String token=cat.get("token").getAsString();
            String first=prepare(f,c,cat,0,"4").get("id").getAsString();
            String second=prepare(f,c,cat,0,"4").get("id").getAsString();
            json(post(f,c,"orders/"+first,"{}",token));
            json(post(f,c,"orders/"+second,"{}",token));
            f.craftState=1;
            var result=json(post(f,c,"orders/"+first+"/cancel","{}",token)).getAsJsonObject("order");
            assertEquals("canceled",result.get("state").getAsString());
            assertFalse(result.get("can_cancel").getAsBoolean());
            json(post(f,c,"orders/"+first+"/cancel","{}",token));
            assertEquals(1,f.cancelCalls);
            var orders=json(f.request(c,"/ae/api/orders","GET",true)).getAsJsonArray("orders");
            assertEquals("running",orders.get(1).getAsJsonObject().get("state").getAsString());
            assertTrue(orders.get(1).getAsJsonObject().get("can_cancel").getAsBoolean());
            assertEquals(2,f.craftCalls);
        }
    }

    @Test void calculatingOrderCancelsInBackgroundOnceLinkArrives() throws Exception {
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()) {
            var cat=catalog(f,c);String token=cat.get("token").getAsString();
            String id=prepare(f,c,cat,0,"4").get("id").getAsString();
            json(post(f,c,"orders/"+id,"{}",token));
            var result=json(post(f,c,"orders/"+id+"/cancel","{}",token)).getAsJsonObject("order");
            assertEquals("canceling",result.get("state").getAsString());
            assertFalse(result.get("can_cancel").getAsBoolean());
            assertEquals(0,f.cancelCalls);
            f.craftState=1;f.h.time+=6;
            for(int i=0;i<40;i++) f.tick();
            assertEquals(1,f.cancelCalls,"the browser need not keep polling");
            assertEquals("canceled",state(f,c));
        }
    }

    @Test void cancellationRequiresAuthenticatedSameOriginPostAndFreshToken() throws Exception {
        try(var f=new WebAeTest.Fixture();var c=HttpClient.newHttpClient()) {
            var cat=catalog(f,c);String token=cat.get("token").getAsString();
            String id=prepare(f,c,cat,0,"4").get("id").getAsString();
            json(post(f,c,"orders/"+id,"{}",token));f.craftState=1;
            String path="orders/"+id+"/cancel";
            assertEquals(405,f.request(c,"/ae/api/"+path,"GET",true).statusCode());
            assertEquals(401,send(f,c,post(f,path,"{}",token,null,"application/json",false)).statusCode());
            assertEquals(403,post(f,c,path,"{}","stale").statusCode());
            assertEquals(403,send(f,c,post(f,path,"{}",token,"https://foreign.example","application/json",true)).statusCode());
            assertEquals(400,post(f,c,"orders/missing/cancel","{}",token).statusCode());
            assertEquals(0,f.cancelCalls);
            f.craftState=2;
            assertEquals("done",json(post(f,c,path,"{}",token)).getAsJsonObject("order").get("state").getAsString());
            assertEquals(0,f.cancelCalls,"a completed order must not cancel a later CPU job");
        }
    }
}
