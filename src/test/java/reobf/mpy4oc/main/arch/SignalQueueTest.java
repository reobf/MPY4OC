package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;

import net.mpy.runtime.PyObj;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.tk.TileTkDisplay;

/**
 * pullSignal leaves Tk events for tkinter while it has windows, and keyboard input for the shell
 * when a background program polls; Tk() takes only a display no other running computer holds,
 * and a display is free again once the computer holding it stops or restarts.
 */
class SignalQueueTest {
    @org.junit.jupiter.api.BeforeAll static void registerTile() {
        reobf.mpy4oc.tk.TkTestRegistration.ensure();
    }

    private static PyObj.Tuple signal(Object... parts) { return new PyObj.Tuple(parts); }

    @Test void tkEventsStayQueuedWhileTkinterHasWindows() {
        PyObj.PyList queue = new PyObj.PyList();
        queue.items.add(signal("tk_event", "screen", "token"));
        queue.items.add(signal("key_down", "kb", 97L, 30L));
        queue.items.add(signal("tk_command", "screen", "token"));
        Object first = MpyArchitecture.pollSignal(queue, true, false);
        assertEquals("key_down", ((PyObj.Tuple) first).items[0], "the shell gets its key");
        assertEquals(2, queue.items.size(), "both Tk signals wait for tkinter");
        assertSame(PyObj.NONE, MpyArchitecture.pollSignal(queue, true, false), "nothing else is queued");
        assertEquals("tk_event", ((PyObj.Tuple) MpyArchitecture.pollSignal(queue, false, false)).items[0],
                "with no windows open they are handed out (and dropped by whoever pulls them), as before");
    }

    @Test void aBackgroundProgramLeavesTheKeyboardToTheShell() {
        PyObj.PyList queue = new PyObj.PyList();
        queue.items.add(signal("key_down", "kb", 97L, 30L));
        queue.items.add(signal("clipboard", "kb", "text"));
        queue.items.add(signal("key_up", "kb", 97L, 30L));
        queue.items.add(signal("modem_message", "modem", "sender", 1L, 0.0, "hi"));
        assertEquals("modem_message", ((PyObj.Tuple) MpyArchitecture.pollSignal(queue, false, true)).items[0]);
        assertSame(PyObj.NONE, MpyArchitecture.pollSignal(queue, false, true));
        assertEquals(3, queue.items.size(), "the keys stay for the shell");
        assertEquals("key_down", ((PyObj.Tuple) MpyArchitecture.pollSignal(queue, false, false)).items[0]);
    }

    @Test void claimFreeSkipsADisplayAnotherRunningComputerHolds() {
        TileTkDisplay tile = new TileTkDisplay();
        boolean[] alive = {true};
        tile.ownerAlive = address -> alive[0];
        assertTrue(opened(tile.application().call("me", "claim_free", "")), "a display nobody holds");
        assertTrue(opened(tile.application().call("me", "claim_free", "")), "its computer opens another program there");
        assertEquals(2, tile.application().tokens().size(), "the two programs share it");
        tile.application().call("other", "claim", "");
        assertEquals(1, tile.application().tokens().size(), "naming it takes it over");
        assertEquals("", tile.application().call("me", "claim_free", ""), "another running computer holds it");
        assertEquals("other", tile.application().owner());
        alive[0] = false;
        assertTrue(opened(tile.application().call("me", "claim_free", "")), "a holder that stopped (or went away) does not count");
        assertEquals("me", tile.application().owner());
    }

    @Test void aProgramTheShellWaitsForLeavesItTheCtrlC() {
        PyObj.PyList queue = new PyObj.PyList();
        queue.items.add(signal("key_down", "kb", 3.0, 46.0));
        queue.items.add(signal("key_down", "kb", 97L, 30L));
        assertEquals(97L, ((PyObj.Tuple) MpyArchitecture.pollSignal(queue, false, false, true)).items[2], "other keys are its own");
        assertSame(PyObj.NONE, MpyArchitecture.pollSignal(queue, false, false, true));
        assertEquals(1, queue.items.size(), "the Ctrl+C stays for the shell");
    }

    @Test void aClaimFromAnEarlierRunOfItsComputerDoesNotCount() {
        // The holder restarted without the display hearing of it (unloaded, or a snapshot that
        // failed to restore): it is running, but its claim belongs to a VM that is gone.
        TileTkDisplay tile = new TileTkDisplay();
        String[] run = {"run-1"};
        tile.ownerAlive = address -> true;
        tile.ownerBoot = address -> address.equals("pc") ? run[0] : "";
        tile.application().call("pc", "claim", "");
        assertEquals("", tile.application().call("other", "claim_free", ""), "held by the run that claimed it");
        net.minecraft.nbt.NBTTagCompound nbt = new net.minecraft.nbt.NBTTagCompound();
        tile.writeToNBT(nbt);
        TileTkDisplay loaded = new TileTkDisplay();
        loaded.readFromNBT(nbt);
        loaded.ownerAlive = tile.ownerAlive;
        loaded.ownerBoot = tile.ownerBoot;
        assertEquals("", loaded.application().call("other", "claim_free", ""), "the run is saved with the claim");
        run[0] = "run-2";
        assertTrue(opened(loaded.application().call("other", "claim_free", "")), "a later run does not hold it");
        assertEquals("other", loaded.application().owner());
    }

    @Test void aDisplayIsFreeOnceItsComputerStopsOrRestarts() {
        TileTkDisplay tile = new TileTkDisplay();
        tile.application().call("pc", "claim", "");
        tile.application().ownerStopped("someone-else");
        assertEquals("pc", tile.application().owner(), "another computer stopping does not matter");
        tile.application().ownerStopped("pc");
        assertEquals("", tile.application().owner());
        assertTrue(tile.application().tokens().isEmpty());
    }

    /** Tk() opened a program: its token and root window. */
    static boolean opened(Object result) {
        return result instanceof Object[] && ((Object[]) result).length == 2 && ((String) ((Object[]) result)[0]).length() > 0;
    }
}
