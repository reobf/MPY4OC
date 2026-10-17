package reobf.mpy4oc.main.item;

import java.util.*;
import li.cil.oc.api.Network;
import li.cil.oc.api.driver.item.*;
import li.cil.oc.api.machine.*;
import li.cil.oc.api.network.*;
import net.minecraft.item.*;
import net.minecraft.nbt.NBTTagCompound;
import reobf.mpy4oc.main.Config;
import reobf.mpy4oc.web.*;

/** Public HTTP gateway capability; callbacks remain usable from OpenOS/Lua too. */
public class ItemWebCard extends Item implements HostAware {
    public ItemWebCard() { setCreativeTab(net.minecraft.creativetab.CreativeTabs.tabRedstone); }
    private static WebGateway gateway;
    public static synchronized WebGateway gateway() throws Exception {
        if (gateway == null) {
            if (Config.webPort < 0) throw new IllegalStateException("Web server is disabled in the server configuration");
            gateway = new WebGateway(Config.webBind, Config.webPort,
                Config.webKeyStore.isEmpty() ? null : WebGateway.ssl(Config.webKeyStore, Config.webKeyStorePassword), Config.webUwsgi);
        }
        return gateway;
    }
    public static synchronized void shutdown() {
        WebServices.shutdown();
        if (gateway != null) { gateway.close(); gateway = null; }
    }
    public static class APIEnv implements ManagedEnvironment {
        private final Node node = Network.newNode(this, Visibility.Network).withComponent("webserver").create();
        private final EnvironmentHost host;
        private String user = "", password = "", error = "", owner = "";
        private boolean restore;
        private WebServices.Service service;
        public APIEnv(EnvironmentHost host) { this.host = host; }
        public Node node() { return node; }
        public boolean canUpdate() { return true; }
        public void update() {
            if (restore && node.network() != null) {
                restore = false;
                try { activate(); } catch (Exception e) { error = e.getMessage(); }
            }
            if (service != null) service.expire();
        }
        private void activate() throws Exception {
            gateway();
            service = WebServices.register(user, password); error = "";
        }
        private void deactivate() { if (service != null) service.close(); service = null; }
        private WebServices.Service require() {
            // A restored Python thread may run before this component's first update tick.
            if (service == null && restore && node.network() != null) {
                restore = false;
                try { activate(); } catch (Exception e) { error = e.getMessage(); }
            }
            if (service == null || !service.active) throw new IllegalStateException(error.isEmpty() ? "Web service is stopped" : error);
            return service;
        }
        public void onConnect(Node other) {}
        public void onDisconnect(Node other) {
            if (other == node) { restore = service != null && service.active; deactivate(); }
            else if (owner.equals(other.address())) { restore = false; deactivate(); }
        }
        public void onMessage(Message message) {
            if ("computer.stopped".equals(message.name()) && (owner.equals(message.source().address()) || message.source().host() instanceof Machine
                    && ((Machine)message.source().host()).host() == host)) { restore = false; deactivate(); }
        }
        public void load(NBTTagCompound nbt) {
            node.load(nbt.getCompoundTag("node"));
            user = nbt.getString("user"); password = nbt.getString("password"); owner = nbt.getString("owner"); restore = nbt.getBoolean("active");
        }
        public void save(NBTTagCompound nbt) {
            NBTTagCompound value = new NBTTagCompound(); node.save(value); nbt.setTag("node", value);
            nbt.setString("user", user); nbt.setString("password", password);
            nbt.setString("owner", owner);
            nbt.setBoolean("active", restore || service != null && service.active);
        }
        @Callback(doc="start([id:string, password:string]):boolean -- Register an ID; omit arguments to reuse saved credentials.")
        public Object[] start(Context context, Arguments args) throws Exception {
            String nextUser = args.count() == 0 ? user : args.checkString(0);
            String nextPassword = args.count() == 0 ? password : args.checkString(1);
            if (service != null && service.active) {
                if (nextUser.equals(user) && nextPassword.equals(password)) return new Object[]{true};
                throw new IllegalStateException("stop the service before changing credentials");
            }
            user = nextUser; password = nextPassword; owner = context.node().address(); restore = false;
            try { activate(); } catch (Exception e) { error = e.getMessage(); throw e; }
            return new Object[]{true};
        }
        @Callback(doc="stop():boolean -- Release the ID and all requests, keeping saved credentials.")
        public Object[] stop(Context context, Arguments args) { restore = false; deactivate(); return new Object[]{true}; }
        @Callback(doc="getCredentials():string,string -- Return the saved ID and plaintext password.")
        public Object[] getCredentials(Context context, Arguments args) { return new Object[]{user, password}; }
        @Callback(doc="status():table -- Running state, registration error, port and TLS state.")
        public Object[] status(Context context, Arguments args) {
            Map<String,Object> data = new LinkedHashMap<>(); data.put("id",user); data.put("active",service != null && service.active); data.put("error",error);
            data.put("port",gateway == null ? -1 : gateway.port()); data.put("tls",gateway != null && gateway.tls); return new Object[]{data};
        }
        @Callback(doc="open(path:string):string -- Register a URL prefix and return a route token.")
        public Object[] open(Context context, Arguments args) { return new Object[]{require().open(args.checkString(0), args.optBoolean(1,false))}; }
        @Callback(doc="wsPoll(route:string):table -- Accept the next authenticated WebSocket connection.")
        public Object[] wsPoll(Context context, Arguments args) { Map<String,Object> r=require().pollSocket(args.checkString(0));return r==null?new Object[]{}:new Object[]{r}; }
        @Callback(doc="wsReceive(connection:string):table -- Next message, close notification, or nil.")
        public Object[] wsReceive(Context context, Arguments args) { Map<String,Object> r=require().receiveSocket(args.checkString(0));return r==null?new Object[]{}:new Object[]{r}; }
        @Callback(doc="wsSend(connection:string,data:string[,binary:boolean]) -- Send one message, up to 64 KiB.")
        public Object[] wsSend(Context context, Arguments args) { require().sendSocket(args.checkString(0),args.checkByteArray(1),args.optBoolean(2,false));return new Object[]{}; }
        @Callback(doc="wsClose(connection:string) -- Close a WebSocket connection.")
        public Object[] wsClose(Context context, Arguments args) { if(service!=null)service.closeSocket(args.checkString(0));return new Object[]{}; }
        @Callback(doc="hasRoute(route:string):boolean -- Check a route token, including after loading a save.")
        public Object[] hasRoute(Context context, Arguments args) { return new Object[]{service != null && service.hasRoute(args.checkString(0))}; }
        @Callback(doc="close(route:string) -- Release a URL prefix and cancel its requests.")
        public Object[] close(Context context, Arguments args) { if (service != null) service.remove(args.checkString(0)); return new Object[]{}; }
        @Callback(doc="poll(route:string):table -- Get the next request or nil; never blocks a Java thread.")
        public Object[] poll(Context context, Arguments args) {
            WebServices.Request r = require().poll(args.checkString(0)); return r == null ? new Object[]{} : new Object[]{r.data()};
        }
        @Callback(doc="pending(request:string):boolean -- Whether the client still awaits a response.")
        public Object[] pending(Context context, Arguments args) { return new Object[]{service != null && service.pending(args.checkString(0))}; }
        @Callback(doc="respond(request:string,status:number,headers:string,body:string):boolean -- Complete an HTTP request; headers are CRLF-separated name: value lines.")
        public Object[] respond(Context context, Arguments args) {
            List<String[]> headers = new ArrayList<>();
            String raw = args.checkString(2);
            if (raw.length() > 16384) throw new IllegalArgumentException("response headers too large");
            if (!raw.isEmpty()) for (String line : raw.split("\r\n", -1)) {
                int colon = line.indexOf(':'); if (colon <= 0) throw new IllegalArgumentException("invalid response header");
                headers.add(new String[]{line.substring(0,colon),line.substring(colon+1).trim()});
            }
            WebServices.Response response = new WebServices.Response(args.checkInteger(1), headers, args.checkByteArray(3));
            return new Object[]{service != null && service.finish(args.checkString(0), response)};
        }
    }
    public boolean worksWith(ItemStack stack) { return stack != null && stack.getItem() instanceof ItemWebCard; }
    public boolean worksWith(ItemStack stack, Class<? extends EnvironmentHost> host) { return worksWith(stack); }
    public ManagedEnvironment createEnvironment(ItemStack stack, EnvironmentHost host) { return new APIEnv(host); }
    public String slot(ItemStack stack) { return Slot.Card; }
    public int tier(ItemStack stack) { return 0; }
    public NBTTagCompound dataTag(ItemStack stack) { return null; }
}
