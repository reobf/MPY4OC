package reobf.mpy4oc.tk;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/** The OC component backing a Tk display; GUI and surface rendering are added separately. */
public final class BlockTkDisplay extends BlockContainer {
    public BlockTkDisplay() {
        super(Material.iron);
        setBlockName("mpy4oc.tk_display");
        setBlockTextureName("minecraft:iron_block");
        setCreativeTab(CreativeTabs.tabRedstone);
        setHardness(2.0F);
        setResistance(5.0F);
        setStepSound(soundTypeMetal);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileTkDisplay();
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        int direction = MathHelper.floor_double(placer.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        int[] faces = {2, 5, 3, 4};
        world.setBlockMetadataWithNotify(x, y, z, faces[direction], 2);
    }

    /**
     * Right-click opens the GUI of the group's master; an empty-hand sneak right-click opens the
     * resolution dialog. Wrench clicks never get here: TkWrenchClient sends them as packets.
     */
    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player,
                                    int side, float hitX, float hitY, float hitZ) {
        TileEntity entity = world.getTileEntity(x, y, z);
        if (!(entity instanceof TileTkDisplay)) return false;
        TileTkDisplay tile = (TileTkDisplay) entity;
        if (player.isSneaking() && player.getHeldItem() != null) return false;
        if (world.isRemote || !(player instanceof EntityPlayerMP)) return true;
        TileTkDisplay master = tile.master();
        if (master == null) return true;
        if (player.isSneaking()) com.cleanroommc.modularui.factory.GuiFactories.tileEntity().open(player, tile);
        else master.openFor((EntityPlayerMP) player);
        return true;
    }

    /** Breaking any block of a group splits the rest back into single displays. */
    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int metadata) {
        if (!world.isRemote) {
            TileEntity entity = world.getTileEntity(x, y, z);
            if (entity instanceof TileTkDisplay) {
                // The block is already gone from the world; keep the facing the group used.
                entity.blockMetadata = metadata;
                try { TkGroups.dissolve(world, (TileTkDisplay) entity); }
                catch (RuntimeException e) { reobf.mpy4oc.main.MyMod.LOG.warn("Tk display group split failed", e); }
                ((TileTkDisplay) entity).deleteImageFiles();
            }
        }
        super.breakBlock(world, x, y, z, block, metadata);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int metadata) {
        int front = metadata >= 2 && metadata <= 5 ? metadata : 2;
        // A placeholder face using vanilla assets until the GUI renderer is ready.
        return side == front ? Blocks.wool.getIcon(0, 15) : super.getIcon(side, metadata);
    }
}
