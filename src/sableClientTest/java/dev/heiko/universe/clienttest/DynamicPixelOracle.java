package dev.heiko.universe.clienttest;

import dev.heiko.universe.clienttest.DynamicGeometryMapper.FrameGeometry;
import dev.heiko.universe.clienttest.DynamicGeometryMapper.Marker;
import dev.heiko.universe.clienttest.DynamicGeometryMapper.PixelMask;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Frozen offline image gates. Reads genuine PNGs against independently projected depth masks. */
public final class DynamicPixelOracle {
    public record Frame(String file,int stage,BufferedImage image,FrameGeometry geometry){}
    public record Detection(int expectedPixels,int pixels,double occupancy,double expectedX,double expectedY,
            double x,double y,double centroidError){}
    public record Measurement(String file,int stage,Map<Marker,Detection> markers,Detection landmark,
            int hiddenPixels,int hiddenMagenta,int hiddenLeaks,int wallPixels,int wallMagenta,int wallLeaks){}
    private DynamicPixelOracle(){}
    public static List<Measurement> verify(List<Frame> frames){
        if(frames.size()!=12)throw new IllegalStateException("Exactly three held PNGs per four stages required");
        List<Measurement> result=new ArrayList<>();int[] counts=new int[4];boolean[] occlusion=new boolean[4];
        for(var frame:frames){
            if(frame.stage()<0||frame.stage()>3||frame.image().getWidth()!=960||frame.image().getHeight()!=540)
                throw new IllegalStateException("Framebuffer/stage mismatch");
            counts[frame.stage()]++;
            Map<Marker,Detection> detections=new EnumMap<>(Marker.class);
            for(var entry:frame.geometry().markers().entrySet()){
                PixelMask mask=entry.getValue().visibleInterior();
                if(mask.areaPixels()<12)continue;
                detections.put(entry.getKey(),detect(frame.image(),mask,entry.getKey(),false,12,.70,12));
            }
            if(detections.size()<2)throw new IllegalStateException("At least two eligible real markers required in "+frame.file());
            var landmark=detect(frame.image(),frame.geometry().landmark().interior(),null,true,12,.70,4);
            int[] wall=coverage(frame.image(),frame.geometry().wallInterior());
            int[] hidden=coverage(frame.image(),frame.geometry().hiddenMarkerWallInterior());
            if(hidden[0]>=80){
                if(wall[0]<100||wall[1]<.90*wall[0]||wall[2]>2||hidden[1]<.90*hidden[0]||hidden[2]>2)
                    throw new IllegalStateException("Opaque wall overlap/depth coverage failed in "+frame.file());
                occlusion[frame.stage()]=true;
            }
            result.add(new Measurement(frame.file(),frame.stage(),detections,landmark,hidden[0],hidden[1],hidden[2],wall[0],wall[1],wall[2]));
        }
        int occlusionStages=0;for(int stage=0;stage<4;stage++){if(counts[stage]!=3)throw new IllegalStateException("Missing held stage");if(occlusion[stage])occlusionStages++;}
        if(occlusionStages<2)throw new IllegalStateException("Nonvacuous hidden marker overlap required in two stages");
        var first=result.stream().filter(m->m.stage()==0).toList();var last=result.stream().filter(m->m.stage()==3).toList();
        int moved=0;
        for(var marker:Marker.values()){
            if(first.stream().anyMatch(m->!m.markers().containsKey(marker))||last.stream().anyMatch(m->!m.markers().containsKey(marker)))continue;
            boolean enough=true;for(var a:first)for(var b:last){var x=a.markers().get(marker);var y=b.markers().get(marker);
                if(Math.hypot(x.x()-y.x(),x.y()-y.y())<20)enough=false;}
            if(enough)moved++;
        }
        if(moved<2)throw new IllegalStateException("Two identical markers must move at least 20px between all endpoint PNGs");
        boolean basisPassed=false;
        for(var from:Marker.values())for(var to:Marker.values()){
            if(from.ordinal()>=to.ordinal())continue;
            double[] predicted=new double[4],observed=new double[4];boolean[] valid=new boolean[4];
            for(int stage=0;stage<4;stage++){
                final int s=stage;var group=result.stream().filter(m->m.stage()==s).toList();boolean ok=true;double es=0,ec=0,os=0,oc=0;
                for(var m:group){if(!m.markers().containsKey(from)||!m.markers().containsKey(to)){ok=false;break;}
                    var a=m.markers().get(from);var b=m.markers().get(to);
                    if(Math.hypot(b.expectedX()-a.expectedX(),b.expectedY()-a.expectedY())<1||Math.hypot(b.x()-a.x(),b.y()-a.y())<1){ok=false;break;}
                    double expected=Math.toDegrees(Math.atan2(b.expectedY()-a.expectedY(),b.expectedX()-a.expectedX()));
                    double actual=Math.toDegrees(Math.atan2(b.y()-a.y(),b.x()-a.x()));
                    if(Math.abs(DynamicGeometryMapper.angleDifferenceDegrees(expected,actual))>8){ok=false;break;}
                    es+=Math.sin(Math.toRadians(expected));ec+=Math.cos(Math.toRadians(expected));
                    os+=Math.sin(Math.toRadians(actual));oc+=Math.cos(Math.toRadians(actual));
                }
                if(ok&&(Math.hypot(es,ec)<2.9||Math.hypot(os,oc)<2.9))ok=false;
                valid[stage]=ok;if(ok){predicted[stage]=Math.toDegrees(Math.atan2(es,ec));observed[stage]=Math.toDegrees(Math.atan2(os,oc));}
            }
            int stages=0;for(boolean ok:valid)if(ok)stages++;
            if(stages>=3&&valid[0]&&valid[3]&&Math.abs(DynamicGeometryMapper.angleDifferenceDegrees(predicted[0],predicted[3]))>=8
                    &&Math.abs(DynamicGeometryMapper.angleDifferenceDegrees(observed[0],observed[3]))>=8)basisPassed=true;
        }
        if(!basisPassed)throw new IllegalStateException("Same visible marker basis must rotate >=8deg with predicted agreement in >=3 stages");
        return List.copyOf(result);
    }
    private static Detection detect(BufferedImage image,PixelMask mask,Marker marker,boolean cyan,int minimum,double fraction,double tolerance){
        if(mask.areaPixels()<minimum)throw new IllegalStateException("Insufficient projected area for "+(cyan?"landmark":marker));
        var bounds=mask.iterationBounds();int pixels=0;double sx=0,sy=0;
        for(int y=bounds.y();y<bounds.y()+bounds.height();y++)for(int x=bounds.x();x<bounds.x()+bounds.width();x++){
            if(!mask.contains(x,y))continue;
            if(cyan?cyan(image.getRGB(x,y)):marker(image.getRGB(x,y))==marker){pixels++;sx+=x+.5;sy+=y+.5;}
        }
        if(pixels<minimum||pixels<fraction*mask.areaPixels())throw new IllegalStateException("Insufficient actual surface occupancy for "+(cyan?"landmark":marker)+": "+pixels+"/"+mask.areaPixels());
        var expected=mask.centroid();double x=sx/pixels,y=sy/pixels,error=Math.hypot(x-expected.x(),y-expected.y());
        if(!Double.isFinite(error)||error>tolerance)throw new IllegalStateException("Projection centroid disagreement: "+error);
        return new Detection(mask.areaPixels(),pixels,(double)pixels/mask.areaPixels(),expected.x(),expected.y(),x,y,error);
    }
    private static int[] coverage(BufferedImage image,PixelMask mask){
        var bounds=mask.iterationBounds();int pixels=0,magenta=0,leaks=0;
        for(int y=bounds.y();y<bounds.y()+bounds.height();y++)for(int x=bounds.x();x<bounds.x()+bounds.width();x++){
            if(!mask.contains(x,y))continue;int rgb=image.getRGB(x,y);pixels++;if(magenta(rgb))magenta++;if(marker(rgb)!=null)leaks++;
        }
        return new int[]{pixels,magenta,leaks};
    }
    private static Marker marker(int rgb){
        double r=((rgb>>>16)&255)/255.0,g=((rgb>>>8)&255)/255.0,b=(rgb&255)/255.0;
        if(Math.max(r,Math.max(g,b))<.10)return null;
        if(r>1.65*g&&r>1.65*b)return Marker.RED;
        if(b>1.35*r&&b>1.20*g&&r>0&&g/r<1.5)return Marker.BLUE;
        if(g>1.30*r&&g>1.65*b)return Marker.LIME;
        if(r>1.65*b&&g>1.65*b&&r/Math.max(g,.001)>.65&&r/Math.max(g,.001)<1.65)return Marker.YELLOW;return null;
    }
    private static boolean magenta(int rgb){double r=(rgb>>>16)&255,g=(rgb>>>8)&255,b=rgb&255;return r>25&&b>20&&r>1.35*g&&b>1.35*g;}
    private static boolean cyan(int rgb){double r=(rgb>>>16)&255,g=(rgb>>>8)&255,b=rgb&255;return Math.max(g,b)>25&&r<.60*Math.min(g,b)&&g>.75*b&&g<1.30*b;}
}
