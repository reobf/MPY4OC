package reobf.mpy4oc.tk;

import reobf.mpy4oc.tk.net.TkPackets;

/** Pure scene reducer; stale sessions and out-of-order deltas cannot overwrite the GUI. */
public final class TkClientState {
    public final TkTarget target;
    private TkScene scene;
    private long revision;
    private int copySerial, pendingCopy;

    public TkClientState(TkPackets.OpenGui open) {
        this.target = open.target;
        this.scene = open.scene;
        this.revision = open.revision;
    }

    /** False requests a full resync; packets for another session are simply ignored. */
    public boolean accept(TkPackets.SceneSync update) {
        if (!target.equals(update.target) || update.revision < revision) return true;
        if (!update.isFull() && update.revision == revision) return true;
        if (!update.isFull() && update.baseRevision != revision) return false;
        try { scene = update.apply(scene); }
        catch (IllegalArgumentException invalidPatch) { return false; }
        revision = update.revision;
        return true;
    }

    public TkScene scene() { return scene; }
    public long revision() { return revision; }

    public int requestCopy() { if (++copySerial <= 0) copySerial = 1; return pendingCopy = copySerial; }
    /** A delayed or unsolicited reply must never change the player's clipboard. */
    public String acceptCopy(TkPackets.CopyResult reply) {
        if (!target.equals(reply.target) || reply.request != pendingCopy || pendingCopy == 0) return null;
        pendingCopy = 0; return reply.text;
    }
}
