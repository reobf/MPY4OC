package net.mpy.vm;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * A bidirectional map between stable string ids and {@link HostFunction}s, used to
 * (de)serialize host functions in a snapshot.
 *
 * At save time a live host function is written as its id (looked up by identity);
 * at restore time the id is resolved back to a host function. The embedder assigns
 * the ids and supplies a registry with the same ids before restoring — the actual
 * {@code HostFunction} instances may differ between save and restore (indeed they
 * must for VM-bound ones like {@link Vm#yieldHost()}, which should be re-created
 * from the new VM under the same id).
 */
public final class HostRegistry {

    private final Map<String, HostFunction> byId = new HashMap<>();
    private final Map<HostFunction, String> idOf = new IdentityHashMap<>();
    private Map<String, ?> liveGlobals;   // lazy backing (fromGlobals): entries
                                          // added to the map AFTER construction
                                          // remain visible to both lookups

    /** Register {@code fn} under {@code id} (both directions). Returns this for chaining. */
    public HostRegistry register(String id, HostFunction fn) {
        if (id == null || fn == null) throw new IllegalArgumentException("id and fn must be non-null");
        byId.put(id, fn);
        idOf.put(fn, id);
        return this;
    }

    /** The id previously registered for {@code fn}, or null if unregistered. */
    public String idFor(HostFunction fn) {
        String id = idOf.get(fn);
        if (id == null && liveGlobals != null) {
            for (Map.Entry<String, ?> e : liveGlobals.entrySet()) {
                if (e.getValue() == fn) return e.getKey();
            }
        }
        return id;
    }

    /** The host function registered under {@code id}, or null if none. */
    public HostFunction functionFor(String id) {
        HostFunction fn = byId.get(id);
        if (fn == null && liveGlobals != null) {
            Object v = liveGlobals.get(id);
            if (v instanceof HostFunction) return (HostFunction) v;
        }
        if (fn == null && resolver != null) {
            // Last resort: let the embedder synthesise a function for an id it can
            // reconstruct deterministically (e.g. component proxy methods, whose id
            // encodes address + method and so need not have been pre-registered --
            // important when a snapshot is restored before the component network is
            // back online, as happens during chunk load). A generated function is
            // cached so identity is stable within this registry.
            fn = resolver.resolve(id);
            if (fn != null) register(id, fn);
        }
        return fn;
    }

    /** Synthesises a HostFunction for an id not otherwise registered. */
    public interface Resolver {
        HostFunction resolve(String id);
    }

    private Resolver resolver;

    /** Install a fallback resolver for unknown ids (see {@link #functionFor}). */
    public HostRegistry withResolver(Resolver r) {
        this.resolver = r;
        return this;
    }

    /** Build a registry backed LIVE by a globals map: every host function is
     *  addressable under its global name, including entries added to the map
     *  after this call (e.g. the batteries the Vm constructor fills in). */
    public static HostRegistry fromGlobals(Map<String, ?> globals) {
        HostRegistry r = new HostRegistry();
        r.liveGlobals = globals;
        return r;
    }

    /** Attach a globals map as a by-name fallback for unregistered functions
     *  (explicit register() ids always win). Used by the Vm so auto-installed
     *  primitives resolve even under a manually-built registry. */
    void fallbackTo(Map<String, ?> globals) {
        if (liveGlobals == null) liveGlobals = globals;
    }
}
