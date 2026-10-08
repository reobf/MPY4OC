package reobf.mpy4oc.tk;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Immutable display state shared by persistence, packets, and the client. */
public final class TkScene {
    public static final int DEFAULT_BACKGROUND = 0x202020;
    public static final String DEFAULT_TITLE = "Tk Display";
    public static final int MAX_TITLE_BYTES = 512;

    public final int width, height, background;
    public final String title;
    public final boolean surfaceVisible;
    public final TkDisplayList display;

    public TkScene(int width, int height, int background, String title, boolean surfaceVisible) {
        this(width, height, background, title, surfaceVisible, TkDisplayList.EMPTY);
    }

    public TkScene(int width, int height, int background, String title, boolean surfaceVisible, TkDisplayList display) {
        if (!validSize(width) || !validSize(height)) throw new IllegalArgumentException("resolution must be between 64 and 1024");
        if (background < 0 || background > 0xFFFFFF) throw new IllegalArgumentException("color must be between 0 and 0xFFFFFF");
        if (!validTitle(title)) throw new IllegalArgumentException("title must be one line and at most 512 UTF-8 bytes");
        this.width = width;
        this.height = height;
        this.background = background;
        this.title = title;
        this.surfaceVisible = surfaceVisible;
        this.display = Objects.requireNonNull(display);
    }

    public static boolean validSize(int size) {
        return size >= TileTkDisplay.MIN_SIZE && size <= TileTkDisplay.MAX_SIZE;
    }

    public static boolean validTitle(String title) {
        return title != null && title.getBytes(StandardCharsets.UTF_8).length <= MAX_TITLE_BYTES
                && title.codePoints().noneMatch(c -> c < 32 || c == 127 || c >= 0xD800 && c <= 0xDFFF);
    }

    public static TkScene defaults() {
        return new TkScene(TileTkDisplay.DEFAULT_WIDTH, TileTkDisplay.DEFAULT_HEIGHT,
                DEFAULT_BACKGROUND, DEFAULT_TITLE, true);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TkScene)) return false;
        TkScene scene = (TkScene) other;
        return width == scene.width && height == scene.height && background == scene.background
                && surfaceVisible == scene.surfaceVisible && title.equals(scene.title) && display.equals(scene.display);
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, height, background, title, surfaceVisible, display);
    }
}
