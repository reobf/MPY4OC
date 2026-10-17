package reobf.mpy4oc.tk;

import java.util.Map;

/** The "image" component operation: PhotoImage commands, validated before they change anything. */
final class TkImageApi {
    private TkImageApi() {}

    /** Largest put() block, in pixels. */
    static final int MAX_PUT_PIXELS = 65536;

    static Object call(TkTree tree, int id, String action, Map<String, Object> m) {
        if (action.equals("upload")) {
            // PhotoImage(file=...): the file arrives in pieces, so the program never holds
            // all of it (the VM's memory is smaller than an image file may be).
            TkGeometry.keys(m, "data", "offset");
            Object raw = m.get("data");
            if (!(raw instanceof byte[]) || ((byte[]) raw).length == 0) throw new IllegalArgumentException("upload data must be non-empty bytes");
            byte[] piece = (byte[]) raw;
            int offset = TkOptions.number(m, "offset", -1, TkImage.MAX_FILE_BYTES);
            // One upload per program: two programs loading files at once do not mix their pieces.
            if (offset == 0) tree.uploads.put(tree.caller, new java.io.ByteArrayOutputStream(Math.min(TkImage.MAX_FILE_BYTES, piece.length * 4)));
            java.io.ByteArrayOutputStream upload = tree.uploads.get(tree.caller);
            if (upload == null || offset != upload.size()) { tree.uploads.remove(tree.caller); throw new IllegalArgumentException("upload out of order"); }
            if (upload.size() + piece.length > TkImage.MAX_FILE_BYTES) { tree.uploads.remove(tree.caller); throw new IllegalArgumentException("image file larger than 1 MiB"); }
            upload.write(piece, 0, piece.length);
            return upload.size();
        }
        if (action.equals("create")) {
            TkGeometry.keys(m, "data", "upload", "fit", "width", "height");
            TkImage image;
            if (m.containsKey("data") || m.containsKey("upload")) {
                if (m.containsKey("width") || m.containsKey("height") || m.containsKey("data") && m.containsKey("upload"))
                    throw new IllegalArgumentException("give either data/file or width and height");
                int fitWidth = 0, fitHeight = 0;
                if (m.containsKey("fit")) {
                    Object[] fit = TkOptions.sequence(m.get("fit"), 2);
                    if (fit.length != 2) throw new IllegalArgumentException("fit must be (width, height)");
                    // fit only ever shrinks: a box larger than an image can be is the same as the largest box.
                    fitWidth = Math.min(TkImage.MAX_SIDE, TkOptions.integer(fit[0], 1, Integer.MAX_VALUE));
                    fitHeight = Math.min(TkImage.MAX_SIDE, TkOptions.integer(fit[1], 1, Integer.MAX_VALUE));
                }
                byte[] data;
                if (m.containsKey("upload")) {
                    int size = TkOptions.number(m, "upload", 1, TkImage.MAX_FILE_BYTES);
                    java.io.ByteArrayOutputStream upload = tree.uploads.remove(tree.caller);
                    if (upload == null || upload.size() != size) throw new IllegalArgumentException("the image file did not arrive completely");
                    data = upload.toByteArray();
                } else data = bytes(m.get("data"));
                // Checked before decoding: a display that is full pays nothing for the attempt.
                TkImageCodec.Decoded decoded = TkImageCodec.decode(data, fitWidth, fitHeight, tree.imageRoom());
                image = tree.addImage(decoded.width, decoded.height, decoded.argb);
            } else {
                if (m.containsKey("fit")) throw new IllegalArgumentException("fit applies to data or file");
                int width = TkOptions.number(m, "width", 0, TkImage.MAX_SIDE), height = TkOptions.number(m, "height", 0, TkImage.MAX_SIDE);
                if (width < 1 || height < 1) throw new IllegalArgumentException("a blank image needs width and height");
                image = tree.addImage(width, height, new int[width * height]);
            }
            return new Object[] {image.id, image.width, image.height};
        }
        TkImage image = tree.ownedImage(id);
        if (image == null) throw new IllegalArgumentException("image was deleted");
        switch (action) {
            case "replace": {
                TkGeometry.keys(m, "data");
                TkImageCodec.Decoded decoded = TkImageCodec.decode(bytes(m.get("data")), 0, 0, image.width * image.height);
                if (decoded.width != image.width || decoded.height != image.height)
                    throw new IllegalArgumentException("images do not match");
                image.argb = decoded.argb;
                image.version++;
                tree.imageChanged();
                return null;
            }
            case "size": TkGeometry.keys(m); return new Object[] {image.width, image.height};
            case "delete": TkGeometry.keys(m); tree.deleteImage(id); return null;
            case "get": {
                TkGeometry.keys(m, "x", "y");
                int c = image.pixel(TkOptions.number(m, "x", 0, TkImage.MAX_SIDE), TkOptions.number(m, "y", 0, TkImage.MAX_SIDE));
                return new Object[] {(c >> 16) & 255, (c >> 8) & 255, c & 255};
            }
            case "put": {
                TkGeometry.keys(m, "rows", "x", "y", "x2", "y2");
                Object[] raw = TkOptions.sequence(m.get("rows"), TkImage.MAX_SIDE);
                int[][] rows = new int[raw.length][];
                int pixels = 0;
                for (int r = 0; r < raw.length; r++) {
                    Object[] row = TkOptions.sequence(raw[r], TkImage.MAX_SIDE);
                    pixels += row.length;
                    if (pixels > MAX_PUT_PIXELS) throw new IllegalArgumentException("put block too large");
                    rows[r] = new int[row.length];
                    for (int c = 0; c < row.length; c++) rows[r][c] = TkOptions.integer(row[c], -1, 0xFFFFFF);
                }
                boolean region = m.containsKey("x2") || m.containsKey("y2");
                int x2 = region ? TkOptions.number(m, "x2", 0, TkImage.MAX_SIDE) : -1, y2 = region ? TkOptions.number(m, "y2", 0, TkImage.MAX_SIDE) : -1;
                image.put(rows, TkOptions.number(m, "x", 0, TkImage.MAX_SIDE), TkOptions.number(m, "y", 0, TkImage.MAX_SIDE), x2, y2);
                tree.imageChanged();
                return null;
            }
            case "blank": TkGeometry.keys(m); image.blank(); tree.imageChanged(); return null;
            case "transparency_get": {
                TkGeometry.keys(m, "x", "y");
                return image.pixel(TkOptions.number(m, "x", 0, TkImage.MAX_SIDE), TkOptions.number(m, "y", 0, TkImage.MAX_SIDE)) >>> 24 == 0;
            }
            case "transparency_set": {
                TkGeometry.keys(m, "x", "y", "transparent");
                image.transparency(TkOptions.number(m, "x", 0, TkImage.MAX_SIDE), TkOptions.number(m, "y", 0, TkImage.MAX_SIDE),
                        TkOptions.bool(m.get("transparent")));
                tree.imageChanged();
                return null;
            }
            case "copy": case "zoom": case "subsample": {
                TkGeometry.keys(m, "x", "y");
                int fx = action.equals("copy") ? 1 : TkOptions.number(m, "x", 1, TkImage.MAX_SIDE);
                int fy = action.equals("copy") ? 1 : TkOptions.number(m, "y", fx, TkImage.MAX_SIDE);
                if (fx < 1 || fy < 1) throw new IllegalArgumentException("factors must be positive");
                boolean zoom = action.equals("zoom");
                long w = zoom ? (long) image.width * fx : (image.width + fx - 1) / fx, h = zoom ? (long) image.height * fy : (image.height + fy - 1) / fy;
                if (w > TkImage.MAX_SIDE || h > TkImage.MAX_SIDE) throw new IllegalArgumentException("result would exceed 1024x1024");
                int[] out = new int[(int) (w * h)];
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int sx = zoom ? x / fx : x * fx, sy = zoom ? y / fy : y * fy;
                    out[(int) (y * w + x)] = image.argb[sy * image.width + sx];
                }
                TkImage copy = tree.addImage((int) w, (int) h, out);
                return new Object[] {copy.id, copy.width, copy.height};
            }
            default: throw new IllegalArgumentException("unsupported image operation: " + action);
        }
    }

    /** Image file bytes: a Python bytes object, or Tk's base64 text. */
    static byte[] bytes(Object value) {
        if (value instanceof byte[]) return (byte[]) value;
        if (value instanceof String) {
            String text = (String) value;
            if (text.length() > TkImage.MAX_FILE_BYTES * 4 / 3 + 65536) throw new IllegalArgumentException("image data must be 1 byte to 1 MiB");
            // Line breaks and spaces are allowed (Tk's own base64 has them); anything else
            // outside the alphabet is an error, not silently dropped (a path passed as data=).
            StringBuilder compact = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') compact.append(c);
            }
            try { return java.util.Base64.getDecoder().decode(compact.toString()); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("image data text must be base64 (or give bytes, or file=)"); }
        }
        throw new IllegalArgumentException("image data must be bytes or base64 text");
    }
}
