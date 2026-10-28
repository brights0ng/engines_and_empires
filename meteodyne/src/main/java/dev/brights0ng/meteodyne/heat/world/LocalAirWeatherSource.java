// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import dev.brights0ng.meteodyne.climate.Climate;
import dev.brights0ng.meteodyne.wind.WindSources;
import net.minecraft.server.level.ServerLevel;

/** Explicit prototype: uniform exterior climate at domain center (nearest active tile for sparse domains)
 * and unsheltered surface weather wind.
 * No underground/room-temperature or shelter feedback; no world objects leave this server-thread adapter.
 */
final class LocalAirWeatherSource {
    static LocalAirRun.Weather sample(ServerLevel level,LocalAirGeometryCapture.Bounds bounds) {
        if(!level.getServer().isSameThread()) throw new IllegalStateException("Weather snapshot requires server thread");
        double x=bounds.x()+bounds.nx()/2.0,y=bounds.y()+bounds.ny()/2.0,z=bounds.z()+bounds.nz()/2.0;
        if(bounds.sparse()!=null) {
            double best=Double.POSITIVE_INFINITY;var tiles=bounds.tiles();
            for(int i=0;i<tiles.length;i+=3) {
                double tx=bounds.x()+tiles[i]+8,ty=bounds.y()+tiles[i+1]+8,tz=bounds.z()+tiles[i+2]+8;
                double distance=(tx-x)*(tx-x)+(ty-y)*(ty-y)+(tz-z)*(tz-z);
                if(distance<best) {best=distance;}
            }
            // Select after measuring all candidates against the unchanged envelope center.
            for(int i=0;i<tiles.length;i+=3) {
                double tx=bounds.x()+tiles[i]+8,ty=bounds.y()+tiles[i+1]+8,tz=bounds.z()+tiles[i+2]+8;
                if((tx-x)*(tx-x)+(ty-y)*(ty-y)+(tz-z)*(tz-z)==best) {x=tx;y=ty;z=tz;break;}
            }
        }
        if(level.getChunkSource().getChunkNow(((int)Math.floor(x))>>4,((int)Math.floor(z))>>4)==null)
            throw new IllegalArgumentException("Weather anchor chunk is not loaded");
        double ambient=Climate.sample(level,x,y,z).temperature();
        var wind=WindSources.column(level,x,z);
        return new LocalAirRun.Weather(ambient,wind==null?0:wind.surfaceX(),wind==null?0:wind.surfaceZ());
    }
}
