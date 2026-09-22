package reobf.mpy4oc.main.recipe;

import java.util.Arrays;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.world.World;

/**
 * Shapeless: any EEPROM + the mpyos LiveCD floppy -> a "MPYOS BIOS" EEPROM.
 * The floppy is identified by its oc:lootFactory NBT ("mpy4oc:MPYOS"), so other
 * loot disks do not match; the EEPROM ingredient may be blank or anything else
 * (its old contents are overwritten, which is what flashing means). The floppy
 * is consumed -- it is cheap to get back via the wrench cycling recipe.
 *
 * This exists because registerEEPROM() only creates the item; it does not make
 * it obtainable in survival.
 *
 * Extends {@link ShapelessRecipes} so NEI can draw it (it ignores bare IRecipe
 * implementations); the strict NBT-aware matching is in {@code matches}.
 */
public class MpyBiosRecipe extends ShapelessRecipes {

    /**
     * @param eeprom     any OC EEPROM (display ingredient)
     * @param mpyosDisk  the MPYOS LiveCD floppy from registerFloppy
     * @param output     the MPYOS BIOS EEPROM from registerEEPROM
     */
    public MpyBiosRecipe(ItemStack eeprom, ItemStack mpyosDisk, ItemStack output) {
        super(output, Arrays.asList(eeprom, mpyosDisk));
    }

    @Override
    public boolean matches(InventoryCrafting inv, World world) {
        boolean eeprom = false, disk = false;
        int others = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null) continue;
            if (isEeprom(s) && !eeprom) eeprom = true;
            else if (isMpyosDisk(s) && !disk) disk = true;
            else others++;
        }
        return eeprom && disk && others == 0;
    }

    private static boolean isEeprom(ItemStack s) {
        li.cil.oc.api.detail.ItemInfo info = li.cil.oc.api.Items.get(s);
        return info != null && "eeprom".equals(info.name());
    }

    private static boolean isMpyosDisk(ItemStack s) {
        if (!s.hasTagCompound()) return false;
        return "mpy4oc:MPYOS".equals(s.getTagCompound().getString("oc:lootFactory"));
    }
}
