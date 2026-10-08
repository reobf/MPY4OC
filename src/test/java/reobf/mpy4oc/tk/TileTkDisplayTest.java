package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import li.cil.oc.api.API;
import li.cil.oc.api.detail.Builder;
import li.cil.oc.api.detail.NetworkAPI;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.Visibility;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs against MC's real NBT and OC's API, with only the network factory substituted. */
class TileTkDisplayTest {
    private NetworkAPI previousNetwork;
    private String registeredComponent;
    private Visibility componentVisibility;
    private final AtomicInteger removals = new AtomicInteger();
    private final AtomicInteger nodeCount = new AtomicInteger();

    @BeforeAll
    static void registerTile() {
        TkTestRegistration.ensure();
    }

    @BeforeEach
    void installNetworkFactory() {
        previousNetwork = API.network;
        API.network = (NetworkAPI) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {NetworkAPI.class}, (proxy, method, args) -> {
                    if (method.getName().equals("newNode")) return builder((Environment) args[0]);
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @AfterEach
    void restoreNetworkFactory() {
        API.network = previousNetwork;
    }

    @Test
    void registersNetworkVisibleDisplayAndKeepsAddressAndSettingsInNbt() {
        TileTkDisplay original = new TileTkDisplay();
        assertEquals("tk_display", registeredComponent);
        assertEquals(Visibility.Network, componentVisibility);
        assertArrayEquals(new Object[] {320, 240}, original.getResolution(null, null));
        original.setResolution(null, arguments(640, 480));
        original.setSurfaceVisible(null, arguments(false));
        original.setBackground(null, arguments(0x123456));
        original.setTitle(null, arguments("共享画布"));
        NBTTagCompound saved = new NBTTagCompound();
        original.writeToNBT(saved);

        TileEntity loaded = TileEntity.createAndLoadEntity(saved);
        assertInstanceOf(TileTkDisplay.class, loaded);
        TileTkDisplay restored = (TileTkDisplay) loaded;
        assertEquals(original.node().address(), restored.node().address());
        assertArrayEquals(new Object[] {640, 480}, restored.getResolution(null, null));
        assertArrayEquals(new Object[] {false}, restored.isSurfaceVisible(null, null));
        assertArrayEquals(new Object[] {0x123456}, restored.getBackground(null, null));
        assertArrayEquals(new Object[] {"共享画布"}, restored.getTitle(null, null));
        assertArrayEquals(new Node[] {restored.node()}, restored.onAnalyze(null, 0, 0, 0, 0));
    }

    @Test
    void ownNetworkConnectionRequestsSavingNewAddress() {
        AtomicInteger saves = new AtomicInteger();
        TileTkDisplay tile = new TileTkDisplay() {
            @Override
            public void markDirty() {
                saves.incrementAndGet();
            }
        };
        assertEquals(0, saves.get());
        tile.onConnect(tile.node());
        assertEquals(1, saves.get(), "an address assigned while loading a clean chunk must be saved");
        tile.onConnect(testNode(tile));
        assertEquals(1, saves.get(), "a neighbor joining does not change this display's address");
    }

    @Test
    void invalidResolutionDoesNotPartiallyChangeState() {
        TileTkDisplay tile = new TileTkDisplay();
        assertThrows(IllegalArgumentException.class, () -> tile.setResolution(null, arguments(640, 0)));
        assertThrows(IllegalArgumentException.class, () -> tile.setResolution(null, arguments(1025, 480)));
        assertArrayEquals(new Object[] {320, 240}, tile.getResolution(null, null));
    }

    @Test
    void missingOrInvalidSavedSizeUsesDefaultsAndUnloadRemovesNode() {
        TileTkDisplay tile = new TileTkDisplay();
        NBTTagCompound state = new NBTTagCompound();
        state.setInteger("width", Integer.MAX_VALUE);
        state.setInteger("height", 480);
        NBTTagCompound saved = new NBTTagCompound();
        saved.setTag("mpy4oc:display", state);
        tile.readFromNBT(saved);
        assertArrayEquals(new Object[] {320, 240}, tile.getResolution(null, null));
        assertArrayEquals(new Object[] {true}, tile.isSurfaceVisible(null, null));
        tile.onChunkUnload();
        assertEquals(1, removals.get());
        tile.invalidate();
        assertEquals(2, removals.get());
    }

    @Test
    void absentNetworkApiHasNoNodeAndLifecycleCallsRemainSafe() {
        API.network = null;
        TileTkDisplay tile = new TileTkDisplay();
        assertNull(tile.node());
        assertEquals(0, tile.onAnalyze(null, 0, 0, 0, 0).length);
        tile.updateEntity();
        tile.onChunkUnload();
        tile.invalidate();
    }

    private Object builder(Environment host) {
        Component node = testNode(host);
        Object componentBuilder = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Builder.ComponentBuilder.class}, (proxy, method, args) -> {
                    if (method.getName().equals("create")) return node;
                    throw new UnsupportedOperationException(method.getName());
                });
        return Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Builder.NodeBuilder.class}, (proxy, method, args) -> {
                    if (method.getName().equals("withComponent")) {
                        registeredComponent = (String) args[0];
                        componentVisibility = (Visibility) args[1];
                        return componentBuilder;
                    }
                    if (method.getName().equals("create")) return node;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private Component testNode(Environment host) {
        String[] address = {"test-node-" + nodeCount.incrementAndGet()};
        return (Component) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Component.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "host": return host;
                        case "address": return address[0];
                        case "save": ((NBTTagCompound) args[0]).setString("address", address[0]); return null;
                        case "load":
                            NBTTagCompound nbt = (NBTTagCompound) args[0];
                            if (nbt.hasKey("address")) address[0] = nbt.getString("address");
                            return null;
                        case "remove": removals.incrementAndGet(); return null;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return address[0];
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private Arguments arguments(Object... values) {
        return (Arguments) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Arguments.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("checkInteger") || method.getName().equals("checkBoolean")
                            || method.getName().equals("checkString")) {
                        return values[(Integer) args[0]];
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
