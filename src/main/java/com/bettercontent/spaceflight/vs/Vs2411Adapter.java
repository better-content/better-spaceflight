package com.bettercontent.spaceflight.vs;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.ModList;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import org.valkyrienskies.mod.common.assembly.ShipAssembler;

import java.util.Set;

/**
 * Exact-release boundary. The probe uses the same native teleport path as VS's command.
 * This is NOT a transactional production transfer coordinator and must not be exposed
 * to survival play until cargo, passengers, persistence and client tracking are proven.
 */
public final class Vs2411Adapter {
    public static final String SUPPORTED_VERSION = "2.4.11";

    private Vs2411Adapter() {}

    public static void requireSupportedVersion() {
        String actual = ModList.get().getModContainerById("valkyrienskies")
            .orElseThrow(() -> new IllegalStateException("Better Spaceflight requires Valkyrien Skies " + SUPPORTED_VERSION))
            .getModInfo().getVersion().toString();
        if (!SUPPORTED_VERSION.equals(actual)) {
            throw new IllegalStateException("Unsupported VS version " + actual + "; expected exactly " + SUPPORTED_VERSION);
        }
    }

    private static void requireGameThread(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("VS operation outside server game thread");
    }

    public record Assembly(ServerShip ship, AssemblyTranslation translation) {
        public BlockPos relocated(BlockPos original) {
            return original.offset(translation.x(), translation.y(), translation.z());
        }
    }

    /** Only accepts the explicit small fixture set; not the future player assembly search. */
    public static Assembly assembleProbe(ServerLevel level, Set<BlockPos> blocks) {
        requireGameThread(level);
        if (blocks.isEmpty() || blocks.size() > 512) throw new IllegalArgumentException("Probe requires 1..512 explicit blocks");
        Set<BlockPos> selection = Set.copyOf(blocks);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : selection) {
            minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
        }
        var originalCenter = AssemblyTranslation.Center.fromBounds(minX, minY, minZ, maxX, maxY, maxZ);
        var context = ShipAssembler.assembleToShipFull(level, selection, 1.0);
        // VS2.4.11 mutates returned fromCenter into COM world space. Never use it for voxels.
        // moveBlocksFromTo returns toCenter = integer placement corner + the original bbox half-size.
        var placementCenter = AssemblyTranslation.Center.snapshot(context.getToCenter());
        return new Assembly(context.getShip(), AssemblyTranslation.between(originalCenter, placementCenter));
    }

    public static ServerShip find(ServerLevel level, long id) {
        requireGameThread(level);
        return VSGameUtilsKt.getShipObjectWorld(level).getAllShips().getById(id);
    }

    /** Low-level destructive native operation. Fresh probe worlds ONLY. */
    public static void teleportForProbe(ServerLevel source, ServerLevel destination, ServerShip ship,
                                       Vector3dc destinationPosition) {
        setProbeState(source, destination, ship, destinationPosition, ship.getVelocity(), ship.getAngularVelocity());
    }

    /** Explicit diagnostic initial conditions; never an actuator or survival velocity clamp. */
    public static void setProbeState(ServerLevel source, ServerLevel destination, ServerShip ship,
                                     Vector3dc position, Vector3dc velocity, Vector3dc angularVelocity) {
        requireGameThread(source);
        if (source.getServer() != destination.getServer()) throw new IllegalArgumentException("Different servers");
        if (!ship.getChunkClaimDimension().equals(VSGameUtilsKt.getDimensionId(source))) {
            throw new IllegalStateException("Ship no longer owned by expected source dimension");
        }
        // Reject unequal chunk vertical layouts: pinned VS copies sections by index.
        new SectionLayout(source.getMinBuildHeight(), source.getHeight()).requireCompatible(
            new SectionLayout(destination.getMinBuildHeight(), destination.getHeight()));
        var data = ValkyrienSkiesMod.getVsCore().newShipTeleportData(
            new Vector3d(position), new Quaterniond(ship.getTransform().getShipToWorldRotation()),
            new Vector3d(velocity), new Vector3d(angularVelocity),
            VSGameUtilsKt.getDimensionId(destination), null, null);
        ValkyrienSkiesMod.getVsCore().teleportShip(VSGameUtilsKt.getShipObjectWorld(source), ship, data);
    }

    public static boolean loadedForProbe(ServerLevel level, long id) {
        requireGameThread(level);
        return VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips().getById(id) != null;
    }

    /** Read-only loaded/canonical projection comparison, NOT direct native-body proof. */
    public record LoadedProbeState(String implementation, String dimension, boolean staticBody,
                                   double x, double y, double z, double vx, double vy, double vz,
                                   boolean transformAlias, boolean inertiaAlias, String canonicalImplementation) {}

    public static LoadedProbeState loadedStateForProbe(ServerLevel level, long id) {
        requireGameThread(level);
        var loaded = VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips().getById(id);
        if (loaded == null) return null;
        var canonical = find(level, id);
        var transform = loaded.getTransform();
        var p = transform.getPositionInWorld();
        var v = loaded.getVelocity();
        return new LoadedProbeState(loaded.getClass().getName(), loaded.getChunkClaimDimension(), loaded.isStatic(),
            p.x(), p.y(), p.z(), v.x(), v.y(), v.z(),
            canonical != null && transform == canonical.getTransform(),
            canonical != null && loaded.getInertiaData() == canonical.getInertiaData(),
            canonical == null ? "ABSENT" : canonical.getClass().getName());
    }

    public static boolean nativePhysicsForProbe() {
        return org.valkyrienskies.mod.util.KrunchSupport.INSTANCE.isKrunchSupported();
    }

    public static String dimension(ServerLevel level) { return VSGameUtilsKt.getDimensionId(level); }
}
