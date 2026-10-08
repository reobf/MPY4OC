package reobf.mpy4oc.tk;

import java.util.Objects;

/** A block identity plus one player's viewing session; never just coordinates. */
public final class TkTarget {
    public final int dimension, x, y, z;
    public final String address;
    public final long session;

    public TkTarget(int dimension, int x, int y, int z, String address, long session) {
        if (x < -30_000_000 || x >= 30_000_000 || z < -30_000_000 || z >= 30_000_000 || y < 0 || y > 255)
            throw new IllegalArgumentException("invalid display position");
        if (address == null || address.isEmpty() || address.length() > 128 || session == 0)
            throw new IllegalArgumentException("invalid display identity");
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.address = address;
        this.session = session;
    }

    public boolean matches(TileTkDisplay tile) {
        return tile.getWorldObj() != null && tile.getWorldObj().provider.dimensionId == dimension
                && tile.xCoord == x && tile.yCoord == y && tile.zCoord == z
                && tile.node() != null && address.equals(tile.node().address());
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TkTarget)) return false;
        TkTarget target = (TkTarget) other;
        return dimension == target.dimension && x == target.x && y == target.y && z == target.z
                && session == target.session && address.equals(target.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dimension, x, y, z, address, session);
    }
}
