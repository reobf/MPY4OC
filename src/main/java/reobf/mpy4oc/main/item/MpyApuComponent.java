package reobf.mpy4oc.main.item;

import li.cil.oc.api.network.EnvironmentHost;

/**
 * The APU's environment: OpenComputers' own APU (a GraphicsCard) that ALSO drives
 * the mpy VM every tick. Extends OC's server APU so the graphics half is unchanged,
 * and overrides update() to additionally run the VM (see {@link MpyTickDriver}).
 */
public class MpyApuComponent extends li.cil.oc.server.component.APU {

    private final MpyTickDriver driver;

    public MpyApuComponent(int tier, EnvironmentHost host) {
        super(tier);
        this.driver = new MpyTickDriver(host);
    }

    @Override
    public boolean canUpdate() {
        return true;   // OC's GraphicsCard doesn't tick; we need to, to drive the VM
    }

    @Override
    public void onConnect(li.cil.oc.api.network.Node node) {
        super.onConnect(node);
        driver.registerWithArch();
    }

    @Override
    public void update() {
        super.update();   // graphics-card housekeeping (VRAM etc.)
        driver.tick();    // then advance the VM
    }
}
