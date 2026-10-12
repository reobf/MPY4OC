package reobf.mpy4oc.tk;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.oredict.OreDictionary;

/** Recognises wrenches from OpenComputers, GregTech and the usual tool APIs; both sides. */
public final class TkWrench {
    private TkWrench() {}

    public static boolean isWrench(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;
        // GregTech's toolbox: like GT machines (GTUtility.isStackInList), judge the tool in its
        // selected slot.
        if ("gregtech.common.items.ItemGTToolbox".equals(stack.getItem().getClass().getName()))
            return isPlainWrench(selectedToolboxTool(stack));
        return isPlainWrench(stack);
    }

    private static boolean isPlainWrench(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return false;
        // GregTech registers its wrenches (meta 16, and charged electric ones), per damage
        // value, in the tool dictionary. Check it first: every GregTech tool -- hammer, saw,
        // screwdriver, also GT++ and Detrav tools -- shares one base class that implements the
        // BuildCraft/AE2/EnderIO wrench interfaces, so an interface test alone would call a
        // GT hammer a wrench.
        for (int id : OreDictionary.getOreIDs(stack))
            if ("craftingToolWrench".equals(OreDictionary.getOreName(id))) return true;
        if (isGregTechTool(stack.getItem().getClass())) return false;
        if (stack.getItem() instanceof li.cil.oc.api.internal.Wrench) return true;
        try {
            // OpenComputers' registry of wrench APIs (BuildCraft, CoFH, AE2, EnderIO, ...).
            return li.cil.oc.integration.util.Wrench$.MODULE$.isWrench(stack);
        } catch (Throwable missing) {
            return false;
        }
    }

    /** GregTech items, including MetaGeneratedTool subclasses in other packages (gtPlusPlus., detrav.). */
    private static boolean isGregTechTool(Class<?> type) {
        if (type.getName().startsWith("gregtech.")) return true;
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            if ("gregtech.api.items.MetaGeneratedTool".equals(c.getName())) return true;
        return false;
    }

    /**
     * The tool in a GregTech toolbox's selected slot: an int "gt5u.toolbox:SelectedSlot" (-1 =
     * none) and a ModularUI item handler "gt5u.toolbox:Contents" = {Items: [{Slot, ...stack}]}.
     */
    private static ItemStack selectedToolboxTool(ItemStack toolbox) {
        NBTTagCompound tag = toolbox.getTagCompound();
        if (tag == null || !tag.hasKey("gt5u.toolbox:SelectedSlot")) return null;
        int slot = tag.getInteger("gt5u.toolbox:SelectedSlot");
        if (slot < 0) return null;
        NBTTagList items = tag.getCompoundTag("gt5u.toolbox:Contents").getTagList("Items", 10);
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound item = items.getCompoundTagAt(i);
            if (item.getInteger("Slot") == slot) return ItemStack.loadItemStackFromNBT(item);
        }
        return null;
    }
}
