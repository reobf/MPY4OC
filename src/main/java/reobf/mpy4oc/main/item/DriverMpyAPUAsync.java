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
 * Driver for {@link ItemMpyAPUAsync}. Makes the APU behave as an mpy CPU (fixed
 * architecture, CPU slot) AND, unlike the plain CPU, creates a graphics-card
 * environment so the machine has a built-in GPU. The GPU half reuses
 * OpenComputers' own GraphicsCard at the matching tier.
 */
public class DriverMpyAPUAsync implements Processor, CallBudget {

    @Override
    public boolean worksWith(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemMpyAPUAsync;
    }

    @Override
    public Class<? extends Architecture> architecture(ItemStack stack) {
        return MpyArchitecture.class;
    }

    @Override
    public int supportedComponents(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemMpyAPUAsync
                ? ((ItemMpyAPUAsync) stack.getItem()).supportedComponents(stack)
                : 8;
    }

    @Override
    public double getCallBudget(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemMpyAPUAsync
                ? ((ItemMpyAPUAsync) stack.getItem()).callBudget(stack)
                : 1.0;
    }

    @Override
    public String slot(ItemStack stack) {
        return Slot.CPU;
    }

    @Override
    public int tier(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemMpyAPUAsync
                ? ((ItemMpyAPUAsync) stack.getItem()).tierOf(stack)
                : 0;
    }

    /**
     * The graphics-card half: OpenComputers' own GraphicsCard at the APU's tier.
     * This is what makes the APU a GPU as well as a CPU -- a screen can be driven
     * with no separate graphics card installed.
     */
    @Override
    public ManagedEnvironment createEnvironment(ItemStack stack, EnvironmentHost host) {
        int tier = stack != null && stack.getItem() instanceof ItemMpyAPUAsync
                ? ((ItemMpyAPUAsync) stack.getItem()).tierOf(stack)
                : 0;
        try {
            return new MpyApuAsyncComponent(tier, host);
        } catch (Throwable t) {
            // If OC's APU/GraphicsCard isn't constructable in this pack, degrade to
            // a pure CPU (no built-in GPU) rather than failing to load the item.
            return null;
        }
    }

    @Override
    public NBTTagCompound dataTag(ItemStack stack) {
        if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
        NBTTagCompound nbt = stack.getTagCompound();
        if (!nbt.hasKey("oc:data")) nbt.setTag("oc:data", new NBTTagCompound());
        return nbt.getCompoundTag("oc:data");
    }
}
