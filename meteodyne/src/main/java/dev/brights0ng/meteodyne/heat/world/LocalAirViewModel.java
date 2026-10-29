// Written by ChatGPT.
// Updated by ChatGPT: kept automatic lattice positions aligned to world coordinates.
// Updated by ChatGPT: used explicit coverage progress instead of a generic waiting label.
// Updated by Claude: coarsened the lattice of domains larger than one view packet instead of failing the payload.
package dev.brights0ng.meteodyne.heat.world;
import java.util.*;
final class LocalAirViewModel {
    @FunctionalInterface interface LatticePoint { void visit(int x,int y,int z); }
    static List<LocalAirViewNetwork.Point> samples(LocalAirRun.Frame frame,long now,int maxAge,int spacing,boolean worldAnchored) {
        if(spacing<1 || spacing>32) throw new IllegalArgumentException("Lattice spacing must be 1..32 blocks");
        if(frame==null || LocalAirDiagnostics.age(frame,now)>maxAge) return List.of();
        var points=new ArrayList<LocalAirViewNetwork.Point>();
        // A 12-tile domain holds 49,152 cells, more than one packet carries: widen the spacing until the lattice fits.
        int fit=spacing;while(fit<32 && count(frame.bounds,fit,worldAnchored)>LocalAirViewNetwork.MAX_POINTS) fit++;
        lattice(frame.bounds,fit,worldAnchored,(x,y,z)->{
            var sample=LocalAirDiagnostics.sample(frame,now,x+.5,y+.5,z+.5);
            if(sample.availability()==LocalAirDiagnostics.Availability.AIR)
                points.add(new LocalAirViewNetwork.Point(x,y,z,(float)(sample.temperature()-frame.weather.ambient()),(float)sample.x(),(float)sample.y(),(float)sample.z()));
        });
        return points;
    }
    private static long count(LocalAirGeometryCapture.Bounds b,int spacing,boolean worldAnchored) {
        long[] n={0};lattice(b,spacing,worldAnchored,(x,y,z)->n[0]++);return n[0];
    }
    /** Cell centers anchored to the domain minimum; spacing changes sampling only. */
    static void lattice(LocalAirGeometryCapture.Bounds b,int spacing,LatticePoint visitor) {
        lattice(b,spacing,false,visitor);
    }
    static void lattice(LocalAirGeometryCapture.Bounds b,int spacing,boolean worldAnchored,LatticePoint visitor) {
        if(spacing<1 || spacing>32) throw new IllegalArgumentException("Lattice spacing must be 1..32 blocks");
        if(b.sparse()!=null) {
            for(int c=0;c<b.cells();c++) {
                int x=b.cellX(c),y=b.cellY(c),z=b.cellZ(c);
                if((worldAnchored?Math.floorMod((long)b.x()+x,spacing):x%spacing)==0
                        && (worldAnchored?Math.floorMod((long)b.y()+y,spacing):y%spacing)==0
                        && (worldAnchored?Math.floorMod((long)b.z()+z,spacing):z%spacing)==0) visitor.visit(b.x()+x,b.y()+y,b.z()+z);
            }
            return;
        }
        int ox=worldAnchored?(int)Math.floorMod(-(long)b.x(),spacing):0,oy=worldAnchored?(int)Math.floorMod(-(long)b.y(),spacing):0,oz=worldAnchored?(int)Math.floorMod(-(long)b.z(),spacing):0;
        for(int z=oz;z<b.nz();z+=spacing) for(int y=oy;y<b.ny();y+=spacing) for(int x=ox;x<b.nx();x+=spacing)
            visitor.visit(b.x()+x,b.y()+y,b.z()+z);
    }
    record Point(double x,double y,double z) {
        static final Point ZERO=new Point(0,0,0);
        double length() { return Math.sqrt(x*x+y*y+z*z); }
        Point scale(double s) { return new Point(x*s,y*s,z*s); }
        Point add(Point p) { return new Point(x+p.x,y+p.y,z+p.z); }
        Point subtract(Point p) { return add(p.scale(-1)); }
        Point cross(Point p) { return new Point(y*p.z-z*p.y,z*p.x-x*p.z,x*p.y-y*p.x); }
        Point normalize() { return scale(1/length()); }
        double distanceTo(Point p) { return subtract(p).length(); }
    }
    static String label(LocalAirPresentation data) {
        if(data.reading().status()!=LocalAirReading.Status.CURRENT && !data.detail().isEmpty()) return "Trial thermometer: "+data.reading().status()+data.reading().sample().map(s->"; age "+s.ageTicks()+" ticks").orElse("")+" | "+data.detail();
        return label(data.reading());
    }
    static String label(LocalAirReading reading) {
        if(reading.status()!=LocalAirReading.Status.CURRENT) return "Trial thermometer: "+reading.status()+" / "+reading.reason()+
                reading.sample().map(s->"; age "+s.ageTicks()+" ticks").orElse("")+"; no current reading";
        var s=reading.currentSample().orElseThrow();
        return String.format(Locale.ROOT,"Trial thermometer: %.2f C | v=(%.3f, %.3f, %.3f) blocks/s | tick %d, age %d",s.temperature(),s.x(),s.y(),s.z(),s.throughTick(),s.ageTicks());
    }
    /** Dotted shaft and two arrowhead points. Zero speed is a single temperature dot. */
    static List<Point> glyph(double x,double y,double z) {
        var v=new Point(x,y,z);double speed=v.length();
        if(speed<1e-6) return List.of(Point.ZERO);
        var direction=v.scale(1/speed);var end=direction.scale(Math.min(0.8,speed*0.2));
        var side=direction.cross(Math.abs(direction.y)>0.9?new Point(1,0,0):new Point(0,1,0)).normalize().scale(end.length()*0.2);
        var neck=end.scale(0.7);
        return List.of(Point.ZERO,end.scale(0.33),end.scale(0.66),end,neck.add(side),neck.subtract(side));
    }
}
