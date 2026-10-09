package dev.brights0ng.enginesandempires.weather.lightning.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.lightning.FlashLight;
import dev.brights0ng.enginesandempires.weather.lightning.LightningFlashes;
import dev.brights0ng.enginesandempires.weather.lightning.LightningModel;
import dev.brights0ng.enginesandempires.weather.lightning.LightningPayload;
import dev.brights0ng.enginesandempires.weather.lightning.ThunderPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The light and sound of the server's flashes (weather phase 6c; Bright, 2026-10-08).
 *
 * <ul>
 *   <li><b>Glow:</b> each flash lights up a patch of its tower in the cloud shader (up to {@link #GLOWS} at once,
 *       brightest first), flickering by its {@link FlashLight}; no mesh is rebuilt.</li>
 *   <li><b>Sky flash:</b> a strike within {@value #STRIKE_SKY_FLASH} blocks, or an in-cloud flash within
 *       {@value #CLOUD_SKY_FLASH}, flashes the sky and the world's light at each stroke, as vanilla's bolts do.</li>
 *   <li><b>Thunder:</b> vanilla's sounds, played when the sound would arrive ({@link ThunderPlan}: 343 blocks a
 *       second), from the flash's direction. A vanilla bolt from an announced strike stays silent (see
 *       {@code LightningBoltSoundMixin}): its thunder is this one.</li>
 * </ul>
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID, value = Dist.CLIENT)
public final class ClientLightning {

    /** How many flashes the cloud shader lights at once. */
    public static final int GLOWS = 4;
    static final double STRIKE_SKY_FLASH = 256;
    static final double CLOUD_SKY_FLASH = 128;
    /** The glow's radius as a share of the lit tower's radius, and its least, blocks. */
    static final double GLOW_SHARE = 0.6;
    static final double MIN_GLOW = 48;
    /** How far from the camera thunder is placed (only its direction matters: it isn't faded by distance). */
    private static final double SOUND_OFFSET = 4;

    private record Active(LightningPayload p, FlashLight light, double startMs) {
    }

    private record Pending(ThunderPlan.Layer layer, double atMs, double x, double y, double z) {
    }

    private static final List<Active> ACTIVE = new ArrayList<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static double lastTickMs = LightningFlashes.nowMs();

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        double now = LightningFlashes.nowMs();
        if (level == null) {
            ACTIVE.clear();
            PENDING.clear();
            LightningFlashes.clear();
            lastTickMs = now;
            return;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        for (LightningFlashes.Flash f : LightningFlashes.drainNew()) {
            start(f.payload(), f.arrivedMs(), cam);
        }
        for (Active a : ACTIVE) {
            if (a.light.strokeStarts(lastTickMs - a.startMs, now - a.startMs) && nearEnough(a.p, cam)) {
                level.setSkyFlashTime(2);
            }
        }
        ACTIVE.removeIf(a -> now - a.startMs > a.light.duration());
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending s = it.next();
            if (now >= s.atMs) {
                play(mc, s, cam);
                it.remove();
            }
        }
        lastTickMs = now;
    }

    private static void start(LightningPayload p, double startMs, Vec3 cam) {
        long seed = FlashLight.seed(p.x(), p.y(), p.z());
        ACTIVE.add(new Active(p, FlashLight.of(seed, p.struck(), p.strength()), startMs));
        double d = cam.distanceTo(new Vec3(p.x(), p.y(), p.z()));
        double sd = p.struck() ? cam.distanceTo(new Vec3(p.targetX(), p.targetY(), p.targetZ())) : Double.NaN;
        double heard = p.struck() ? Math.min(d, sd) : d;
        double sx = p.struck() ? p.targetX() : p.x();
        double sy = p.struck() ? p.targetY() : p.y();
        double sz = p.struck() ? p.targetZ() : p.z();
        for (ThunderPlan.Layer l : ThunderPlan.of(heard, sd, p.struck(), LightningModel.Settings.DEFAULT.range(),
                seed)) {
            PENDING.add(new Pending(l, startMs + l.delay() * 1000, sx, sy, sz));
        }
    }

    private static boolean nearEnough(LightningPayload p, Vec3 cam) {
        if (p.struck()) {
            return Math.hypot(p.targetX() - cam.x, p.targetZ() - cam.z) <= STRIKE_SKY_FLASH;
        }
        return Math.hypot(p.x() - cam.x, p.z() - cam.z) <= CLOUD_SKY_FLASH;
    }

    private static void play(Minecraft mc, Pending s, Vec3 cam) {
        SoundEvent event = s.layer.sound() == ThunderPlan.Sound.IMPACT ? SoundEvents.LIGHTNING_BOLT_IMPACT
                : SoundEvents.LIGHTNING_BOLT_THUNDER;
        Vec3 dir = new Vec3(s.x - cam.x, s.y - cam.y, s.z - cam.z);
        dir = dir.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : dir.normalize();
        Vec3 at = cam.add(dir.scale(SOUND_OFFSET));
        mc.getSoundManager().play(new SimpleSoundInstance(event.getLocation(), SoundSource.WEATHER, s.layer.volume(),
                s.layer.pitch(), RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE, at.x, at.y, at.z,
                false));
    }

    /** Sets the cloud shader's flash uniforms for this frame: the brightest {@link #GLOWS} flashes now. */
    public static void upload(ShaderInstance shader, Vec3 cam) {
        double now = LightningFlashes.nowMs();
        List<double[]> lit = new ArrayList<>();
        for (Active a : ACTIVE) {
            double b = a.light.at(now - a.startMs);
            if (b > 0.005) {
                lit.add(new double[] {a.p.x() - cam.x, a.p.y() - cam.y, a.p.z() - cam.z, b,
                        Math.max(MIN_GLOW, a.p.radius() * GLOW_SHARE)});
            }
        }
        lit.sort(Comparator.comparingDouble((double[] v) -> v[3]).reversed());
        float[] radii = new float[GLOWS];
        String[] names = {"FlashA", "FlashB", "FlashC", "FlashD"};
        for (int i = 0; i < GLOWS; i++) {
            if (i < lit.size()) {
                double[] v = lit.get(i);
                shader.safeGetUniform(names[i]).set((float) v[0], (float) v[1], (float) v[2], (float) v[3]);
                radii[i] = (float) v[4];
            } else {
                shader.safeGetUniform(names[i]).set(0f, 0f, 0f, 0f);
                radii[i] = 1f;
            }
        }
        shader.safeGetUniform("FlashRadii").set(radii[0], radii[1], radii[2], radii[3]);
    }

    private ClientLightning() {
    }
}
