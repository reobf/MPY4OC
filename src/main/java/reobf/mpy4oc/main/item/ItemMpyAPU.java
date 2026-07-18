package reobf.mpy4oc.main.item;

import java.util.List;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;

/**
 * The MPY APU: a CPU + GPU in one, occupying the CPU slot. Like OpenComputers'
 * APU, it runs the processor architecture (here always mpy) AND provides a built-in
 * graphics card, so a machine can drive a screen without a separate GPU.
 *
 * Three tiers (damage 0/1/2 == tier 1/2/3), same op budgets as the plain CPU:
 *   tier 1 -> 2000, tier 2 -> 8000, tier 3 -> 64000 ops/tick.
 * The GPU half is OpenComputers' own GraphicsCard at the matching tier.
 */
public class ItemMpyAPU extends Item {

    public static final int TIERS = 3;
    private static final int[] SUPPORTED_COMPONENTS = { 8, 12, 16 };
    private static final double[] CALL_BUDGET = { 0.5, 1.0, 1.5 };

    @SideOnly(Side.CLIENT)
    private IIcon[] icons;

    public ItemMpyAPU() {
        super();
        setMaxStackSize(64);
        setHasSubtypes(true);
        setMaxDamage(0);
        setUnlocalizedName("mpy4oc.apu");
        setCreativeTab(CreativeTabs.tabRedstone);
    }

    public int tierOf(ItemStack stack) {
        int d = stack == null ? 0 : stack.getItemDamage();
        if (d < 0) return 0;
        if (d >= TIERS) return TIERS - 1;
        return d;
    }

    public int supportedComponents(ItemStack stack) {
        return SUPPORTED_COMPONENTS[tierOf(stack)];
    }

    public double callBudget(ItemStack stack) {
        return CALL_BUDGET[tierOf(stack)];
    }

    @Override
    public String getUnlocalizedName(ItemStack stack) {
        return super.getUnlocalizedName() + (tierOf(stack) + 1);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    @SideOnly(Side.CLIENT)
    public void getSubItems(Item item, CreativeTabs tab, List list) {
        for (int t = 0; t < TIERS; t++) {
            list.add(new ItemStack(item, 1, t));
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltip, boolean advanced) {
        int t = tierOf(stack);
        int[] ops = { 500, 2000, 16000 };
        tooltip.add("Architecture: mpy  (CPU + GPU)");
        tooltip.add("Tier " + (t + 1) + " \u2014 " + ops[t] + " ops/tick");
        tooltip.add("Built-in graphics card.");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister reg) {
        icons = new IIcon[TIERS];
        for (int t = 0; t < TIERS; t++) {
            icons[t] = reg.registerIcon("mpy4oc:mpy_apu" + (t == 0 ? "" : String.valueOf(t + 1)));
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconFromDamage(int damage) {
        int t = damage < 0 ? 0 : (damage >= TIERS ? TIERS - 1 : damage);
        return icons != null ? icons[t] : super.getIconFromDamage(damage);
    }
}
