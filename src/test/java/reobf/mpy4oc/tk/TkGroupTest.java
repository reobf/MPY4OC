package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TkGroupTest {
    @Test void uvRoundTripsAndMatchesTheSurfaceRendererAxes() {
        int[] master = {10, 64, -5};
        for (int face = 2; face <= 5; face++)
            for (int u = -3; u <= 3; u++) for (int v = -3; v <= 3; v++) {
                int[] p = TkGroup.position(master, face, u, v);
                assertArrayEquals(new int[] {u, v}, TkGroup.uv(master, face, p), "face " + face);
                // TkSurfacePolicy.point maps block-local u/v the same way: +u of one block is
                // where the neighbour at u+1 starts.
                double[] a = TkSurfacePolicy.point(face, u + .5, v + .5), b = TkSurfacePolicy.point(face, .5, .5);
                assertEquals(p[0] - master[0], Math.round(a[0] - b[0]), "x face " + face);
                assertEquals(p[1] - master[1], Math.round(a[1] - b[1]), "y face " + face);
                assertEquals(p[2] - master[2], Math.round(a[2] - b[2]), "z face " + face);
            }
        int[] n = TkGroup.normal(2);
        assertNull(TkGroup.uv(master, 2, new int[] {master[0], master[1], master[2] + n[2]}), "a block in front is not in the plane");
    }

    @Test void mergesFormRectanglesOnlyAndRespectTheSizeLimit() {
        TkGroup a = TkGroup.SINGLE;
        TkGroup ab = a.merge(TkGroup.SINGLE, new int[] {1, 0}, TkGroup.Edge.RIGHT);
        assertEquals(new TkGroup(0, 0, 2, 1), ab);
        // CD below AB, same width and aligned: 2x2.
        TkGroup cd = new TkGroup(0, 0, 2, 1);
        assertEquals(new TkGroup(0, 0, 2, 2), ab.merge(cd, new int[] {0, 1}, TkGroup.Edge.BOTTOM));
        // Merging a lone block under a 2-wide group would not be a rectangle.
        assertNull(ab.merge(TkGroup.SINGLE, new int[] {0, 1}, TkGroup.Edge.BOTTOM));
        // Offset rows do not merge.
        assertNull(ab.merge(new TkGroup(0, 0, 2, 1), new int[] {1, 1}, TkGroup.Edge.BOTTOM));
        // Merging leftwards keeps the clicked master's frame: the new group extends to negative u.
        assertEquals(new TkGroup(-1, 0, 2, 1), a.merge(TkGroup.SINGLE, new int[] {-1, 0}, TkGroup.Edge.LEFT));
        TkGroup wide = new TkGroup(0, 0, 8, 1);
        assertNull(wide.merge(TkGroup.SINGLE, new int[] {8, 0}, TkGroup.Edge.RIGHT), "9 wide exceeds the limit");
        assertEquals(2560, new TkGroup(0, 0, 8, 8).maxWidth());
    }

    @Test void faceUvInvertsTheSurfacePoint() {
        for (int face = 2; face <= 5; face++)
            for (double u = .05; u < 1; u += .15) for (double v = .05; v < 1; v += .15) {
                double[] p = TkSurfacePolicy.point(face, u, v);
                // A hit on the front face lies on the block boundary, not .002 in front of it.
                double x = Math.min(1, Math.max(0, p[0])), y = p[1], z = Math.min(1, Math.max(0, p[2]));
                double[] back = TkSurfacePolicy.faceUv(face, x, y, z);
                assertEquals(u, back[0], 1e-9, "u face " + face);
                assertEquals(v, back[1], 1e-9, "v face " + face);
            }
    }

    @Test void edgeZones() {
        assertEquals(TkGroup.Edge.RIGHT, TkGroup.edgeAt(.95, .5));
        assertEquals(TkGroup.Edge.LEFT, TkGroup.edgeAt(.05, .5));
        assertEquals(TkGroup.Edge.TOP, TkGroup.edgeAt(.5, .1));
        assertEquals(TkGroup.Edge.BOTTOM, TkGroup.edgeAt(.5, .9));
        assertNull(TkGroup.edgeAt(.5, .5));
    }
}
