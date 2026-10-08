package reobf.mpy4oc.tk.client;

import java.util.*;
import cpw.mods.fml.relauncher.*;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.*;
import reobf.mpy4oc.tk.TkVector;

/** Local Canvas coordinates; scissoring and transforms belong to the display renderer. */
@SideOnly(Side.CLIENT)
final class TkVectorRenderer {
    private static final Map<TkVector,List<Primitive>> polygons = new WeakHashMap<>();
    private static final class Primitive { int mode; final List<double[]> vertices = new ArrayList<>(); }
    static void draw(TkVector v, int x, int y) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(x,y,0);GL11.glDisable(GL11.GL_TEXTURE_2D);GL11.glDisable(GL11.GL_CULL_FACE);
            double[] p=v.coords(); boolean closed=true;
            switch(v.shape) {
                case TEXT:return;
                case LINE:closed=false;break;
                case RECTANGLE:p=new double[]{p[0],p[1],p[2],p[1],p[2],p[3],p[0],p[3]};break;
                case OVAL:
                    double cx=(p[0]+p[2])/2,cy=(p[1]+p[3])/2,rx=Math.abs(p[2]-p[0])/2,ry=Math.abs(p[3]-p[1])/2;
                    p=new double[192];for(int i=0;i<96;i++){double a=i*Math.PI/48;p[2*i]=cx+Math.cos(a)*rx;p[2*i+1]=cy+Math.sin(a)*ry;}break;
                default:break;
            }
            if(closed&&v.fill>=0) {
                color(v.fill);
                if(v.shape==TkVector.Shape.POLYGON) {
                    List<Primitive> mesh=polygons.get(v);if(mesh==null){mesh=tessellate(p);polygons.put(v,mesh);}
                    for(Primitive part:mesh){GL11.glBegin(part.mode);for(double[] a:part.vertices)GL11.glVertex2d(a[0],a[1]);GL11.glEnd();}
                } else {GL11.glBegin(GL11.GL_TRIANGLE_FAN);for(int i=0;i<p.length;i+=2)GL11.glVertex2d(p[i],p[i+1]);GL11.glEnd();}
            }
            int stroke=closed?v.outline:v.fill;
            if(stroke>=0){color(stroke);stroke(p,Math.max(1,v.width),closed);}
        } finally {GL11.glPopMatrix();GL11.glPopAttrib();}
    }
    private static void color(int c){GL11.glColor4f(((c>>16)&255)/255f,((c>>8)&255)/255f,(c&255)/255f,1);}
    private static void stroke(double[] p,double width,boolean closed) {
        int count=p.length/2,edges=count-(closed?0:1);double radius=width/2;
        GL11.glBegin(GL11.GL_QUADS);
        for(int i=0;i<edges;i++){int a=i*2,b=((i+1)%count)*2;double dx=p[b]-p[a],dy=p[b+1]-p[a+1],len=Math.hypot(dx,dy);if(len==0)continue;
            double nx=-dy*radius/len,ny=dx*radius/len;
            GL11.glVertex2d(p[a]+nx,p[a+1]+ny);GL11.glVertex2d(p[a]-nx,p[a+1]-ny);GL11.glVertex2d(p[b]-nx,p[b+1]-ny);GL11.glVertex2d(p[b]+nx,p[b+1]+ny);}
        GL11.glEnd();
        // Round joins and caps keep thick polylines continuous at acute angles.
        for(int i=0;i<count;i++){GL11.glBegin(GL11.GL_TRIANGLE_FAN);GL11.glVertex2d(p[i*2],p[i*2+1]);for(int j=0;j<=12;j++){
            double a=j*Math.PI/6;GL11.glVertex2d(p[i*2]+Math.cos(a)*radius,p[i*2+1]+Math.sin(a)*radius);}GL11.glEnd();}
    }
    private static List<Primitive> tessellate(double[] points) {
        final List<Primitive> parts=new ArrayList<>();GLUtessellator tess=GLU.gluNewTess();
        GLUtessellatorCallbackAdapter callback=new GLUtessellatorCallbackAdapter(){
            private Primitive current;
            @Override public void begin(int mode){current=new Primitive();current.mode=mode;parts.add(current);}
            @Override public void vertex(Object value){current.vertices.add((double[])value);}
            @Override public void combine(double[] coords,Object[] data,float[] weights,Object[] out){out[0]=coords.clone();}
            @Override public void error(int code){throw new IllegalArgumentException("Canvas tessellation failed: "+code);}
        };
        try {
            tess.gluTessCallback(GLU.GLU_TESS_BEGIN,callback);tess.gluTessCallback(GLU.GLU_TESS_VERTEX,callback);
            tess.gluTessCallback(GLU.GLU_TESS_COMBINE,callback);tess.gluTessCallback(GLU.GLU_TESS_ERROR,callback);
            tess.gluTessProperty(GLU.GLU_TESS_WINDING_RULE,GLU.GLU_TESS_WINDING_ODD);tess.gluTessNormal(0,0,1);
            tess.gluTessBeginPolygon(null);tess.gluTessBeginContour();
            for(int i=0;i<points.length;i+=2){double[] p={points[i],points[i+1],0};tess.gluTessVertex(p,0,p);}
            tess.gluTessEndContour();tess.gluTessEndPolygon();return parts;
        } finally {tess.gluDeleteTess();}
    }
    private TkVectorRenderer() {}
}
