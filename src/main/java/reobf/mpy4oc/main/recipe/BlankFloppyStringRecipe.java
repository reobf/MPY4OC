package reobf.mpy4oc.main.recipe;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.world.World;

/**
 * Shapeless: one BLANK floppy + one piece of string -> the MPYOS LiveCD floppy.
 *
 * This cannot be a vanilla shapeless recipe: in OC a loot disk (OpenOS, ...) and
 * a written floppy are the SAME item as a blank one, distinguished only by NBT,
 * and vanilla matching ignores NBT -- the recipe would silently eat a player's
 * OpenOS disk or a floppy with data on it. So we match strictly: the floppy must
 * carry no NBT at all (fresh from crafting), everything else is refused.
 */
public class BlankFloppyStringRecipe implements IRecipe {
    private final ItemStack output;

    public BlankFloppyStringRecipe(ItemStack output) {
        this.output = output;
    }

    @Override
    public boolean matches(InventoryCrafting inv, World world) {
        boolean floppy = false, string = false;
        int others = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null) continue;
            if (!floppy && isBlankFloppy(s)) floppy = true;
            else if (!string && s.getItem() == net.minecraft.init.Items.string) string = true;
            else others++;
        }
        return floppy && string && others == 0;
    }

    private static boolean isBlankFloppy(ItemStack s) {
        if (s.hasTagCompound()) return false;   // loot disk or written floppy: refuse
        li.cil.oc.api.detail.ItemInfo info = li.cil.oc.api.Items.get(s);
        return info != null && "floppy".equals(info.name());
    }

    @Override
    public ItemStack getCraftingResult(InventoryCrafting inv) {
        return output.copy();
    }

    @Override
    public int getRecipeSize() {
        return 2;
    }

    @Override
    public ItemStack getRecipeOutput() {
        return output;
    }
}
