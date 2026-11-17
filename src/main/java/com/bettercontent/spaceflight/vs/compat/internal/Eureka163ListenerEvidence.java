package com.bettercontent.spaceflight.vs.compat.internal;

import net.minecraft.server.level.ServerPlayer;
import org.valkyrienskies.core.api.ships.LoadedServerShip;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;

/**
 * Read-only exact-class candidate preflight. No Eureka linkage when addon absent.
 * This DOES NOT remove the native listener veto: final canonical destination
 * LoadedServerShip/seated actor rebind and before-release native state checks remain
 * unimplemented until full-world recovery/transport handoff review. Nonzero flight
 * control buffers are normal, recorded but never zeroed or automatically rejected.
 */
public final class Eureka163ListenerEvidence {
    public record Snapshot(Object sameController, LoadedServerShip canonicalShip, ServerPlayer seatedPlayer,
                           double extraLinear, double extraAngular, double pendingLinear,
                           double pendingAngular, boolean cruising) {}
    private Eureka163ListenerEvidence() {}
    private record Handles(MethodHandle ship, MethodHandle player, MethodHandle anchors,
                           MethodHandle aligning, MethodHandle disassembling,
                           MethodHandle extraLinear, MethodHandle extraAngular,
                           MethodHandle powerLinear, MethodHandle powerAngular,
                           MethodHandle cruising) {}
    private static Handles handles(Class<?> exactClass) throws ReflectiveOperationException {
        var publicApi = MethodHandles.publicLookup();
        var privateApi = MethodHandles.privateLookupIn(exactClass, MethodHandles.lookup());
        return new Handles(
            publicApi.findVirtual(exactClass, "getShip$eureka_1201", MethodType.methodType(LoadedServerShip.class)),
            publicApi.findVirtual(exactClass, "getSeatedPlayer",
                MethodType.methodType(net.minecraft.world.entity.player.Player.class)),
            publicApi.findVirtual(exactClass, "getAnchorsActive", MethodType.methodType(int.class)),
            publicApi.findVirtual(exactClass, "getAligning", MethodType.methodType(boolean.class)),
            publicApi.findVirtual(exactClass, "getDisassembling", MethodType.methodType(boolean.class)),
            privateApi.findGetter(exactClass, "extraForceLinear", double.class),
            privateApi.findGetter(exactClass, "extraForceAngular", double.class),
            publicApi.findVirtual(exactClass, "getPowerLinear", MethodType.methodType(double.class)),
            publicApi.findVirtual(exactClass, "getPowerAngular", MethodType.methodType(double.class)),
            publicApi.findVirtual(exactClass, "isCruising", MethodType.methodType(boolean.class)));
    }
    public static Optional<Snapshot> inspect(Object listener, LoadedServerShip canonical,
                                             ServerPlayer seated) {
        if (!Eureka163MixinGate.verifiedForThisProcess() || listener == null || canonical == null
            || seated == null || !"org.valkyrienskies.eureka.ship.EurekaShipControl".equals(listener.getClass().getName()))
            return Optional.empty();
        try {
            Class<?> exact = listener.getClass();
            // Exact attachment object and player identity, not just a class-name match.
            if (canonical.getAttachment(exact) != listener) return Optional.empty();
            Handles h = handles(exact);
            if ((LoadedServerShip) h.ship.invoke(listener) != canonical
                || h.player.invoke(listener) != seated || (int) h.anchors.invoke(listener) != 0
                || (boolean) h.aligning.invoke(listener) || (boolean) h.disassembling.invoke(listener))
                return Optional.empty();
            double extraL = (double) h.extraLinear.invoke(listener), extraA = (double) h.extraAngular.invoke(listener);
            double pendingL = (double) h.powerLinear.invoke(listener), pendingA = (double) h.powerAngular.invoke(listener);
            if (!Double.isFinite(extraL) || !Double.isFinite(extraA)
                || !Double.isFinite(pendingL) || !Double.isFinite(pendingA)) return Optional.empty();
            return Optional.of(new Snapshot(listener, canonical, seated, extraL, extraA,
                pendingL, pendingA, (boolean) h.cruising.invoke(listener)));
        } catch (Throwable failedAccessor) { return Optional.empty(); } // never fall back to a name-only whitelist
    }
}
