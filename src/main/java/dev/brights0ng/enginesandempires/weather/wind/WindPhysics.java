package dev.brights0ng.enginesandempires.weather.wind;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Pushes Sable's physics objects with the wind, on every physics step (Sable runs 2 a tick by default).
 *
 * <p>Sable fires {@link ForgeSablePrePhysicsTickEvent} after its own forces are worked out and before queued forces go to
 * the physics engine, so a push queued here lands in that same step. Queued forces are impulses (force × time step) in
 * the object's own frame, at a point in its own block coordinates; they go into the pack's "wind" force group, which
 * Simulated's contraption diagram shows in its own colour.
 *
 * <p>Per object and step this is one velocity read and a few sums. The wind, shelter and outline are brought up to date
 * once per game tick at most ({@link WindShips#refresh}). Overworld only: that is where the pack's weather is.
 */
public final class WindPhysics {

    private static final int PURGE_INTERVAL = 100;

    static void onPrePhysicsTick(ForgeSablePrePhysicsTickEvent event) {
        SubLevelPhysicsSystem system = event.getPhysicsSystem();
        ServerLevel level = system.getLevel();
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        WindParams params = WindConfig.params();
        // Until the weather simulation's wind exists (phase 4), only the debug/test override blows.
        if (!params.enabled() || WindSources.override() == null) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        long tick = level.getGameTime();
        double timeStep = event.getTimeStep();
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) {
                continue;
            }
            ShipWind ship = WindShips.of(subLevel);
            if (ship.lastTick != tick) {
                WindShips.refresh(level, subLevel, ship, params, tick);
            }
            ship.lastForce = push(system, subLevel, ship, params, timeStep);
        }
        if (tick % PURGE_INTERVAL == 0) {
            WindShips.purge();
        }
    }

    /** Queues this step's push on one object; returns the force applied (0 if none). */
    private static double push(SubLevelPhysicsSystem system, ServerSubLevel subLevel, ShipWind ship, WindParams params,
                               double timeStep) {
        if (ship.speed <= 0 || ship.exposure <= 0 || ship.silhouette.isEmpty()) {
            return 0;
        }
        MassData massData = subLevel.getMassTracker();
        Vector3dc com = massData == null ? null : massData.getCenterOfMass();
        double mass = massData == null ? 0 : massData.getMass();
        if (com == null || mass <= 0) {
            return 0;
        }
        RigidBodyHandle handle = system.getPhysicsHandle(subLevel);
        if (handle == null || !handle.isValid()) {
            return 0;
        }
        Vector3d velocity = handle.getLinearVelocity(new Vector3d());
        double along = velocity.x * ship.dirX + velocity.z * ship.dirZ;
        double closing = WindForce.closing(ship.speed, along);

        Vector3d local = subLevel.logicalPose().transformNormalInverse(new Vector3d(ship.dirX, 0, ship.dirZ));
        double area = ship.silhouette.projectedArea(local.x, local.y, local.z);
        double force = WindForce.push(ship.speed, along, area, ship.pressure, ship.exposure, params.pushCoefficient());
        double impulse = WindForce.limitImpulse(force * timeStep, mass, closing);
        if (impulse <= 0) {
            return 0;
        }
        double[] point = new double[3];
        ship.silhouette.centreOfPressure(local.x, local.y, local.z, com.x(), com.y(), com.z(), point);
        local.normalize().mul(impulse);
        subLevel.getOrCreateQueuedForceGroup(WindContent.WIND.get())
                .applyAndRecordPointForce(new Vector3d(point[0], point[1], point[2]), local);
        return impulse / timeStep;
    }

    private WindPhysics() {
    }
}
