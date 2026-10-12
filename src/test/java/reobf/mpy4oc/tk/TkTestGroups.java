package reobf.mpy4oc.tk;

/** Test access to multiblock groups without a world: a master of a {@code width x height} group. */
public final class TkTestGroups {
    private TkTestGroups() {}

    public static TileTkDisplay joined(int width, int height) {
        TileTkDisplay tile = new TileTkDisplay();
        tile.joinGroup(tile.position(), new TkGroup(0, 0, width, height), false);
        return tile;
    }
}
