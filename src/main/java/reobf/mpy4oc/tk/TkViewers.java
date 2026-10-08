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
        private long budgetTick = Long.MIN_VALUE, lastResync = Long.MIN_VALUE / 2;
        private int inputs, bytes;
        private long lastSeen;

        /**
         * Inputs that edit text, each costing work proportional to the widget's content: a
         * token bucket of {@link #EDIT_BURST}, refilled by {@link #EDITS_PER_TICK} each tick.
         * The burst absorbs keystrokes that arrive bunched after a lag spike.
         */
        public static final int EDITS_PER_TICK = 4, EDIT_BURST = 8;
        private int editTokens = EDIT_BURST;
        private long editTick = Long.MIN_VALUE / 2;
        /** Full rescans requested by the client are honoured at most this often. */
        public static final int RESYNC_INTERVAL = 20;

        private Viewer(UUID id, P owner) {
            this.id = id;
            this.owner = owner;
            this.session = NEXT_SESSION.getAndIncrement();
        }

        public boolean allowInput(long tick, int byteCost) { return allowInput(tick, byteCost, false); }

        /**
         * Packet and byte counts alone do not bound server work: one keystroke into a
         * large Text re-validates and re-lays out its whole content. Text-editing inputs
         * therefore also draw on a small per-tick allowance (human typing, even with key
         * repeat, is about one key per tick).
         */
        public boolean allowInput(long tick, int byteCost, boolean edit) {
            if (byteCost < 0 || byteCost > 8192) return false;
            if (budgetTick != tick) {
                budgetTick = tick;
                inputs = bytes = 0;
            }
            if (edit && tick > editTick) {
                editTokens = (int) Math.min(EDIT_BURST, editTokens + Math.min(EDIT_BURST, tick - editTick) * EDITS_PER_TICK);
                editTick = tick;
            }
            if (inputs >= 32 || bytes + byteCost > 8192 || edit && editTokens <= 0) return false;
            inputs++;
            bytes += byteCost;
            if (edit) editTokens--;
            lastSeen = tick;
            return true;
        }

        public boolean expired(long tick) { return tick - lastSeen > 100; }

        /**
         * Forget what the client holds so the next flush sends a full scene. Rate-limited:
         * each honoured request costs a full scene of outbound traffic.
         */
        public boolean requestResync(long tick) {
            if (tick - lastResync < RESYNC_INTERVAL) return false;
            lastResync = tick;
            lastScene = null;
            return true;
        }

        /** Whether the client lacks the current scene (including a pending full resync). */
        public boolean stale(long sceneRevision) { return lastScene == null || revision != sceneRevision; }

        /**
         * The update that brings this viewer to {@code nextScene}, or null when nothing
         * visible changed. Does not record it as sent: call {@link #sent} once the packet
         * actually went out, so a send dropped by the bandwidth budget is retried as a
         * delta from what the client really has.
         */
        public TkPackets.SceneSync prepare(TkTarget target, long nextRevision, TkScene nextScene) {
            int mask = lastScene == null ? TkPackets.ALL : TkPackets.changes(lastScene, nextScene);
            if (mask == 0) return null; // The client did not receive a revision for a cancelled change.
            TkPackets.SceneSync packet = new TkPackets.SceneSync(target, lastScene == null ? -1 : revision,
                    nextRevision, mask, nextScene);
            if (lastScene != null && (mask & TkPackets.DISPLAY) != 0) packet.displayPatch = TkDisplayPatch.between(lastScene.display, nextScene.display);
            return packet;
        }

        public void sent(long nextRevision, TkScene nextScene) {
            lastScene = nextScene;
            revision = nextRevision;
        }

        public TkPackets.SceneSync update(TkTarget target, long nextRevision, TkScene nextScene) {
            TkPackets.SceneSync packet = prepare(target, nextRevision, nextScene);
            if (packet != null) sent(nextRevision, nextScene);
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
