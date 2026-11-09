package th.ogay.luma;

/** Linear RGB accumulation after Bayer sampling/WB, before the display transfer curve. */
public final class LinearRawFusion {
    private final int width, height;
    private final float[] reference, sums;
    private final short[] counts;
    private int used = 1;
    public LinearRawFusion(int width, int height, float[] rgb) {
        if (rgb.length != width*height*3) throw new IllegalArgumentException("Invalid RGB grid");
        this.width=width; this.height=height; reference=rgb.clone(); sums=rgb.clone();
        counts=new short[width*height]; java.util.Arrays.fill(counts,(short)1);
    }
    public int width(){return width;} public int height(){return height;} public int used(){return used;}
    private int[] thumb(float[] rgb,int w,int h){
        int[] y=new int[w*h];
        for(int row=0;row<h;row++)for(int col=0;col<w;col++){
            int i=((row*height/h)*width+col*width/w)*3;
            y[row*w+col]=(int)(255*Math.pow(Math.max(0,Math.min(1,.2126*rgb[i]+.7152*rgb[i+1]+.0722*rgb[i+2])),1/2.2));
        }return y;
    }
    public void add(float[] rgb){
        if(rgb.length!=reference.length)throw new IllegalArgumentException("Size changed during capture");
        int w=Math.min(192,width),h=Math.max(1,w*height/width);
        FrameFusionEngine.Alignment alignment=FrameFusionEngine.align(thumb(reference,w,h),thumb(rgb,w,h),w,h);
        if(alignment.error>22)return;
        int dx=Math.round(alignment.dx*(float)width/w),dy=Math.round(alignment.dy*(float)height/h);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int xx=x+dx,yy=y+dy;if(xx<0||xx>=width||yy<0||yy>=height)continue;
            int p=y*width+x,q=yy*width+xx;float difference=0;
            for(int c=0;c<3;c++)difference=Math.max(difference,Math.abs(reference[p*3+c]-rgb[q*3+c]));
            if(difference>.18f)continue;
            for(int c=0;c<3;c++)sums[p*3+c]+=rgb[q*3+c];counts[p]++;
        }used++;
    }
    public static int encode(float value){float v=Math.max(0,Math.min(1,value));return Math.round(255*(v<=.0031308f?12.92f*v:1.055f*(float)Math.pow(v,1/2.4)-.055f));}
    public int[] finish(){int[] pixels=new int[width*height];for(int i=0;i<pixels.length;i++)pixels[i]=0xff000000|(encode(sums[i*3]/counts[i])<<16)|(encode(sums[i*3+1]/counts[i])<<8)|encode(sums[i*3+2]/counts[i]);return pixels;}
}
