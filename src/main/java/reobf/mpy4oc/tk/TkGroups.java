package reobf.mpy4oc.tk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;
import reobf.mpy4oc.tk.net.TkPackets;

/**
 * Merging and splitting multiblock displays. {@link #plan} is pure and runs on both sides
 * (the client draws the wrench overlay from it); {@link #merge}, {@link #split} and
 * {@link #dissolve} change the world and run on the server.
 */
public final class TkGroups {
    private TkGroups() {}

    /** Why a merge cannot happen; the lang key suffix of the player message. */
    public enum Problem { NO_NEIGHBOUR, FACING, UNLOADED, SAME_GROUP, SHAPE, TOO_LARGE }

    /** The outcome of aiming a wrench at one edge of a display. */
    public static final class Plan {
        public final TileTkDisplay master, otherMaster;
        public final TkGroup merged;
        public final Problem problem;
        /** Neighbour block across the edge (may be any block); null when off-world. */
        public final int[] neighbour;
        Plan(TileTkDisplay master, TileTkDisplay otherMaster, TkGroup merged, Problem problem, int[] neighbour) {
            this.master = master; this.otherMaster = otherMaster; this.merged = merged; this.problem = problem; this.neighbour = neighbour;
        }
        public boolean ok() { return problem == null; }
    }

    private static TileTkDisplay display(World world, int[] p) {
        if (!world.blockExists(p[0], p[1], p[2])) return null;
        TileEntity t = world.getTileEntity(p[0], p[1], p[2]);
        return t instanceof TileTkDisplay ? (TileTkDisplay) t : null;
    }

    /**
     * Merging the group of {@code clicked} with the group across {@code edge} of the clicked
     * block. The clicked group keeps its master (its program and address).
     */
    public static Plan plan(World world, TileTkDisplay clicked, TkGroup.Edge edge) {
        int face = clicked.face();
        TileTkDisplay master = clicked.master();
        if (master == null) return new Plan(null, null, null, Problem.UNLOADED, null);
        int[] mPos = master.position();
        int[] at = TkGroup.uv(mPos, face, clicked.position());
        int[] n = TkGroup.position(mPos, face, at[0] + edge.du, at[1] + edge.dv);
        if (!world.blockExists(n[0], n[1], n[2])) return new Plan(master, null, null, Problem.UNLOADED, n);
        TileTkDisplay neighbour = display(world, n);
        if (neighbour == null) return new Plan(master, null, null, Problem.NO_NEIGHBOUR, n);
        if (neighbour.face() != face) return new Plan(master, null, null, Problem.FACING, n);
        TileTkDisplay other = neighbour.master();
        if (other == null) return new Plan(master, null, null, Problem.UNLOADED, n);
        if (other == master) return new Plan(master, other, null, Problem.SAME_GROUP, n);
        int[] otherUv = TkGroup.uv(mPos, face, other.position());
        if (otherUv == null) return new Plan(master, other, null, Problem.FACING, n);
        TkGroup merged = master.group().merge(other.group(), otherUv, edge);
        if (merged == null) {
            // Distinguish "too big" from "not a rectangle" for the player message.
            boolean rectangle = shapeOnly(master.group(), other.group(), otherUv, edge);
            return new Plan(master, other, null, rectangle ? Problem.TOO_LARGE : Problem.SHAPE, n);
        }
        for (int[] p : members(master)) if (display(world, p) == null) return new Plan(master, other, null, Problem.UNLOADED, n);
        for (int[] p : members(other)) if (display(world, p) == null) return new Plan(master, other, null, Problem.UNLOADED, n);
        return new Plan(master, other, merged, null, n);
    }

    /** Whether the merge is rectangular regardless of the size limit. */
    private static boolean shapeOnly(TkGroup a, TkGroup b, int[] bUv, TkGroup.Edge edge) {
        int oLeft = bUv[0] + b.left, oTop = bUv[1] + b.top;
        switch (edge) {
            case RIGHT: return oLeft == a.left + a.width && oTop == a.top && b.height == a.height;
            case LEFT: return oLeft + b.width == a.left && oTop == a.top && b.height == a.height;
            case BOTTOM: return oTop == a.top + a.height && oLeft == a.left && b.width == a.width;
            default: return oTop + b.height == a.top && oLeft == a.left && b.width == a.width;
        }
    }

    static List<int[]> members(TileTkDisplay master) { return master.group().members(master.position(), master.face()); }

    /** Server: carry out a merge {@link #plan} approved. Returns the problem, or null when done. */
    public static Problem merge(World world, TileTkDisplay clicked, TkGroup.Edge edge) {
        Plan plan = plan(world, clicked, edge);
        if (!plan.ok()) return plan.problem;
        int[] mPos = plan.master.position();
        List<TileTkDisplay> absorbed = new ArrayList<>(), kept = new ArrayList<>();
        for (int[] p : members(plan.otherMaster)) absorbed.add(display(world, p));
        for (int[] p : members(plan.master)) kept.add(display(world, p));
        for (TileTkDisplay t : absorbed) t.joinGroup(mPos, plan.merged, true);
        for (TileTkDisplay t : kept) t.joinGroup(mPos, plan.merged, t != plan.master);
        return null;
    }

    /** Server: split the group containing {@code any} back into independent 1x1 displays. */
    public static void split(World world, TileTkDisplay any) { split(world, any, null); }

    private static void split(World world, TileTkDisplay any, TileTkDisplay skip) {
        TileTkDisplay master = any.master();
        if (master == null) { if (any != skip) any.leaveGroup(); return; }
        for (int[] p : members(master)) {
            TileTkDisplay t = display(world, p);
            if (t != null && t != master && t != skip && t.master() == master) t.leaveGroup();
        }
        if (master != skip) master.leaveGroup();
    }

    /** Server: {@code broken} is being removed; the rest of its group becomes independent. */
    public static void dissolve(World world, TileTkDisplay broken) {
        if (!broken.group().single()) split(world, broken, broken);
    }

    /**
     * Server: a wrench click by {@code player}. {@code action} is an {@link TkGroup.Edge}
     * ordinal to merge across that edge, or {@link TkPackets.Wrench#SPLIT}. Reconfiguring
     * needs the permission of every group involved; the result is reported in chat.
     */
    public static void wrench(EntityPlayerMP player, TileTkDisplay clicked, int action) {
        World world = clicked.getWorldObj();
        if (action == TkPackets.Wrench.SPLIT) {
            if (clicked.group().single()) return;
            TileTkDisplay master = clicked.master();
            if (master != null && !master.mayReconfigure(player)) { tell(player, "mpy4oc.tk.notPermitted"); return; }
            split(world, clicked);
            tell(player, "mpy4oc.tk.split");
            return;
        }
        if (action < 0 || action >= TkGroup.Edge.values().length) return;
        TkGroup.Edge edge = TkGroup.Edge.values()[action];
        Plan plan = plan(world, clicked, edge);
        if (!plan.ok()) { tell(player, "mpy4oc.tk.merge." + plan.problem.name().toLowerCase(Locale.ROOT)); return; }
        if (!plan.master.mayReconfigure(player) || !plan.otherMaster.mayReconfigure(player)) {
            tell(player, "mpy4oc.tk.notPermitted");
            return;
        }
        merge(world, clicked, edge);
        TkGroup g = plan.merged;
        tell(player, "mpy4oc.tk.merged", g.width, g.height, g.maxWidth(), g.maxHeight());
    }

    private static void tell(EntityPlayerMP player, String key, Object... args) {
        player.addChatMessage(new ChatComponentTranslation(key, args));
    }
}
