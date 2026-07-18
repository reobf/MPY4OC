package reobf.mpy4oc.main;

import java.util.Set;

import cpw.mods.fml.client.IModGuiFactory;
import cpw.mods.fml.client.config.GuiConfig;
import cpw.mods.fml.client.config.IConfigElement;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;

/**
 * Makes the mod's settings editable from the game: Mods -> mpy4oc -> Config.
 *
 * <p>This is what "switchable in-game" needs -- a plain config file would require
 * quitting to edit. Forge builds the screen from the Configuration's own metadata, so
 * the compiler-backend option shows up as a picker of its two valid values with the
 * comment as help text, and nothing here has to be kept in sync by hand.
 *
 * <p>Client-only: Forge instantiates this class solely for the config screen, so the
 * dedicated server never loads it (and never touches the client GUI classes).
 */
public class ModGuiFactory implements IModGuiFactory {

    @Override
    public void initialize(net.minecraft.client.Minecraft minecraftInstance) {
        // nothing to set up
    }

    @Override
    public Class<? extends GuiScreen> mainConfigGuiClass() {
        return ModConfigGui.class;
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return null;
    }

    @Override
    public RuntimeOptionGuiHandler getHandlerFor(RuntimeOptionCategoryElement element) {
        return null;
    }

    public static class ModConfigGui extends GuiConfig {
        public ModConfigGui(GuiScreen parent) {
            super(parent,
                    new ConfigElement(Config.configuration.getCategory(Configuration.CATEGORY_GENERAL))
                            .getChildElements(),
                    MyMod.MODID, false, false,
                    "mpy4oc settings");
        }
    }

    /** Kept for readability of the generic above. */
    @SuppressWarnings("unused")
    private static final Class<IConfigElement> ELEMENT_TYPE = IConfigElement.class;
}
