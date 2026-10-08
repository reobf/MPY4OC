package reobf.mpy4oc.tk;

/** Bounded plain-text subset of the Tk Text widget command. */
final class TkTextApi {
    private TkTextApi() {}
    static Object call(Text t,String action,Object[] a) {
        switch(action) {
            case "index": TkControlApi.count(a,1); return t.index(t.index(a[0]));
            case "get":
                if(a.length<1||a.length>2)throw new IllegalArgumentException("one or two indices required");
                int from=t.index(a[0]); return t.get(from,a.length==1?from+1:t.index(a[1]));
            case "insert": TkControlApi.count(a,2); t.insert(t.index(a[0]),TkOptions.text(a[1])); break;
            case "delete":
                if(a.length<1||a.length>2)throw new IllegalArgumentException("one or two indices required");
                int start=t.index(a[0]); t.delete(start,a.length==1?start+1:t.index(a[1])); break;
            case "mark_set":
                TkControlApi.count(a,2); if(!TkOptions.text(a[0]).equals("insert"))throw new IllegalArgumentException("only the insert mark is supported");
                t.cursor(t.index(a[1])); break;
            case "selection_get": TkControlApi.count(a,0); if(!t.selectionPresent())throw new IllegalArgumentException("no selection"); return t.selectedText();
            case "tag_add": case "tag_remove":
                TkControlApi.count(a,3); if(!TkOptions.text(a[0]).equals("sel"))throw new IllegalArgumentException("Text-lite supports only the sel tag");
                int first=t.index(a[1]),last=t.index(a[2]);
                if(action.equals("tag_add"))t.selection(first,last);
                else if(t.selectionPresent()&&first<t.last&&last>t.first) {
                    // A single contiguous selection: removing its middle keeps the leading part.
                    if(first<=t.first)t.selection(Math.min(last,t.last),t.last);else t.selection(t.first,first);
                }
                break;
            case "tag_ranges":
                TkControlApi.count(a,1); if(!TkOptions.text(a[0]).equals("sel"))throw new IllegalArgumentException("Text-lite supports only the sel tag");
                return t.selectionPresent()?new Object[]{t.index(t.first),t.index(t.last)}:new Object[0];
            case "see": TkControlApi.count(a,1); t.see(t.index(a[0])); break;
            case "compare":
                TkControlApi.count(a,3); int left=t.index(a[0]),right=t.index(a[2]);
                switch(TkOptions.text(a[1])) {case "<":return left<right;case "<=":return left<=right;case "==":return left==right;case "!=":return left!=right;case ">":return left>right;case ">=":return left>=right;default:throw new IllegalArgumentException("invalid comparison");}
            case "edit_modified":
                if(a.length==0)return t.modified; TkControlApi.count(a,1); t.modified=TkOptions.bool(a[0]);t.tree.changed(false);break;
            case "xview": case "yview":
                boolean x=action.equals("xview"); if(a.length==0)return t.view(x);
                String op=TkOptions.text(a[0]);
                if(op.equals("moveto")) {TkControlApi.count(a,2);t.moveto(x,TkControlApi.number(a[1]));}
                else if(op.equals("scroll")) {
                    TkControlApi.count(a,3);String units=TkOptions.text(a[2]);
                    if(!units.equals("units")&&!units.equals("pages"))throw new IllegalArgumentException("units or pages required");
                    t.scroll(x,TkOptions.integer(a[1],Integer.MIN_VALUE,Integer.MAX_VALUE),units.equals("pages"));
                }else throw new IllegalArgumentException("moveto or scroll required");
                break;
            default: throw new IllegalArgumentException("unsupported Text operation: "+action);
        }
        return null;
    }
}
