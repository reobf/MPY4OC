package reobf.mpy4oc.tk;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import net.minecraft.nbt.NBTTagCompound;

/** Plain multi-line text. Indices and selections count Unicode code points.
 * The final newline is permanent, as in Tk; only visible display lines go over the wire.
 */
public final class Text extends TkWidget {
    public static final int MAX_BYTES = 512 * 1024, MAX_CHARS = 262144, MAX_LINES = 20000;
    private int[] chars = {'\n'}, starts = {0, 1};
    int bytes = 1, columns = 80, lines = 24, cursor, anchor, first = -1, last = -1, top, scrollX;
    int xscrollcommand, yscrollcommand;
    String wrap = "char";
    boolean modified;
    private int layoutWidth = -1, maxWidth, goalX = -1;
    private String layoutWrap = "", lastX = "", lastY = "";
    private final List<Row> rows = new ArrayList<>();
    static final class Row {
        final int start, end, width;
        Row(int start, int end, int width) { this.start=start; this.end=end; this.width=width; }
    }
    private static final Pattern BASE = Pattern.compile("^(end|insert|sel\\.first|sel\\.last|[0-9]+\\.(?:[0-9]+|end)|@-?[0-9]+,-?[0-9]+)");
    private static final Pattern DELTA = Pattern.compile("^([+-])\\s*([0-9]+)\\s*(chars|char|c)(?![a-zA-Z])");

    public Text(Frame parent) { super(parent.tree, parent, Kind.TEXT); background=0xFFFFFF; border=1; padx=pady=2; }
    public int length() { return chars.length; }
    private int clamp(long at) { return (int)Math.max(0, Math.min(chars.length, at)); }
    public String get(int a, int b) { a=clamp(a); b=Math.max(a,clamp(b)); return new String(chars,a,b-a); }
    public String content() { return get(0,length()); }
    public boolean selectionPresent() { return first>=0 && last>first; }
    public String selectedText() { return selectionPresent()?get(first,last):""; }
    public void selection(int a,int b) { alive(); first=clamp(a); last=clamp(b); if(last<=first)first=last=-1; tree.changed(false); }
    public void selectionClear() { first=last=-1; tree.changed(false); }
    private int line(int at) { int n=Arrays.binarySearch(starts,clamp(at)); return n>=0?n:-n-2; }
    public String index(int at) { at=clamp(at); int row=line(at); return (row+1)+"."+(at-starts[row]); }
    public int index(Object raw) {
        String value=raw instanceof Number?raw.toString():TkOptions.text(raw);
        if(value.length()>256)throw new IllegalArgumentException("Text index too long");
        value=value.trim(); Matcher base=BASE.matcher(value);
        if(!base.find())throw new IllegalArgumentException("invalid Text index: "+value);
        String b=base.group(); int at;
        switch(b) {
            case "end": at=length(); break;
            case "insert": at=cursor; break;
            case "sel.first": if(!selectionPresent())throw new IllegalArgumentException("no selection"); at=first; break;
            case "sel.last": if(!selectionPresent())throw new IllegalArgumentException("no selection"); at=last; break;
            default:
                if(b.startsWith("@")) { String[] xy=b.substring(1).split(","); bounds(); at=at(Integer.parseInt(xy[0]),Integer.parseInt(xy[1]),false); }
                else { String[] rc=b.split("\\."); long row=Long.parseLong(rc[0]);
                    if(row<1)at=0;
                    else if(row>=starts.length)at=length();
                    else { int r=(int)row-1; long col=rc[1].equals("end")?Long.MAX_VALUE:Long.parseLong(rc[1]); at=starts[r]+(int)Math.min(starts[r+1]-starts[r]-1,col); }
                }
        }
        String rest=value.substring(base.end()).trim();
        while(!rest.isEmpty()) {
            Matcher delta=DELTA.matcher(rest);
            if(delta.find()) { long n=Math.min(Integer.MAX_VALUE,Long.parseLong(delta.group(2))); at=clamp(at+(delta.group(1).equals("+")?n:-n)); rest=rest.substring(delta.end()).trim(); }
            else if(rest.startsWith("linestart")) { at=starts[line(at)]; rest=rest.substring(9).trim(); }
            else if(rest.startsWith("lineend")) { int r=line(at); at=r+1<starts.length?starts[r+1]-1:length(); rest=rest.substring(7).trim(); }
            else throw new IllegalArgumentException("unsupported Text index modifier: "+rest);
        }
        return at;
    }
    private int adjust(int value,int a,int b,int added,boolean right) {
        if(value<a || value==a&&!right)return value;
        return value<b?a+added:value+added-(b-a);
    }
    private void validate(int[] next) {
        if(next.length>MAX_CHARS)throw new IllegalArgumentException("Text character limit exceeded");
        int size=0,count=0;
        for(int cp:next) {
            if(cp<32&&cp!='\n'&&cp!='\t'||cp==127||cp>=0xD800&&cp<=0xDFFF)throw new IllegalArgumentException("Text requires Unicode plain text");
            size+=cp<128?1:cp<2048?2:cp<65536?3:4; if(cp=='\n')count++;
        }
        int total=size; for(TkWidget w:tree.widgets.values())if(w instanceof Text&&w!=this)total+=((Text)w).bytes;
        if(size>MAX_BYTES||total>2*1024*1024||count>MAX_LINES)throw new IllegalArgumentException("Text byte/line budget exceeded");
    }
    private void assign(int[] next) {
        chars=next; bytes=content().getBytes(StandardCharsets.UTF_8).length;
        int count=0; for(int cp:chars)if(cp=='\n')count++;
        starts=new int[count+1]; int n=1; for(int i=0;i<chars.length;i++)if(chars[i]=='\n')starts[n++]=i+1;
        layoutWidth=-1; goalX=-1; modified=true; tree.changed(false);
    }
    private void replace(int a,int b,String value) {
        alive(); if(!enabled)return;
        value=value.replace("\r\n","\n").replace('\r','\n');
        if(value.length()>MAX_CHARS*2)throw new IllegalArgumentException("Text input too long");
        a=Math.min(clamp(a),length()-1); b=Math.max(a,Math.min(clamp(b),length()-1));
        int[] inserted=value.codePoints().toArray();
        if(inserted.length==0&&a==b)return;
        long size=(long)length()-(b-a)+inserted.length;
        if(size>MAX_CHARS)throw new IllegalArgumentException("Text character limit exceeded");
        int[] next=new int[(int)size];
        System.arraycopy(chars,0,next,0,a); System.arraycopy(inserted,0,next,a,inserted.length); System.arraycopy(chars,b,next,a+inserted.length,length()-b);
        validate(next); // No deletion or mark movement before the whole edit has been accepted.
        cursor=Math.min(next.length-1,adjust(cursor,a,b,inserted.length,true)); anchor=Math.min(next.length-1,adjust(anchor,a,b,inserted.length,true));
        if(selectionPresent()) { first=adjust(first,a,b,inserted.length,true); last=adjust(last,a,b,inserted.length,false); if(last<=first)first=last=-1; }
        assign(next);
    }
    public void insert(int at,String value) { replace(at,at,value); }
    public void delete(int a,int b) {
        a=clamp(a);b=clamp(b);if(b<=a)return;
        // Tk removes the preceding separator when deleting complete final lines through end.
        if(b==length()&&a>0&&a<length()&&chars[a-1]=='\n')a--;
        replace(a,b,"");
    }
    public void cursor(int at) { alive(); cursor=Math.min(clamp(at),length()-1); goalX=-1; tree.changed(false); }
    int pixels() { return Math.max(1,bounds.width-2*(padx+border)); }
    int page() { return Math.max(1,(bounds.height-2*(pady+border))/16); }
    private int advance(int cp,int x) { return cp=='\t'?64-Math.floorMod(x,64):Math.max(0,TkFontMetrics.advance(cp)); }
    private int width(int a,int b) { int x=0; for(int i=a;i<b;i++)x+=advance(chars[i],x); return x; }
    private void layout() {
        int pixels=pixels(); if(layoutWidth==pixels&&layoutWrap.equals(wrap))return;
        rows.clear(); maxWidth=0;
        for(int l=0;l<starts.length-1;l++) {
            int end=starts[l+1]-1,start=starts[l];
            if(start==end) { rows.add(new Row(start,end,0)); continue; }
            while(start<end) {
                int at=start,x=0,brk=-1,brkWidth=0;
                while(at<end) {
                    int step=advance(chars[at],x);
                    if(!wrap.equals("none")&&x+step>pixels&&at>start)break;
                    x+=step; at++;
                    if(Character.isWhitespace(chars[at-1])) { brk=at; brkWidth=x; }
                }
                if(wrap.equals("word")&&at<end&&brk>start) { at=brk; x=brkWidth; }
                rows.add(new Row(start,at,x)); maxWidth=Math.max(maxWidth,x); start=at;
            }
        }
        layoutWidth=pixels; layoutWrap=wrap;
    }
    private int rowAt(int at) {
        layout(); at=Math.min(clamp(at),length()-1); int lo=0,hi=rows.size()-1;
        while(lo<hi) { int mid=(lo+hi+1)/2; if(rows.get(mid).start<=at)lo=mid;else hi=mid-1; } return lo;
    }
    private void clampView() { layout(); top=Math.max(0,Math.min(top,Math.max(0,rows.size()-page()))); scrollX=wrap.equals("none")?Math.max(0,Math.min(scrollX,Math.max(0,maxWidth-pixels()+1))):0; }
    private Object[] fractions(boolean x) {
        int total=x?maxWidth:rows.size(),visible=x?pixels():page(),start=x?scrollX:top;
        return total==0?new Object[]{0.0,1.0}:new Object[]{Math.min(1.0,start/(double)total),Math.min(1.0,(start+visible)/(double)total)};
    }
    void viewport() {
        clampView();
        for(boolean x:new boolean[]{true,false}) {
            Object[] f=fractions(x); int cb=x?xscrollcommand:yscrollcommand; String key=cb+":"+f[0]+":"+f[1];
            if(!key.equals(x?lastX:lastY)) { if(x)lastX=key;else lastY=key; tree.queue(this,cb,f); }
        }
    }
    public Object[] view(boolean x) { bounds(); clampView(); return fractions(x); }
    public void moveto(boolean x,double fraction) {
        if(!Double.isFinite(fraction))throw new IllegalArgumentException("finite fraction required"); bounds(); layout();
        int pos=(int)(Math.max(0,Math.min(1,fraction))*(x?maxWidth:rows.size())); if(x)scrollX=pos;else top=pos; clampView(); tree.changed(false);
    }
    public void scroll(boolean x,int amount,boolean pages) {
        bounds(); layout(); long pos=(x?scrollX:top)+(long)amount*(pages?x?pixels():Math.max(1,page()-1):x?8:1);
        if(x)scrollX=(int)Math.max(0,Math.min(Integer.MAX_VALUE,pos));else top=(int)Math.max(0,Math.min(Integer.MAX_VALUE,pos)); clampView(); tree.changed(false);
    }
    public void see(int at) {
        bounds(); int row=rowAt(at); if(row<top)top=row;else if(row>=top+page())top=row-page()+1;
        if(wrap.equals("none")) { Row r=rows.get(row); int x=width(r.start,Math.min(at,r.end)); if(x<scrollX)scrollX=x;else if(x>=scrollX+pixels())scrollX=x-pixels()+1; }
        clampView(); tree.changed(false);
    }
    private int atX(Row row,int x,boolean nearest) {
        int px=0,at=row.start; while(at<row.end) { int step=advance(chars[at],px); if(x<px+(nearest?step/2:step))break; px+=step;at++; } return at;
    }
    int at(int localX,int localY,boolean nearest) {
        clampView(); int row=Math.max(0,Math.min(rows.size()-1,top+Math.floorDiv(localY-pady-border,16)));
        return atX(rows.get(row),localX-padx-border+scrollX,nearest);
    }
    void press(int x,int y,int modifiers) {
        int at=at(x-bounds.x,y-bounds.y,true);
        if((modifiers&1)!=0)selection(Math.min(anchor,at),Math.max(anchor,at));else {selectionClear();anchor=at;}
        cursor(at); see(cursor);
    }
    void drag(int x,int y) {
        if(y<bounds.y+pady+border)top=Math.max(0,top-1);else if(y>=bounds.y+bounds.height-pady-border)top++;
        if(wrap.equals("none")) { if(x<bounds.x+padx+border)scrollX=Math.max(0,scrollX-16);else if(x>=bounds.x+bounds.width-padx-border)scrollX+=16; }
        int at=at(x-bounds.x,y-bounds.y,true); selection(Math.min(anchor,at),Math.max(anchor,at)); cursor(at); see(cursor);
    }
    private void move(int at,boolean extend,boolean vertical) {
        at=Math.min(clamp(at),length()-1); int goal=goalX;
        if(extend) {if(!selectionPresent())anchor=cursor;selection(Math.min(anchor,at),Math.max(anchor,at));}else {selectionClear();anchor=at;}
        cursor(at); if(vertical)goalX=goal; see(at);
    }
    private boolean word(int at) { return Character.isLetterOrDigit(chars[at])||chars[at]=='_'; }
    private int wordMove(int at,boolean right) {
        if(right) { if(at<length()-1) {boolean word=word(at++);while(word&&at<length()-1&&word(at))at++;}while(at<length()-1&&Character.isWhitespace(chars[at]))at++; }
        else {while(at>0&&Character.isWhitespace(chars[at-1]))at--;if(at>0) {boolean word=word(--at);while(word&&at>0&&word(at-1))at--;}}
        return at;
    }
    void key(int code,int character,int modifiers) {
        // AltGr (Windows: Ctrl+Alt) and Option (macOS: Alt) type characters; only Ctrl
        // without Alt is a shortcut modifier.
        boolean shift=(modifiers&1)!=0,ctrl=(modifiers&2)!=0&&(modifiers&4)==0;
        if(ctrl&&code==30) {anchor=0;selection(0,length());cursor(length()-1);see(cursor);return;}
        if(code==203||code==205) {boolean right=code==205;move(ctrl?wordMove(cursor,right):!shift&&selectionPresent()?right?last:first:cursor+(right?1:-1),shift,false);return;}
        if(code==199||code==207) {int row=line(cursor);move(ctrl?code==199?0:length()-1:code==199?starts[row]:starts[row+1]-1,shift,false);return;}
        if(code==200||code==208||code==201||code==209) {
            int r=rowAt(cursor); if(goalX<0)goalX=width(rows.get(r).start,cursor);
            int step=code==201||code==209?Math.max(1,page()-1):1; if(code==200||code==201)step=-step;
            move(atX(rows.get(Math.max(0,Math.min(rows.size()-1,r+step))),goalX,true),shift,true);return;
        }
        if(!enabled)return;
        if(code==14||code==211) {
            if(code==211&&shift)return;
            if(selectionPresent())delete(first,last);
            else if(code==14)delete(ctrl?wordMove(cursor,false):cursor-1,cursor);
            else delete(cursor,ctrl?wordMove(cursor,true):cursor+1);
            see(cursor);return;
        }
        if(!ctrl&&(code==28||code==156))paste("\n");
        else if(!ctrl&&code==15)paste("\t");
        else if(!ctrl&&character>=32&&character<=0x10FFFF&&character!=127&&(character<0xD800||character>0xDFFF))paste(new String(Character.toChars(character)));
    }
    void paste(String value) {
        if(!enabled)return;
        int a=selectionPresent()?first:cursor,b=selectionPresent()?last:cursor;
        String accepted=fit(value,a,b);
        if(accepted.isEmpty()&&!value.isEmpty())return; // Nothing insertable: keep the selection, like Entry.
        replace(a,b,accepted); selectionClear(); anchor=cursor; see(cursor);
    }
    private static int utf8(int cp) { return cp<0x80?1:cp<0x800?2:cp<0x10000?3:4; }
    /**
     * The longest prefix of user input that keeps this Text within its budgets once it
     * replaces [a,b), with control characters other than tab and newline dropped. Like
     * Entry, a paste that does not fit inserts what fits instead of being rejected whole
     * (rejecting threw out of the input handler for a stray control character).
     */
    private String fit(String value,int a,int b) {
        value=value.replace("\r\n","\n").replace('\r','\n');
        a=Math.min(clamp(a),length()-1); b=Math.max(a,Math.min(clamp(b),length()-1));
        int removedBytes=0,removedLines=0;
        for(int i=a;i<b;i++) { removedBytes+=utf8(chars[i]); if(chars[i]=='\n')removedLines++; }
        int others=0; for(TkWidget w:tree.widgets.values())if(w instanceof Text&&w!=this)others+=((Text)w).bytes;
        long keptBytes=bytes-removedBytes, roomChars=MAX_CHARS-(length()-(long)(b-a));
        long roomBytes=Math.min(MAX_BYTES-keptBytes,2L*1024*1024-others-keptBytes), roomLines=MAX_LINES-((starts.length-1)-removedLines);
        StringBuilder out=new StringBuilder();
        for(int i=0;i<value.length();) {
            int cp=value.codePointAt(i); i+=Character.charCount(cp);
            if(cp<32&&cp!='\n'&&cp!='\t'||cp==127||cp>=0xD800&&cp<=0xDFFF)continue;
            int size=utf8(cp);
            if(roomChars<1||roomBytes<size||cp=='\n'&&roomLines<1)break;
            out.appendCodePoint(cp); roomChars--; roomBytes-=size; if(cp=='\n')roomLines--;
        }
        return out.toString();
    }
    void paint(List<TkDisplayList.Item> out,int state) {
        viewport(); int x=bounds.x+padx+border,y=bounds.y+pady+border,w=pixels();
        TkRect inner=new TkRect(x,y,w,Math.max(0,bounds.height-2*(pady+border))).intersect(clip);
        int caretRow=rowAt(cursor);
        for(int n=top;n<rows.size()&&y<inner.y+inner.height;n++,y+=16) {
            Row row=rows.get(n); TkRect rect=new TkRect(x,y,w,16),clipped=rect.intersect(inner); if(clipped.empty())continue;
            int px=0,glyphs=0; StringBuilder visible=new StringBuilder(); int tx=x-scrollX;
            int paintLeft=scrollX+clipped.x-x,paintRight=paintLeft+clipped.width;
            // Expand tabs for the existing font renderer and send only horizontal viewport text.
            for(int i=row.start;i<row.end;i++) {
                int step=advance(chars[i],px);
                if(px+step>paintLeft&&px<paintRight) {
                    if(visible.length()==0)tx=x+px-scrollX;
                    if(chars[i]=='\t')for(int s=0;s<step;s+=8)visible.append(' ');else visible.appendCodePoint(chars[i]);
                    if(++glyphs>=512)break; // Bound degenerate runs of zero-width marks as well as visible glyphs.
                }
                px+=step;if(px>=paintRight)break;
            }
            int sx=0,sw=0;
            if(selectionPresent()&&last>row.start&&first<=row.end) {
                int left=x-scrollX+width(row.start,Math.min(row.end,Math.max(row.start,first)));
                int right=x-scrollX+width(row.start,Math.min(row.end,last));
                if(last>row.end&&row.end<length()&&chars[row.end]=='\n')right+=8;
                sx=Math.max(clipped.x,left); sw=Math.max(0,Math.min(clipped.x+clipped.width,right)-sx); if(sw==0)sx=0;
            }
            int caret=(state&TkDisplayList.FOCUSED)!=0&&enabled&&n==caretRow?x-scrollX+width(row.start,cursor):-1;
            if(caret<clipped.x||caret>=clipped.x+clipped.width)caret=-1;
            out.add(new TkDisplayList.Item(id,n+1,Kind.TEXT,rect,clipped,background,foreground,0,state,tx,y,visible.toString(),caret,sx,sw));
        }
    }
    NBTTagCompound save() {
        NBTTagCompound n=new NBTTagCompound(); n.setByteArray("content",content().getBytes(StandardCharsets.UTF_8));
        n.setString("wrap",wrap); n.setBoolean("modified",modified);
        for(String key:new String[]{"columns","lines","cursor","anchor","first","last","top","scrollX","xscrollcommand","yscrollcommand"}) {
            int value;
            switch(key) {case "columns":value=columns;break;case "lines":value=lines;break;case "cursor":value=cursor;break;case "anchor":value=anchor;break;case "first":value=first;break;case "last":value=last;break;case "top":value=top;break;case "scrollX":value=scrollX;break;case "xscrollcommand":value=xscrollcommand;break;default:value=yscrollcommand;}
            n.setInteger(key,value);
        }
        return n;
    }
    void load(NBTTagCompound n) {
        byte[] data=n.getByteArray("content"); if(data.length>MAX_BYTES)throw new IllegalArgumentException("saved Text too large");
        String content;
        try {content=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString();}catch(java.nio.charset.CharacterCodingException bad){throw new IllegalArgumentException("invalid saved Text UTF-8",bad);}
        int[] next=content.codePoints().toArray(); if(next.length==0||next[next.length-1]!='\n')throw new IllegalArgumentException("missing final Text newline");
        validate(next); assign(next); wrap=n.getString("wrap"); if(!Arrays.asList("none","char","word").contains(wrap))throw new IllegalArgumentException("invalid wrap");
        columns=TkOptions.integer(n.getInteger("columns"),1,1024); lines=TkOptions.integer(n.getInteger("lines"),1,512);
        cursor=TkOptions.integer(n.getInteger("cursor"),0,length()-1);anchor=TkOptions.integer(n.getInteger("anchor"),0,length()-1);
        first=n.getInteger("first");last=n.getInteger("last");if(!(first==-1&&last==-1||first>=0&&last>first&&last<=length()))throw new IllegalArgumentException("invalid Text selection");
        top=Math.max(0,Math.min(MAX_CHARS,n.getInteger("top")));scrollX=Math.max(0,Math.min(MAX_CHARS*64,n.getInteger("scrollX")));
        xscrollcommand=TkOptions.integer(n.getInteger("xscrollcommand"),0,Integer.MAX_VALUE);yscrollcommand=TkOptions.integer(n.getInteger("yscrollcommand"),0,Integer.MAX_VALUE);
        modified=n.getBoolean("modified");
    }
}
