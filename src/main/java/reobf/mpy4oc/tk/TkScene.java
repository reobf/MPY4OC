package reobf.mpy4oc.tk;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable display state shared by persistence, packets, and the client. */
public final class TkScene {
    public static final int DEFAULT_BACKGROUND = 0x202020;
    /** The longest window title (see validTitle). */
    public static final int MAX_TITLE_BYTES = 512;

    public final int width, height, background;
    public final boolean surfaceVisible;
    public final TkDisplayList display;
    /** The display's PhotoImages: what clients need, by id; the pixels travel separately. */
    public final Map<Integer, ImageRef> images;

    public static final class ImageRef {
        public final long version;
        public final int width, height;
        public ImageRef(long version, int width, int height) {
            if (version < 1) throw new IllegalArgumentException("invalid image version");
            TkImage.check(width, height);
            this.version = version; this.width = width; this.height = height;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof ImageRef)) return false;
            ImageRef r = (ImageRef) o;
            return version == r.version && width == r.width && height == r.height;
        }
        @Override public int hashCode() { return Objects.hash(version, width, height); }
    }

    public TkScene(int width, int height, int background, boolean surfaceVisible) {
        this(width, height, background, surfaceVisible, TkDisplayList.EMPTY);
    }

    public TkScene(int width, int height, int background, boolean surfaceVisible, TkDisplayList display) {
        this(width, height, background, surfaceVisible, display, Collections.emptyMap());
    }

    public TkScene(int width, int height, int background, boolean surfaceVisible, TkDisplayList display,
                   Map<Integer, ImageRef> images) {
        if (!validSize(width) || !validSize(height)) throw new IllegalArgumentException("resolution must be between " + TileTkDisplay.MIN_SIZE + " and " + TileTkDisplay.MAX_SIZE);
        if (background < 0 || background > 0xFFFFFF) throw new IllegalArgumentException("color must be between 0 and 0xFFFFFF");
        this.width = width;
        this.height = height;
        this.background = background;
        this.surfaceVisible = surfaceVisible;
        this.display = Objects.requireNonNull(display);
        if (images.size() > TkImage.MAX_IMAGES) throw new IllegalArgumentException("too many images");
        long pixels = 0;
        for (Map.Entry<Integer, ImageRef> e : images.entrySet()) {
            if (e.getKey() == null || e.getKey() < 1 || e.getValue() == null) throw new IllegalArgumentException("invalid image id");
            pixels += (long) e.getValue().width * e.getValue().height;
        }
        if (pixels > TkImage.MAX_PIXELS) throw new IllegalArgumentException("image pixel budget exceeded");
        this.images = images.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(images));
    }

    public static boolean validSize(int size) {
        return size >= TileTkDisplay.MIN_SIZE && size <= TileTkDisplay.MAX_SIZE;
    }

    /** A window title: one line of at most MAX_TITLE_BYTES UTF-8 bytes. */
    public static boolean validTitle(String title) {
        return title != null && title.getBytes(StandardCharsets.UTF_8).length <= MAX_TITLE_BYTES
                && title.codePoints().noneMatch(c -> c < 32 || c == 127 || c >= 0xD800 && c <= 0xDFFF);
    }

    /** A display of one block: the largest canvas it allows (see defaults(int, int)). */
    public static TkScene defaults() {
        return defaults(TkGroup.SINGLE.maxWidth(), TkGroup.SINGLE.maxHeight());
    }

    /** A new display: the given canvas size (its group's largest), the default desktop color, nothing on it. */
    public static TkScene defaults(int width, int height) {
        return new TkScene(width, height, DEFAULT_BACKGROUND, true);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TkScene)) return false;
        TkScene scene = (TkScene) other;
        return width == scene.width && height == scene.height && background == scene.background
                && surfaceVisible == scene.surfaceVisible && display.equals(scene.display)
                && images.equals(scene.images);
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, height, background, surfaceVisible, display, images);
    }
}
