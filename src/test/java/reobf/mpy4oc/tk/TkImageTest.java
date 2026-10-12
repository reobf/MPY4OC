package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.tk.net.TkPackets;

class TkImageTest {
    @BeforeAll static void registerTile() { TkTestRegistration.ensure(); }

    static byte[] encode(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, out), "writer for " + format);
        return out.toByteArray();
    }
    static BufferedImage picture(int w, int h, int type) {
        BufferedImage image = new BufferedImage(w, h, type);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            image.setRGB(x, y, (x == 0 && y == 0 ? 0x00000000 : 0xFF000000) | (x * 255 / Math.max(1, w - 1)) << 16 | (y * 255 / Math.max(1, h - 1)) << 8 | 0x40);
        return image;
    }

    @Test void decodesCommonFormatsAndKeepsPngAlpha() throws Exception {
        BufferedImage argb = picture(40, 30, BufferedImage.TYPE_INT_ARGB), rgb = picture(40, 30, BufferedImage.TYPE_INT_RGB);
        TkImageCodec.Decoded png = TkImageCodec.decode(encode(argb, "png"), 0, 0);
        assertEquals(40, png.width); assertEquals(30, png.height);
        assertArrayEquals(argb.getRGB(0, 0, 40, 30, null, 0, 40), png.argb, "PNG is lossless, alpha included");
        assertEquals(0, png.argb[0] >>> 24, "transparent pixel kept");
        for (String format : new String[] {"jpg", "gif", "bmp"}) {
            TkImageCodec.Decoded d = TkImageCodec.decode(encode(rgb, format), 0, 0);
            assertEquals(40, d.width, format); assertEquals(30, d.height, format);
            int c = d.argb[29 * 40 + 39];
            assertTrue(((c >> 16) & 255) > 200 && ((c >> 8) & 255) > 200, format + " bottom-right is light: " + Integer.toHexString(c));
        }
    }

    @Test void fitScalesDownOnlyAndHugeImagesAreRefusedBeforeDecoding() throws Exception {
        byte[] big = encode(picture(400, 300, BufferedImage.TYPE_INT_RGB), "png");
        TkImageCodec.Decoded fitted = TkImageCodec.decode(big, 100, 100);
        assertEquals(100, fitted.width); assertEquals(75, fitted.height);
        TkImageCodec.Decoded same = TkImageCodec.decode(big, 1000, 1000);
        assertEquals(400, same.width, "fit never enlarges");
        byte[] wide = encode(picture(1100, 4, BufferedImage.TYPE_INT_RGB), "png");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> TkImageCodec.decode(wide, 0, 0));
        assertTrue(refused.getMessage().contains("fit="), refused.getMessage());
        assertEquals(1024, TkImageCodec.decode(wide, 1024, 1024).width);
        // A PNG header claiming 20000x20000: refused from the header, no pixel buffer.
        byte[] header = encode(picture(2, 2, BufferedImage.TYPE_INT_RGB), "png");
        header[16] = 0; header[17] = 0; header[18] = 0x4E; header[19] = 0x20; header[20] = 0; header[21] = 0; header[22] = 0x4E; header[23] = 0x20;
        assertThrows(IllegalArgumentException.class, () -> TkImageCodec.decode(header, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> TkImageCodec.decode("not an image".getBytes("UTF-8"), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> TkImageCodec.decode(new byte[TkImage.MAX_FILE_BYTES + 1], 0, 0));
    }

    @Test void packedPixelsRoundTripAndCorruptionIsRejected() {
        Random random = new Random(7);
        int[] argb = new int[37 * 11];
        for (int i = 0; i < argb.length; i++) argb[i] = random.nextInt();
        byte[] packed = TkImageCodec.pack(37, 11, argb);
        assertArrayEquals(argb, TkImageCodec.unpack(packed, 37, 11));
        assertThrows(IllegalArgumentException.class, () -> TkImageCodec.unpack(packed, 36, 11));
        byte[] cut = Arrays.copyOf(packed, packed.length - 5);
        assertThrows(IllegalArgumentException.class, () -> TkImageCodec.unpack(cut, 37, 11));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
    private static int create(TkTree tree, Map<String, Object> options) {
        return (Integer) ((Object[]) TkImageApi.call(tree, 0, "create", options))[0];
    }

    @Test void imageOperationsAndBudgets() {
        TkTree tree = new TkTree();
        int id = create(tree, map("width", 4, "height", 3));
        TkImageApi.call(tree, id, "put", map("rows", new Object[] {new Object[] {0xFF0000, 0x00FF00}, new Object[] {0x0000FF, -1}}, "x", 1, "y", 1));
        assertArrayEquals(new Object[] {0, 255, 0}, (Object[]) TkImageApi.call(tree, id, "get", map("x", 2, "y", 1)));
        assertEquals(false, TkImageApi.call(tree, id, "transparency_get", map("x", 1, "y", 1)));
        assertEquals(true, TkImageApi.call(tree, id, "transparency_get", map("x", 2, "y", 2)), "-1 puts a transparent pixel");
        assertEquals(true, TkImageApi.call(tree, id, "transparency_get", map("x", 0, "y", 0)), "blank images start transparent");
        TkImageApi.call(tree, id, "put", map("rows", new Object[] {new Object[] {0x123456}}, "x", 0, "y", 0, "x2", 4, "y2", 1));
        assertArrayEquals(new Object[] {0x12, 0x34, 0x56}, (Object[]) TkImageApi.call(tree, id, "get", map("x", 3, "y", 0)), "tiled fill");
        assertThrows(IllegalArgumentException.class, () -> TkImageApi.call(tree, id, "put", map("rows", new Object[] {new Object[] {0}}, "x", 4, "y", 0)));
        Object[] zoom = (Object[]) TkImageApi.call(tree, id, "zoom", map("x", 3, "y", 2));
        assertEquals(12, zoom[1]); assertEquals(6, zoom[2]);
        Object[] sub = (Object[]) TkImageApi.call(tree, id, "subsample", map("x", 3));
        assertEquals(2, sub[1]); assertEquals(1, sub[2]);
        assertThrows(IllegalArgumentException.class, () -> TkImageApi.call(tree, id, "zoom", map("x", 400)));
        long version = tree.image(id).version();
        TkImageApi.call(tree, id, "blank", map());
        assertTrue(tree.image(id).version() > version);
        TkImageApi.call(tree, id, "delete", map());
        assertNull(tree.image(id));
        assertThrows(IllegalArgumentException.class, () -> TkImageApi.call(tree, id, "size", map()));

        TkTree full = new TkTree();
        create(full, map("width", 1024, "height", 512));
        assertThrows(IllegalArgumentException.class, () -> create(full, map("width", 1, "height", 1)), "pixel budget");
        TkTree many = new TkTree();
        for (int i = 0; i < TkImage.MAX_IMAGES; i++) create(many, map("width", 1, "height", 1));
        assertThrows(IllegalArgumentException.class, () -> create(many, map("width", 1, "height", 1)), "count limit");
    }

    @Test void labelsButtonsAndCanvasItemsPaintImages() {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        int id = create(tree, map("width", 20, "height", 10));
        Label label = new Label(tree.root(), "hidden by the image"); label.pack();
        TkOptions.configure(label, map("image", id));
        Canvas canvas = new Canvas(tree.root()); canvas.pack();
        int item = (Integer) canvas.call("create", map("type", "image", "coords", new Object[] {50, 40}, "options", map("image", id, "anchor", "nw")));
        TkDisplayList display = tree.render();
        TkDisplayList.Item paint = display.items.stream().filter(i -> i.id == label.id).findFirst().get();
        assertEquals(id, paint.image); assertEquals("", paint.text, "the image replaces the text");
        assertEquals(label.bounds().x + (label.bounds().width - 20) / 2, paint.textX, "centred");
        assertEquals(20 + label.insetWidth(), label.requestedWidth, "sized by the image");
        TkDisplayList.Item painted = display.items.stream().filter(i -> i.id == canvas.id && i.part == item).findFirst().get();
        assertEquals(TkVector.Shape.IMAGE, painted.vector.shape); assertEquals(id, painted.image);
        assertEquals(canvas.bounds().x + 50, painted.textX);
        assertArrayEquals(new Object[] {50, 40, 70, 50}, (Object[]) canvas.call("bbox", map("selector", item)));
        assertEquals(item, canvas.hitItem(canvas.bounds().x + 55, canvas.bounds().y + 45));
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(new Entry(tree.root()), map("image", id)), "Entry has no image");
        assertThrows(IllegalArgumentException.class, () -> TkOptions.configure(label, map("image", 999)));
        TkImageApi.call(tree, id, "delete", map());
        assertTrue(tree.render().items.stream().noneMatch(i -> i.image != 0), "a deleted image is simply not drawn");
    }

    static final class MemoryStorage implements TkImageStorage {
        final Map<Integer, byte[]> files = new HashMap<>();
        int writes;
        @Override public byte[] read(int id) { return files.get(id); }
        @Override public void write(int id, byte[] packed) { files.put(id, packed); writes++; }
        @Override public void retain(Set<Integer> ids) { files.keySet().retainAll(ids); }
    }

    @Test void imagesPersistBesideTheTreeAndOnlyChangesAreWritten() {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        int a = create(tree, map("width", 8, "height", 8)), b = create(tree, map("width", 2, "height", 2));
        TkImageApi.call(tree, a, "put", map("rows", new Object[] {new Object[] {0xABCDEF}}, "x", 3, "y", 4));
        Label label = new Label(tree.root(), ""); label.pack(); TkOptions.configure(label, map("image", a));
        Canvas canvas = new Canvas(tree.root()); canvas.pack();
        canvas.call("create", map("type", "image", "coords", new Object[] {5, 5}, "options", map("image", b)));
        MemoryStorage storage = new MemoryStorage();
        NBTTagCompound saved = TkTreePersistence.save(tree, storage);
        assertEquals(2, storage.writes);
        assertFalse(saved.getTagList("images", 10).getCompoundTagAt(0).hasKey("packed"), "pixels stay out of the NBT");
        TkTreePersistence.save(tree, storage);
        assertEquals(2, storage.writes, "unchanged images are not written again");
        TkTree loaded = TkTreePersistence.load(saved, 320, 240, () -> {}, storage);
        assertEquals(tree.render(), loaded.render());
        assertArrayEquals(new Object[] {0xAB, 0xCD, 0xEF}, (Object[]) TkImageApi.call(loaded, a, "get", map("x", 3, "y", 4)));
        TkImageApi.call(tree, b, "delete", map());
        TkTreePersistence.save(tree, storage);
        assertEquals(Collections.singleton(a), storage.files.keySet(), "deleted images are removed from storage");
        storage.files.clear();
        TkTree blank = TkTreePersistence.load(saved, 320, 240, () -> {}, storage);
        assertEquals(0, (int) ((Object[]) TkImageApi.call(blank, a, "get", map("x", 3, "y", 4)))[0], "missing pixels load as a blank image");
        TkTree inline = TkTreePersistence.load(TkTreePersistence.save(loaded), 320, 240, () -> {});
        assertArrayEquals(new Object[] {0xAB, 0xCD, 0xEF}, (Object[]) TkImageApi.call(inline, a, "get", map("x", 3, "y", 4)));
    }

    @Test void scenesCarryTheImageTableAndPixelsTravelInPieces() throws Exception {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        int id = create(tree, map("width", 300, "height", 200));
        Random random = new Random(3);
        for (int y = 0; y < 200; y += 50) {
            Object[] rows = new Object[50];
            for (int r = 0; r < 50; r++) { Object[] row = new Object[300]; for (int x = 0; x < 300; x++) row[x] = random.nextInt(0x1000000); rows[r] = row; }
            TkImageApi.call(tree, id, "put", map("rows", rows, "x", 0, "y", y));
        }
        Label label = new Label(tree.root(), ""); label.pack(); TkOptions.configure(label, map("image", id));
        TkScene scene = new TkScene(320, 240, 0, true, tree.render(), tree.imageTable());
        TkTarget target = new TkTarget(0, 0, 0, 0, "screen", 1);
        ByteBuf wire = Unpooled.buffer();
        try {
            new TkPackets.OpenGui(target, 1, scene).toBytes(wire);
            TkPackets.OpenGui decoded = new TkPackets.OpenGui(); decoded.fromBytes(wire);
            assertEquals(scene, decoded.scene);
            TkImageTransfer transfer = new TkImageTransfer();
            ByteArrayOutputStream assembled = new ByteArrayOutputStream();
            int pieces = 0;
            assertNull(transfer.next(null, tree), "nothing before the client has a scene");
            // 300x200 is packed off the server thread: the first piece comes once that is done.
            for (TkImageTransfer.Chunk c = await(transfer, scene, tree); c != null; c = transfer.next(scene, tree), pieces++) {
                wire.clear(); new TkPackets.ImageData(target, c).toBytes(wire);
                TkPackets.ImageData piece = new TkPackets.ImageData(); piece.fromBytes(wire);
                assertEquals(assembled.size(), piece.offset);
                assembled.write(piece.bytes, 0, piece.bytes.length);
                transfer.sent(c);
            }
            assertTrue(pieces > 1, "random pixels need several pieces");
            assertArrayEquals(tree.image(id).argb, TkImageCodec.unpack(assembled.toByteArray(), 300, 200));
            assertNull(transfer.next(scene, tree), "sent once");
            TkImageApi.call(tree, id, "put", map("rows", new Object[] {new Object[] {0}}));
            TkScene next = new TkScene(320, 240, 0, true, tree.render(), tree.imageTable());
            TkImageTransfer.Chunk first = await(transfer, next, tree);
            assertNotNull(first, "a new version is sent again");
            assertEquals(TkPackets.IMAGES, TkPackets.changes(scene, next) & TkPackets.IMAGES);
            // Edits while a version is on its way do not restart it: it is finished first.
            transfer.sent(first);
            TkImageApi.call(tree, id, "put", map("rows", new Object[] {new Object[] {0xFFFFFF}}));
            TkScene third = new TkScene(320, 240, 0, true, tree.render(), tree.imageTable());
            TkImageTransfer.Chunk second = transfer.next(third, tree);
            assertEquals(first.version, second.version); assertEquals(first.bytes.length, second.offset);
            transfer.reset();
        } finally {
            wire.release();
        }
    }

    static TkImageTransfer.Chunk await(TkImageTransfer transfer, TkScene scene, TkTree tree) throws InterruptedException {
        for (int i = 0; i < 5000; i++) {
            TkImageTransfer.Chunk chunk = transfer.next(scene, tree);
            if (chunk != null) return chunk;
            Thread.sleep(1);
        }
        return null;
    }

    @Test void grayImagesKeepTheirValues() throws Exception {
        for (int type : new int[] {BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_USHORT_GRAY}) {
            BufferedImage gray = new BufferedImage(4, 2, type);
            int max = type == BufferedImage.TYPE_BYTE_GRAY ? 255 : 65535;
            for (int x = 0; x < 4; x++) { gray.getRaster().setSample(x, 0, 0, max / 2 + 1); gray.getRaster().setSample(x, 1, 0, max); }
            TkImageCodec.Decoded d = TkImageCodec.decode(encode(gray, "png"), 0, 0);
            assertEquals(0xFF808080, d.argb[0], "128 stays 128, not getRGB's 188: " + Integer.toHexString(d.argb[0]));
            assertEquals(0xFFFFFFFF, d.argb[4]);
        }
        BufferedImage gray = new BufferedImage(16, 16, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) gray.getRaster().setSample(x, y, 0, 128);
        int c = TkImageCodec.decode(encode(gray, "jpg"), 0, 0).argb[0] & 255;
        assertTrue(Math.abs(c - 128) <= 3, "gray JPEG: " + c);
    }

    @Test void longThinImagesSubsampleEachAxisAndOversizedSourcesAreRefused() throws Exception {
        TkImageCodec.Decoded thin = TkImageCodec.decode(encode(picture(16000, 2, BufferedImage.TYPE_INT_RGB), "png"), 100, 100);
        assertEquals(100, thin.width); assertEquals(1, thin.height);
        IllegalArgumentException wide = assertThrows(IllegalArgumentException.class,
                () -> TkImageCodec.decode(encode(picture(16385, 1, BufferedImage.TYPE_INT_RGB), "png"), 100, 100));
        assertTrue(wide.getMessage().contains("too large"), wide.getMessage());
    }

    @Test void compressedBmpsManyScanJpegsAndOtherFormatsAreRefused() throws Exception {
        byte[] bmp = encode(picture(8, 8, BufferedImage.TYPE_INT_RGB), "bmp");
        assertEquals(8, TkImageCodec.decode(bmp, 0, 0).width);
        bmp[30] = 1; // BI_RLE8
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TkImageCodec.decode(bmp, 0, 0)).getMessage().contains("BMP"));
        ByteArrayOutputStream scans = new ByteArrayOutputStream();
        scans.write(0xFF); scans.write(0xD8);
        for (int i = 0; i < 40; i++) { scans.write(0xFF); scans.write(0xDA); scans.write(0); scans.write(2); scans.write(0x12); }
        scans.write(0xFF); scans.write(0xD9);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TkImageCodec.decode(scans.toByteArray(), 0, 0)).getMessage().contains("scans"));
        TkImageCodec.checkJpeg(encode(picture(64, 64, BufferedImage.TYPE_INT_RGB), "jpg")); // a real one passes
        byte[] wbmp = {0, 0, 8, 8, 0, 0, 0, 0, 0, 0, 0, 0};
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TkImageCodec.decode(wbmp, 0, 0)).getMessage().contains("unsupported"));
    }

    @Test void pngTextAndProfileChunksAreDroppedBeforeDecoding() throws Exception {
        BufferedImage argb = picture(10, 6, BufferedImage.TYPE_INT_ARGB);
        byte[] png = encode(argb, "png");
        // A zTXt chunk (it could inflate to far more than the file) before IEND; the CRC is not even right.
        ByteArrayOutputStream withText = new ByteArrayOutputStream();
        withText.write(png, 0, png.length - 12);
        byte[] text = new byte[5000];
        withText.write(new byte[] {0, 0, (byte) (text.length >> 8), (byte) text.length, 'z', 'T', 'X', 't'});
        withText.write(text); withText.write(new byte[4]);
        withText.write(png, png.length - 12, 12);
        byte[] essentials = TkImageCodec.pngEssentials(withText.toByteArray());
        assertArrayEquals(TkImageCodec.pngEssentials(png), essentials);
        assertArrayEquals(argb.getRGB(0, 0, 10, 6, null, 0, 10), TkImageCodec.decode(withText.toByteArray(), 0, 0).argb);
        assertThrows(IllegalArgumentException.class, () -> TkImageCodec.decode(Arrays.copyOf(png, png.length - 20), 0, 0), "cut short");
    }

    @Test void aFullDisplayRefusesBeforeDecodingAndFitIsClampedToTheLargestImage() throws Exception {
        TkTree tree = new TkTree();
        create(tree, map("width", 1024, "height", 500));
        byte[] png = encode(picture(200, 200, BufferedImage.TYPE_INT_RGB), "png");
        IllegalArgumentException full = assertThrows(IllegalArgumentException.class, () -> create(tree, map("data", png)));
        assertTrue(full.getMessage().contains("image room") && full.getMessage().contains("fit="), full.getMessage());
        assertEquals(12, ((Object[]) TkImageApi.call(tree, 0, "create", map("data", png, "fit", new Object[] {12, 12})))[1], "fits in what is left");
        TkTree other = new TkTree();
        Object[] wide = (Object[]) TkImageApi.call(other, 0, "create", map("data", encode(picture(2000, 100, BufferedImage.TYPE_INT_RGB), "png"),
                "fit", new Object[] {5000, 5000}));
        assertEquals(1024, wide[1], "a box beyond 1024 means 1024");
    }

    @Test void filesArriveInPiecesAndBase64IsStrict() throws Exception {
        TkTree tree = new TkTree();
        byte[] png = encode(picture(30, 20, BufferedImage.TYPE_INT_ARGB), "png");
        int half = png.length / 2;
        assertEquals(half, TkImageApi.call(tree, 0, "upload", map("data", Arrays.copyOf(png, half), "offset", 0)));
        assertThrows(IllegalArgumentException.class, () -> TkImageApi.call(tree, 0, "create", map("upload", half + 1)), "incomplete");
        assertNull(tree.uploads.get(0), "a failed create forgets the pieces");
        TkImageApi.call(tree, 0, "upload", map("data", Arrays.copyOf(png, half), "offset", 0));
        assertThrows(IllegalArgumentException.class, () -> TkImageApi.call(tree, 0, "upload", map("data", new byte[] {1}, "offset", 3)), "out of order");
        TkImageApi.call(tree, 0, "upload", map("data", Arrays.copyOf(png, half), "offset", 0));
        TkImageApi.call(tree, 0, "upload", map("data", Arrays.copyOfRange(png, half, png.length), "offset", half));
        Object[] made = (Object[]) TkImageApi.call(tree, 0, "create", map("upload", png.length));
        assertEquals(30, made[1]); assertNull(tree.uploads.get(0));
        TkImageApi.call(tree, 0, "upload", map("data", new byte[TkImage.MAX_FILE_BYTES], "offset", 0));
        assertThrows(IllegalArgumentException.class, () -> TkImageApi.call(tree, 0, "upload", map("data", new byte[1], "offset", TkImage.MAX_FILE_BYTES)));
        tree.uploads.clear();
        String base64 = Base64.getMimeEncoder().encodeToString(png);
        assertTrue(base64.contains("\r\n"), "line breaks are allowed");
        assertEquals(30, ((Object[]) TkImageApi.call(tree, 0, "create", map("data", base64)))[1]);
        IllegalArgumentException path = assertThrows(IllegalArgumentException.class, () -> TkImageApi.call(tree, 0, "create", map("data", "/home/pic.png")));
        assertTrue(path.getMessage().contains("base64"), path.getMessage());
    }

    @Test void deletedImagesBlankTheirWidgetsWhichStillSaveAndConfigure() {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        int id = create(tree, map("width", 6, "height", 6));
        Label label = new Label(tree.root(), "text"); label.pack(); TkOptions.configure(label, map("image", id));
        Canvas canvas = new Canvas(tree.root()); canvas.pack();
        int shown = (Integer) canvas.call("create", map("type", "image", "coords", new Object[] {5, 5}, "options", map("image", id)));
        int empty = (Integer) canvas.call("create", map("type", "image", "coords", new Object[] {9, 9}, "options", map()));
        TkImageApi.call(tree, id, "delete", map());
        assertEquals(0, TkControlOptions.cget(label, "image"), "the label dropped the deleted image");
        assertEquals(0, canvas.call("cget", map("selector", shown, "option", "image")));
        TkOptions.configure(label, map("text", "still configurable"));
        TkTree loaded = TkTreePersistence.load(TkTreePersistence.save(tree, new MemoryStorage()), 320, 240, () -> {}, new MemoryStorage());
        assertEquals(tree.render(), loaded.render(), "image items without an image load too");
        assertNotNull(((Canvas) loaded.get(canvas.id)).items.get(empty));
        // A save from before this fix kept the deleted id: it loads as no image.
        NBTTagCompound old = TkTreePersistence.save(tree);
        NBTTagList nodes = old.getTagList("nodes", 10);
        for (int i = 0; i < nodes.tagCount(); i++) if (nodes.getCompoundTagAt(i).getInteger("id") == label.id) nodes.getCompoundTagAt(i).setInteger("image", id);
        assertEquals(0, TkControlOptions.cget(TkTreePersistence.load(old, 320, 240, () -> {}).get(label.id), "image"));
    }

    @Test void aNewProgramsTreeKeepsCountingImageIds() {
        TkTree first = new TkTree();
        create(first, map("width", 1, "height", 1)); create(first, map("width", 1, "height", 1));
        TkTree second = new TkTree();
        second.continueImagesFrom(first);
        assertEquals(3, create(second, map("width", 1, "height", 1)), "never id 1 again on this display");
    }

    @Test void aTransferResendsAnImageOfTheSameIdFromAReplacedTree() throws Exception {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        int id = create(tree, map("width", 4, "height", 4));
        TkScene scene = new TkScene(320, 240, 0, true, tree.render(), tree.imageTable());
        TkImageTransfer transfer = new TkImageTransfer();
        transfer.sent(await(transfer, scene, tree));
        assertNull(transfer.next(scene, tree));
        TkTree replaced = new TkTree(); replaced.resize(320, 240);
        assertEquals(id, create(replaced, map("width", 4, "height", 4)), "same id and version, another image");
        assertNotNull(transfer.next(new TkScene(320, 240, 0, true, replaced.render(), replaced.imageTable()), replaced));
    }

    @Test void unreadableFilesAreNotOverwrittenAndStrayFilesDoNotBreakSaves() throws Exception {
        TkTree tree = new TkTree(); tree.resize(320, 240);
        create(tree, map("width", 3, "height", 3));
        MemoryStorage storage = new MemoryStorage();
        NBTTagCompound saved = TkTreePersistence.save(tree, storage);
        int[] writes = {0};
        TkImageStorage locked = new TkImageStorage() {
            @Override public byte[] read(int id) throws java.io.IOException { throw new java.io.IOException("locked"); }
            @Override public void write(int id, byte[] packed) { writes[0]++; }
            @Override public void retain(Set<Integer> ids) {}
        };
        TkTree loaded = TkTreePersistence.load(saved, 320, 240, () -> {}, locked);
        TkTreePersistence.save(loaded, locked);
        assertEquals(0, writes[0], "the blank stand-in does not replace the file");
        java.io.File dir = java.nio.file.Files.createTempDirectory("tk-images").toFile();
        try {
            TkImageStorage.Directory directory = new TkImageStorage.Directory(dir);
            directory.write(1, new byte[] {1, 2});
            assertTrue(new java.io.File(dir, "9999999999.tki").createNewFile());
            directory.retain(Collections.singleton(1));
            assertArrayEquals(new byte[] {1, 2}, directory.read(1));
            assertFalse(new java.io.File(dir, "9999999999.tki").exists());
            assertNull(directory.read(2));
        } finally {
            new TkImageStorage.Directory(dir).deleteAll();
            dir.delete();
        }
    }

    @Test void aCopiedDisplayForksItsImageDirectory() {
        TileTkDisplay original = new TileTkDisplay(), reloaded = new TileTkDisplay(), copy = new TileTkDisplay();
        original.xCoord = 1; reloaded.xCoord = 1; copy.xCoord = 5;
        String store = UUID.randomUUID().toString();
        try {
            assertTrue(TkImageStores.claim(store, original));
            assertTrue(TkImageStores.claim(store, reloaded), "the same display, loaded again as a new object");
            assertFalse(TkImageStores.claim(store, copy), "a copy somewhere else");
            TkImageStores.release(store, reloaded);
            assertTrue(TkImageStores.claim(store, copy), "free once released");
        } finally {
            TkImageStores.clear();
        }
    }
}
