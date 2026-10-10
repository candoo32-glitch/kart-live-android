package com.kartgarden.live;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import com.kartgarden.live.protocol.Orientation;
import java.util.Locale;

/** Shared quaternion feeds the horizon and projected 3D kart. */
public final class InstrumentView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private double[] q=Orientation.identity();
    private String source="Nintendo",detail="Waiting for IMU";
    private boolean fresh;
    public InstrumentView(Context c){super(c);setContentDescription("Kart artificial horizon and three dimensional orientation");}
    public void update(double[] value,String mode,String status,boolean fresh){q=value.clone();source=mode;detail=status;this.fresh=fresh;invalidate();}
    private void text(Canvas c,String text,float x,float y,float size,int color){p.setColor(color);p.setTextSize(size);p.setStyle(Paint.Style.FILL);c.drawText(text,x,y,p);}
    @Override protected void onDraw(Canvas c){
        float w=getWidth(),h=getHeight(),pad=16*density(),size=12*density();
        p.setColor(0xff1c302b);c.drawRoundRect(0,0,w,h,18,18,p);
        text(c,"INSTRUMENTS · "+source,pad,pad+size,size,0xff88e2b3);
        double x=q[0],y=q[1],z=q[2],s=q[3];
        // Same native X-right/Y-rear/Z-down basis as Swift: scene (X,-Z,Y).
        double pitch=Math.asin(Math.max(-1,Math.min(1,2*(s*x+y*z))));
        double roll=Math.atan2(2*(s*y-x*z),1-2*(x*x+y*y));
        double yaw=Math.atan2(2*(s*z+x*y),1-2*(y*y+z*z));
        float radius=Math.min(w*.37f,h*.23f),cx=w/2,cy=h*.32f;
        c.save();Path circle=new Path();circle.addCircle(cx,cy,radius,Path.Direction.CW);c.clipPath(circle);c.translate(cx,cy);c.rotate((float)-Math.toDegrees(roll));
        float dy=(float)Math.toDegrees(pitch)*radius/45; p.setColor(fresh?0xff365a70:0xff334044);c.drawRect(-radius*3,-radius*3,radius*3,dy,p);p.setColor(fresh?0xff52623b:0xff363d35);c.drawRect(-radius*3,dy,radius*3,radius*3,p);
        p.setColor(0xffe5f3e9);p.setStrokeWidth(2);c.drawLine(-radius*2,dy,radius*2,dy,p);
        for(int deg=-30;deg<=30;deg+=10)if(deg!=0){float yy=dy-deg*radius/45;c.drawLine(-radius*.3f,yy,radius*.3f,yy,p);}
        c.restore();p.setColor(0xffffc977);p.setStrokeWidth(3);c.drawLine(cx-radius*.6f,cy,cx-radius*.17f,cy,p);c.drawLine(cx+radius*.17f,cy,cx+radius*.6f,cy,p);c.drawCircle(cx,cy,4,p);
        text(c,String.format(Locale.ROOT,"R %+.1f°  P %+.1f°",Math.toDegrees(roll),Math.toDegrees(pitch)),pad,h*.57f,size,0xffe5f3e9);
        drawKart(c,w/2,h*.74f,Math.min(w*.29f,h*.19f));
        text(c,String.format(Locale.ROOT,"Heading %+.1f° · %s",Math.toDegrees(yaw),fresh?"live":"stale"),pad,h*.91f,size,0xffd4e8df);
        String truncated=detail.length()>37?detail.substring(0,37)+"…":detail;
        text(c,truncated,pad,h*.97f,10*density(),0xffa4bbae);
    }
    private void drawKart(Canvas c,float cx,float cy,float scale){
        double[][] vertices={{-.7,-1,-.2},{.7,-1,-.2},{.7,1,-.2},{-.7,1,-.2},{-.7,-1,.2},{.7,-1,.2},{.7,1,.2},{-.7,1,.2}};
        float[][] points=new float[8][2];
        // A fixed viewing tilt gives depth; the live quaternion rotates the body first.
        for(int i=0;i<8;i++){double[] v=Orientation.rotate(q,vertices[i]);points[i][0]=cx+(float)(v[0]+.25*v[1])*scale;points[i][1]=cy+(float)(v[1]*.45+v[2]) *scale;}
        p.setColor(fresh?0xff88e2b3:0xff63786b);p.setStrokeWidth(3);
        for(int[] edge:new int[][]{{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}})c.drawLine(points[edge[0]][0],points[edge[0]][1],points[edge[1]][0],points[edge[1]][1],p);
        p.setColor(0xffffc977);c.drawLine(points[0][0],points[0][1],points[1][0],points[1][1],p);
        for(int i=0;i<4;i++){p.setColor(0xff101b19);c.drawCircle(points[i][0],points[i][1],7,p);p.setColor(0xff88e2b3);p.setStyle(Paint.Style.STROKE);c.drawCircle(points[i][0],points[i][1],7,p);p.setStyle(Paint.Style.FILL);}
    }
    private float density(){return getResources().getDisplayMetrics().density;}
}
