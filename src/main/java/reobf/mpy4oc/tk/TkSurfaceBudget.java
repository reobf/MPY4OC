package reobf.mpy4oc.tk;

/** Bounded burst plus sustained payload budget per surface viewer, in server ticks. */
public final class TkSurfaceBudget {
    public static final int BURST=1048576, PER_TICK=16384;
    private long tick;
    private int available=BURST;
    public TkSurfaceBudget(long tick){this.tick=tick;}
    public boolean take(long now,int bytes) {
        if(now>tick) {available=(int)Math.min(BURST,available+Math.min(64,now-tick)*(long)PER_TICK);tick=now;}
        if(bytes<0||bytes>available)return false;
        available-=bytes;return true;
    }
}
