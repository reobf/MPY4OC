package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.tk.*;

/** Separate from the shared TkPythonTest; uses its existing real-VM host fixture. */
class TkIconPythonTest {
    @BeforeAll static void registerTile() { TkTestRegistration.ensure(); }

    @Test void pythonIconsAcceptOcMapsConfigureClearAndUseNormalWidgetOperations() throws Exception {
        TkPythonTest.Host host = new TkPythonTest.Host();
        TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start("""
                import tkinter as tk
                root = tk.Tk()
                data = {'name': 'minecraft:stone', 'damage': 2, 'size': 123, 'label': 'Stone', 'maxSize': 64,
                        'hasTag': True, 'tag': b'not transmitted', 'nbt': '{display:{Name:"Stone"}}'}
                item = tk.ItemIcon(root, item=data, width=24, height=24, bg='#123456', bd=1, relief='solid')
                item.pack(side='left')
                fluid = tk.FluidIcon(root, {'fluid': {'name': 'water', 'amount': 123456, 'label': 'Water'}}, width=24, height=24)
                fluid.pack(side='left')
                assert item['item'] == {'name': 'minecraft:stone', 'damage': 2, 'nbt': '{display:{Name:"Stone"}}'}
                assert fluid['fluid'] == {'name': 'water'}
                assert item.cget('width') == 24 and item.cget('height') == 24
                assert item.winfo_class() == 'ItemIcon'
                assert item.winfo_width() == 26 and fluid.winfo_width() == 24
                data['name'] = 'minecraft:dirt'
                assert item['item']['name'] == 'minecraft:stone'
                bound = []
                item.bind('<Button-1>', lambda e: bound.append(e.widget))
                item.event_generate('<Button-1>')
                assert bound == [item]
                item.configure(item=None)
                fluid.configure(fluid=None)
                assert item['item'] is None and fluid['fluid'] is None
                item.config(item={'name': 'minecraft:wool', 'damage': 14})
                fluid['fluid'] = {'name': 'lava'}
                assert item['item']['damage'] == 14 and fluid['fluid']['name'] == 'lava'
                for bad in ('minecraft:stone', {}, {'name': ''}, {'name': 'minecraft:stone', 'damage': -1},
                            {'name': 'minecraft:stone', 'nbt': '{broken'}):
                    try:
                        item.configure(item=bad, width=40)
                        assert False
                    except (ValueError, TypeError):
                        pass
                assert item['width'] == 24 and item['item']['damage'] == 14
                item.pack_forget()
                item.place(x=10, y=12)
                assert item.winfo_manager() == 'place'
                fluid.destroy()
                passed = True
                """);
        host.runUntil("passed");
        assertTrue(tile.scene().display.items.stream().anyMatch(i -> i.icon != null && i.icon.name.equals("minecraft:wool")));
        assertTrue(tile.scene().display.items.stream().noneMatch(i -> i.icon != null && i.icon.fluid));
    }
}
