package reobf.mpy4oc.main.item;

import li.cil.oc.api.network.EnvironmentHost;

/**
 * A minimal CPU-slot component whose only job is to drive the mpy VM once per tick
 * from update() (see {@link MpyTickDriver} for why and how). Returned by the plain
 * CPU's driver; the APU uses {@link MpyApuComponent} instead (which also is a GPU).
 */
public class MpyTicker extends li.cil.oc.api.prefab.ManagedEnvironment {

    private final MpyTickDriver driver;

    public MpyTicker(EnvironmentHost host) {
        this.driver = new MpyTickDriver(host);
    }

    @Override
    public boolean canUpdate() {
        return true;
    }

    @Override
    public void onConnect(li.cil.oc.api.network.Node node) {
        super.onConnect(node);
        driver.registerWithArch();   // ensure runSynchronized stands down early
    }

    @Override
    public void update() {
        super.update();
        driver.tick();
    }
}
