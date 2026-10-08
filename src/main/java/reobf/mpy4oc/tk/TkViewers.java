package reobf.mpy4oc.tk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import reobf.mpy4oc.tk.net.TkPackets;

/** Server-thread session tracking. The owner is an EntityPlayerMP in game. */
public final class TkViewers<P> {
    private static final AtomicLong NEXT_SESSION = new AtomicLong(1);
    private final Map<UUID, Viewer<P>> viewers = new HashMap<>();

    public static final class Viewer<P> {
        public final UUID id;
        public final P owner;
        public final long session;
        public TkScene lastScene;
        public long revision;
        private long budgetTick = Long.MIN_VALUE;
        private int inputs, bytes;
        private long lastSeen;

        private Viewer(UUID id, P owner) {
            this.id = id;
            this.owner = owner;
            this.session = NEXT_SESSION.getAndIncrement();
        }

        public boolean allowInput(long tick, int byteCost) {
            if (byteCost < 0 || byteCost > 8192) return false;
            if (budgetTick != tick) {
                budgetTick = tick;
                inputs = bytes = 0;
            }
            if (inputs >= 32 || bytes + byteCost > 8192) return false;
            inputs++;
            bytes += byteCost;
            lastSeen = tick;
            return true;
        }

        public boolean expired(long tick) { return tick - lastSeen > 100; }

        public TkPackets.SceneSync update(TkTarget target, long nextRevision, TkScene nextScene) {
            int mask = lastScene == null ? TkPackets.ALL : TkPackets.changes(lastScene, nextScene);
            if (mask == 0) return null; // The client did not receive a revision for a cancelled change.
            TkPackets.SceneSync packet = new TkPackets.SceneSync(target, lastScene == null ? -1 : revision,
                    nextRevision, mask, nextScene);
            if (lastScene != null && (mask & TkPackets.DISPLAY) != 0) packet.displayPatch = TkDisplayPatch.between(lastScene.display, nextScene.display);
            lastScene = nextScene;
            revision = nextRevision;
            return packet;
        }
    }

    public Viewer<P> open(UUID id, P owner) {
        Viewer<P> viewer = new Viewer<>(id, owner);
        viewers.put(id, viewer);
        return viewer;
    }

    public Viewer<P> open(UUID id, P owner, long tick) {
        Viewer<P> viewer = open(id, owner);
        viewer.lastSeen = tick;
        return viewer;
    }

    public Viewer<P> find(UUID id, long session) {
        Viewer<P> viewer = viewers.get(id);
        return viewer != null && viewer.session == session ? viewer : null;
    }

    public boolean close(UUID id, long session) {
        if (find(id, session) == null) return false;
        viewers.remove(id);
        return true;
    }

    public Collection<Viewer<P>> all() { return new ArrayList<>(viewers.values()); }
    public boolean isEmpty() { return viewers.isEmpty(); }
    public void clear() { viewers.clear(); }
}
