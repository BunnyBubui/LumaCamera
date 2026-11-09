package th.ogay.luma;

import org.junit.Test;
import static org.junit.Assert.*;

public class LinearRawFusionTest {
    @Test public void averagesBeforeTheSrgbTransferCurve() {
        int w=64,h=48;float[] a=new float[w*h*3],b=new float[a.length];
        java.util.Arrays.fill(a,.2f);java.util.Arrays.fill(b,.24f);
        LinearRawFusion f=new LinearRawFusion(w,h,a);f.add(b);
        int[] output=f.finish();assertEquals(LinearRawFusion.encode(.22f),output[120]&255);
        assertEquals(2,f.used());
    }
    @Test public void changingSubjectUsesTheReferenceRegion(){
        int w=96,h=64;float[] a=new float[w*h*3],b=new float[a.length];java.util.Arrays.fill(a,.2f);java.util.Arrays.fill(b,.2f);
        for(int y=25;y<35;y++)for(int x=40;x<50;x++)for(int c=0;c<3;c++)b[(y*w+x)*3+c]=.9f;
        LinearRawFusion f=new LinearRawFusion(w,h,a);f.add(b);int[] out=f.finish();assertEquals(LinearRawFusion.encode(.2f),out[30*w+45]&255);
    }
    @Test public void encodesBlackWhiteAndSrgbMidpoint(){assertEquals(0,LinearRawFusion.encode(-1));assertEquals(255,LinearRawFusion.encode(2));assertEquals(188,LinearRawFusion.encode(.5f));}
    @Test(expected=IllegalArgumentException.class)public void rejectsDifferentSensorGrids(){new LinearRawFusion(8,8,new float[8*8*3]).add(new float[9]);}
}
