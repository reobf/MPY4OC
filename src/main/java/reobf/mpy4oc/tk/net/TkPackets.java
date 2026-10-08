package reobf.mpy4oc.tk.net;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import reobf.mpy4oc.tk.TkScene;
import reobf.mpy4oc.tk.TkVector;
import reobf.mpy4oc.tk.TkTarget;
import reobf.mpy4oc.tk.TkDisplayList;
import reobf.mpy4oc.tk.TkFontMetrics;
import reobf.mpy4oc.tk.TkRect;
import reobf.mpy4oc.tk.TkWidget;
import reobf.mpy4oc.tk.TkDisplayPatch;

/** Protocol 9. Strings, paint records and coordinates are bounded before reaching game state. */
public final class TkPackets {
    public static final int SIZE = 1, BACKGROUND = 2, TITLE = 4, SURFACE = 8, DISPLAY = 16, ALL = 31;
    public static final int MAX_CLIPBOARD_BYTES = 4096;

    private TkPackets() {}

    public static class SceneSync implements IMessage {
        public TkTarget target;
        public long baseRevision, revision;
        public int mask;
        public TkScene scene;
        public TkDisplayPatch displayPatch;

        public SceneSync() {}
        public SceneSync(TkTarget target, long baseRevision, long revision, int mask, TkScene scene) {
            this.target = target;
            this.baseRevision = baseRevision;
            this.revision = revision;
            this.mask = mask;
            this.scene = scene;
            validate();
        }

        public boolean isFull() { return baseRevision == -1; }

        public TkScene apply(TkScene previous) {
            return new TkScene((mask & SIZE) != 0 ? scene.width : previous.width,
                    (mask & SIZE) != 0 ? scene.height : previous.height,
                    (mask & BACKGROUND) != 0 ? scene.background : previous.background,
                    (mask & TITLE) != 0 ? scene.title : previous.title,
                    (mask & SURFACE) != 0 ? scene.surfaceVisible : previous.surfaceVisible,
                    (mask & DISPLAY) != 0 ? displayPatch == null ? scene.display : displayPatch.apply(previous.display) : previous.display);
        }

        private void validate() {
            if (mask <= 0 || (mask & ~ALL) != 0 || revision < 1 || baseRevision < -1
                    || !isFull() && (baseRevision < 1 || revision <= baseRevision) || isFull() && mask != ALL)
                throw new IllegalArgumentException("invalid scene revision or mask");
        }

        @Override
        public void toBytes(ByteBuf out) {
            validate();
            writeTarget(out, target);
            out.writeLong(baseRevision).writeLong(revision).writeByte(mask);
            if ((mask & SIZE) != 0) out.writeShort(scene.width).writeShort(scene.height);
            if ((mask & BACKGROUND) != 0) out.writeInt(scene.background);
            if ((mask & TITLE) != 0) writeString(out, scene.title, TkScene.MAX_TITLE_BYTES);
            if ((mask & SURFACE) != 0) out.writeBoolean(scene.surfaceVisible);
            if ((mask & DISPLAY) != 0) {
                if (isFull() && displayPatch != null) throw new IllegalArgumentException("full scene cannot be a patch");
                out.writeBoolean(displayPatch != null);
                if (displayPatch == null) writeDisplay(out, scene.display);
                else { out.writeShort(displayPatch.order.length); for (long key : displayPatch.order) out.writeLong(key); writeDisplay(out, displayPatch.changed); }
            }
        }

        @Override
        public void fromBytes(ByteBuf in) {
            target = readTarget(in);
            baseRevision = in.readLong();
            revision = in.readLong();
            mask = in.readUnsignedByte();
            validate();
            int width = (mask & SIZE) != 0 ? in.readUnsignedShort() : 320;
            int height = (mask & SIZE) != 0 ? in.readUnsignedShort() : 240;
            int background = (mask & BACKGROUND) != 0 ? in.readInt() : TkScene.DEFAULT_BACKGROUND;
            String title = (mask & TITLE) != 0 ? readString(in, TkScene.MAX_TITLE_BYTES) : TkScene.DEFAULT_TITLE;
            boolean surface = (mask & SURFACE) == 0 || in.readBoolean();
            TkDisplayList display = TkDisplayList.EMPTY;
            if ((mask & DISPLAY) != 0) {
                if (!in.readBoolean()) display = readDisplay(in);
                else {
                    if (isFull()) throw new IllegalArgumentException("full scene cannot be a patch");
                    int count = in.readUnsignedShort(); if (count > TkDisplayList.MAX_RECORDS) throw new IllegalArgumentException("too many paint keys");
                    long[] order = new long[count]; for (int i = 0; i < count; i++) order[i] = in.readLong();
                    displayPatch = new TkDisplayPatch(order, readDisplay(in));
                }
            }
            scene = new TkScene(width, height, background, title, surface, display);
            end(in);
        }
    }

    public static final class OpenGui extends SceneSync {
        public OpenGui() {}
        public OpenGui(TkTarget target, long revision, TkScene scene) { super(target, -1, revision, ALL, scene); }
        @Override
        public void fromBytes(ByteBuf in) {
            super.fromBytes(in);
            if (!isFull()) throw new IllegalArgumentException("opening requires a full scene");
        }
    }

    /** Separate message type prevents a passive display update from opening or mutating a GUI. */
    public static final class SurfaceSync extends SceneSync {
        public SurfaceSync() {}
        public SurfaceSync(TkTarget target,long base,long revision,int mask,TkScene scene) { super(target,base,revision,mask,scene); }
    }
    public static final class SurfaceEnd extends CloseRequest {
        public SurfaceEnd() {}
        public SurfaceEnd(TkTarget target) { super(target); }
    }
    public static final class SurfaceRequest implements IMessage {
        public static final int WATCH=0, RESYNC=1, CLOSE=2;
        public int dimension,x,y,z,action;
        public long nonce;
        public SurfaceRequest() {}
        public SurfaceRequest(int dimension,int x,int y,int z,long nonce,int action) {
            this.dimension=dimension;this.x=x;this.y=y;this.z=z;this.nonce=nonce;this.action=action;validate();
        }
        private void validate() {
            if(x< -30000000||x>=30000000||z< -30000000||z>=30000000||y<0||y>255||nonce<=0||action<WATCH||action>CLOSE)
                throw new IllegalArgumentException("invalid surface request");
        }
        @Override public void toBytes(ByteBuf out) {validate();out.writeInt(dimension).writeInt(x).writeInt(y).writeInt(z).writeLong(nonce).writeByte(action);}
        @Override public void fromBytes(ByteBuf in) {dimension=in.readInt();x=in.readInt();y=in.readInt();z=in.readInt();nonce=in.readLong();action=in.readUnsignedByte();validate();end(in);}
    }

    public abstract static class Request implements IMessage {
        public TkTarget target;
        public Request() {}
        public Request(TkTarget target) { this.target = target; }
    }

    public static class CloseRequest extends Request {
        public CloseRequest() {}
        public CloseRequest(TkTarget target) { super(target); }
        @Override public void toBytes(ByteBuf out) { writeTarget(out, target); }
        @Override public void fromBytes(ByteBuf in) { target = readTarget(in); end(in); }
    }

    public static final class CloseGui extends CloseRequest {
        public CloseGui() {}
        public CloseGui(TkTarget target) { super(target); }
    }

    public static final class Resync extends CloseRequest {
        public Resync() {}
        public Resync(TkTarget target) { super(target); }
    }

    public static final class Heartbeat extends CloseRequest {
        public Heartbeat() {}
        public Heartbeat(TkTarget target) { super(target); }
    }

    public static final class InputEvent extends Request {
        public static final int MOUSE_DOWN = 0, MOUSE_UP = 1, KEY_DOWN = 2, MOUSE_CANCEL = 3,
                MOTION = 4, DRAG = 5, WHEEL = 6, KEY_UP = 7;
        public int kind, x, y, code, character, modifiers;
        public InputEvent() {}
        public InputEvent(TkTarget target, int kind, int x, int y, int code, int character, int modifiers) {
            super(target);
            this.kind = kind; this.x = x; this.y = y; this.code = code;
            this.character = character; this.modifiers = modifiers;
            validate();
        }
        private void validate() {
            boolean outside = kind == DRAG || kind == MOUSE_UP;
            boolean key = kind == KEY_DOWN || kind == KEY_UP;
            if (kind < MOUSE_DOWN || kind > KEY_UP || x < (outside ? -1024 : 0) || x >= (outside ? 2048 : 1024)
                    || y < (outside ? -1024 : 0) || y >= (outside ? 2048 : 1024)
                    || (kind == WHEEL ? code == 0 || code < -1200 || code > 1200 : code < 0 || code > (key ? 255 : 15))
                    || character < 0 || character > 0x10FFFF || character >= 0xD800 && character <= 0xDFFF
                    || !key && character != 0 || modifiers < 0 || modifiers > 7)
                throw new IllegalArgumentException("invalid input");
        }
        @Override
        public void toBytes(ByteBuf out) {
            validate(); writeTarget(out, target);
            out.writeByte(kind).writeShort(x).writeShort(y).writeShort(code).writeInt(character).writeByte(modifiers);
        }
        @Override
        public void fromBytes(ByteBuf in) {
            target = readTarget(in); kind = in.readUnsignedByte(); x = in.readShort(); y = in.readShort();
            code = in.readShort(); character = in.readInt(); modifiers = in.readUnsignedByte();
            validate(); end(in);
        }
    }

    public static final class Clipboard extends Request {
        public String text;
        public Clipboard() {}
        public Clipboard(TkTarget target, String text) { super(target); this.text = text; }
        @Override public void toBytes(ByteBuf out) { writeTarget(out, target); writeString(out, text, MAX_CLIPBOARD_BYTES); }
        @Override public void fromBytes(ByteBuf in) { target = readTarget(in); text = readString(in, MAX_CLIPBOARD_BYTES); end(in); }
    }

    /** A copy/cut is answered only to the requesting GUI session. */
    public static final class CopyRequest extends Request {
        public int request; public boolean cut;
        public CopyRequest() {}
        public CopyRequest(TkTarget target, int request, boolean cut) { super(target); this.request = request; this.cut = cut; }
        @Override public void toBytes(ByteBuf out) { if (request < 1) throw new IllegalArgumentException("invalid copy request"); writeTarget(out, target); out.writeInt(request).writeBoolean(cut); }
        @Override public void fromBytes(ByteBuf in) { target = readTarget(in); request = in.readInt(); cut = in.readBoolean(); if (request < 1) throw new IllegalArgumentException("invalid copy request"); end(in); }
    }
    public static final class CopyResult extends Request {
        public int request; public String text;
        public CopyResult() {}
        public CopyResult(TkTarget target, int request, String text) { super(target); this.request = request; this.text = text; }
        @Override public void toBytes(ByteBuf out) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (request < 1 || bytes.length > reobf.mpy4oc.tk.Text.MAX_BYTES) throw new IllegalArgumentException("invalid copy result");
            writeTarget(out, target); out.writeInt(request).writeInt(bytes.length).writeBytes(bytes);
        }
        @Override public void fromBytes(ByteBuf in) {
            target = readTarget(in); request = in.readInt(); if (request < 1) throw new IllegalArgumentException("invalid copy result");
            text = readUtf8(in, in.readInt(), reobf.mpy4oc.tk.Text.MAX_BYTES); end(in);
        }
    }

    public static int changes(TkScene before, TkScene after) {
        int mask = 0;
        if (before.width != after.width || before.height != after.height) mask |= SIZE;
        if (before.background != after.background) mask |= BACKGROUND;
        if (!before.title.equals(after.title)) mask |= TITLE;
        if (before.surfaceVisible != after.surfaceVisible) mask |= SURFACE;
        if (!before.display.equals(after.display)) mask |= DISPLAY;
        return mask;
    }

    private static void writeDisplay(ByteBuf out, TkDisplayList display) {
        out.writeShort(display.items.size());
        for (TkDisplayList.Item item : display.items) {
            out.writeInt(item.id).writeInt(item.part).writeByte(item.kind.ordinal());
            out.writeInt(item.bounds.x).writeInt(item.bounds.y).writeInt(item.bounds.width).writeInt(item.bounds.height); writeRect(out, item.clip);
            out.writeInt(item.background).writeInt(item.foreground).writeByte(item.border).writeByte(item.state);
            out.writeInt(item.textX).writeInt(item.textY);
            out.writeShort(item.caretX);
            out.writeShort(item.selectionX).writeShort(item.selectionWidth);
            writeString(out, item.text, 4096);
            out.writeBoolean(item.vector != null);
            if (item.vector != null) {
                TkVector v = item.vector;
                out.writeByte(v.shape.ordinal()).writeInt(v.fill).writeInt(v.outline).writeDouble(v.width).writeShort(v.count());
                for (int j = 0; j < v.count(); j++) out.writeDouble(v.at(j));
            }
        }
    }

    private static TkDisplayList readDisplay(ByteBuf in) {
        int count = in.readUnsignedShort(), bytes = 0, coordinates = 0;
        if (count > TkDisplayList.MAX_RECORDS) throw new IllegalArgumentException("too many paint records");
        List<TkDisplayList.Item> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int id = in.readInt(), part = in.readInt(), kind = in.readUnsignedByte();
            if (kind >= TkWidget.Kind.values().length) throw new IllegalArgumentException("invalid widget kind");
            TkRect bounds = new TkRect(in.readInt(), in.readInt(), in.readInt(), in.readInt()), clip = readRect(in);
            int background = in.readInt(), foreground = in.readInt(), border = in.readUnsignedByte(), state = in.readUnsignedByte();
            int textX = in.readInt(), textY = in.readInt(), caretX = in.readShort();
            int selectionX = in.readUnsignedShort(), selectionWidth = in.readUnsignedShort();
            String text = readString(in, Math.min(4096, TkDisplayList.MAX_PAINT_BYTES - bytes));
            bytes += text.getBytes(StandardCharsets.UTF_8).length;
            TkVector vector = null;
            if (in.readBoolean()) {
                int shape = in.readUnsignedByte(), fill = in.readInt(), outline = in.readInt(); double stroke = in.readDouble();
                int length = in.readUnsignedShort(); coordinates += length;
                if (shape >= TkVector.Shape.values().length || length > TkVector.MAX_COORDS || coordinates > TkVector.MAX_TOTAL_COORDS
                        || length * 8 > in.readableBytes()) throw new IllegalArgumentException("invalid vector payload");
                double[] points = new double[length]; for (int j = 0; j < length; j++) points[j] = in.readDouble();
                vector = new TkVector(TkVector.Shape.values()[shape], points, fill, outline, stroke);
            }
            items.add(new TkDisplayList.Item(id, part, TkWidget.Kind.values()[kind], bounds, clip,
                    background, foreground, border, state, textX, textY, text, caretX, selectionX, selectionWidth, vector));
        }
        return new TkDisplayList(items);
    }

    private static void writeRect(ByteBuf out, TkRect r) { out.writeShort(r.x).writeShort(r.y).writeShort(r.width).writeShort(r.height); }
    private static TkRect readRect(ByteBuf in) {
        return new TkRect(in.readUnsignedShort(), in.readUnsignedShort(), in.readUnsignedShort(), in.readUnsignedShort());
    }

    private static void writeTarget(ByteBuf out, TkTarget target) {
        out.writeInt(target.dimension).writeInt(target.x).writeInt(target.y).writeInt(target.z).writeLong(target.session);
        writeString(out, target.address, 512);
    }

    private static TkTarget readTarget(ByteBuf in) {
        int dimension = in.readInt(), x = in.readInt(), y = in.readInt(), z = in.readInt();
        long session = in.readLong();
        return new TkTarget(dimension, x, y, z, readString(in, 512), session);
    }

    private static void writeString(ByteBuf out, String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new IllegalArgumentException("text too long");
        out.writeShort(bytes.length).writeBytes(bytes);
    }

    private static String readString(ByteBuf in, int maxBytes) {
        return readUtf8(in, in.readUnsignedShort(), maxBytes);
    }
    private static String readUtf8(ByteBuf in, int length, int maxBytes) {
        if (length < 0 || length > maxBytes || length > in.readableBytes()) throw new IllegalArgumentException("invalid text length");
        byte[] bytes = new byte[length];
        in.readBytes(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("invalid UTF-8", e);
        }
    }

    private static void end(ByteBuf in) {
        if (in.isReadable()) throw new IllegalArgumentException("trailing packet data");
    }
}
