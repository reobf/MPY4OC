package reobf.mpy4oc.main.item;

import li.cil.oc.api.network.EnvironmentHost;

/**
 * The graphics half of an ASYNC mpy APU.
 *
 * <p>Unlike {@link MpyApuComponent} (the synchronous APU), this component does
 * NOT drive the VM from its per-tick {@code update()}. The async architecture
 * steps the VM on the OC computer thread via {@code runThreaded()} instead, so
 * registering a ticker here would double-drive the VM on the main thread. We
 * therefore only inherit OC's built-in graphics card (VRAM, gpu callbacks) and
 * leave VM execution entirely to the async arch.
 *
 * <p>The upside of an async APU: the graphics callbacks (gpu.set/copy/fill, ...)
 * are {@code direct}, so they run on the computer thread with no synchronized-call
 * round trip -- a graphics-heavy async script stays fast while still keeping the
 * main server thread light.
 */
public class MpyApuAsyncComponent extends li.cil.oc.server.component.APU {

    public MpyApuAsyncComponent(int tier, EnvironmentHost host) {
        super(tier);
        // No MpyTickDriver: the async arch owns VM execution via runThreaded.
    }

    @Override
    public boolean canUpdate() {
        // OC's GraphicsCard doesn't tick and we don't drive the VM here, so there
        // is nothing to do per tick. (The async arch runs on the computer thread.)
        return false;
    }
}
