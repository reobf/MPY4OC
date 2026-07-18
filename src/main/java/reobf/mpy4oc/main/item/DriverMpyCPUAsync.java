package reobf.mpy4oc.main.item;

import li.cil.oc.api.driver.item.CallBudget;
import li.cil.oc.api.driver.item.Processor;
import li.cil.oc.api.driver.item.Slot;
import li.cil.oc.api.machine.Architecture;
import li.cil.oc.api.network.EnvironmentHost;
import li.cil.oc.api.network.ManagedEnvironment;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import reobf.mpy4oc.main.arch.MpyArchitecture;

/**
 * OpenComputers driver for the async MPY CPU.
 *
 * This is what actually makes the item a CPU as far as OC is concerned: it goes in
 * the CPU slot, reports how many components it supports, and — the whole point —
 * reports {@link MpyArchitecture} as its architecture.
 *
 * Note we implement {@link Processor} and NOT {@code MutableProcessor}: the
 * architecture is fixed, so there is nothing for a player (or OC) to switch. This
 * is exactly why this item sidesteps the sneak-right-click / NBT dance.
 */
public class DriverMpyCPUAsync implements Processor, CallBudget {

    @Override
    public boolean worksWith(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemMpyCPUAsync;
    }

    /** The architecture this CPU runs. Fixed — no NBT, no toggling. */
    @Override
    public Class<? extends Architecture> architecture(ItemStack stack) {
        return MpyArchitecture.class;
    }

    @Override
    public int supportedComponents(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemMpyCPUAsync
                ? ((ItemMpyCPUAsync) stack.getItem()).supportedComponents(stack)
                : 8;
    }

    @Override
    public double getCallBudget(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemMpyCPUAsync
                ? ((ItemMpyCPUAsync) stack.getItem()).callBudget(stack)
                : 1.0;
    }

    @Override
    public String slot(ItemStack stack) {
        return Slot.CPU;
    }

    @Override
    public int tier(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemMpyCPUAsync
                ? ((ItemMpyCPUAsync) stack.getItem()).tierOf(stack)
                : 0;
    }

    /**
     * The CPU's environment is a tiny ticker component whose update() drives the VM
     * every tick on the main thread -- guaranteeing steady execution instead of
     * relying on the thread-pool-scheduled runThreaded/runSynchronized path, which
     * can skip ticks under load. See {@link MpyTicker} / {@link MpyTickDriver}.
     */
    /**
     * The ASYNC CPU deliberately creates NO ticker environment: it runs the VM on
     * OC's computer thread via runThreaded (with a bounded budget), handing
     * non-direct component calls back to the main thread through SynchronizedCall.
     * A ticker would defeat the purpose by driving everything on the main thread.
     */
    @Override
    public ManagedEnvironment createEnvironment(ItemStack stack, EnvironmentHost host) {
        return null;
    }

    @Override
    public NBTTagCompound dataTag(ItemStack stack) {
        if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
        NBTTagCompound nbt = stack.getTagCompound();
        if (!nbt.hasKey("oc:data")) nbt.setTag("oc:data", new NBTTagCompound());
        return nbt.getCompoundTag("oc:data");
    }
}
