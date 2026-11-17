package com.bettercontent.spaceflight.vs.compat.internal;

import com.bettercontent.spaceflight.BetterSpaceflight;
import com.bettercontent.spaceflight.travel.ServerOwnerPreflight;
import com.bettercontent.spaceflight.vs.compat.ReservationRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.valkyrienskies.core.impl.shadow.FN;
import org.valkyrienskies.core.impl.shadow.FQ;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import com.bettercontent.spaceflight.vs.compat.mixin.VsFnAccess;
import com.bettercontent.spaceflight.vs.compat.mixin.VsFqAccess;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One non-transport owner per actual MinecraftServer lifetime. Startup scans precede
 * any experimental native binding. No requests, offers, transfer readiness or dispatch
 * are exposed. Opt-in with exact pinned artifact AND an observed transformed physics
 * boundary is mandatory; default-on server behavior is read-only journal inspection.
 */
@Mod.EventBusSubscriber(modid = BetterSpaceflight.ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ForgeVsServerOwner {
    public enum State { JOURNAL_BLOCKED, HOOKS_OFF, WAITING_FOR_PHYSICS_HOOK,
        HOOKS_BOUND_NO_TRAVEL, STOPPING, RETIRED, FAULT }
    public record View(State state, String diagnostic, boolean transferAvailable) {}
    private static final Map<MinecraftServer, Owner> OWNERS = new IdentityHashMap<>();
    private static final class Owner {
        final MinecraftServer server;
        final ServerOwnerPreflight.Inspection inspection;
        volatile State state;
        volatile String diagnostic;
        volatile boolean stopping;
        FQ pipeline;
        FN physics;
        Thread physicsThread;
        boolean physicsThreadObservedAlive;
        ReservationRegistry registry;
        Owner(MinecraftServer server, ServerOwnerPreflight.Inspection inspection, State state, String diagnostic) {
            this.server = server; this.inspection = inspection; this.state = state; this.diagnostic = diagnostic;
        }
    }
    private ForgeVsServerOwner() {}

    public static synchronized View view(MinecraftServer server) {
        Owner o = OWNERS.get(server);
        return o == null ? new View(State.FAULT, "No inspected server owner", false)
            : new View(o.state, o.diagnostic, false);
    }

    /** Exact server-lifetime registry identity for read-only source capture; never admits travel. */
    public static synchronized boolean ownsCaptureRegistry(MinecraftServer server, ReservationRegistry registry) {
        Owner owner = OWNERS.get(server);
        return owner != null && !owner.stopping && owner.state == State.HOOKS_BOUND_NO_TRAVEL
            && registry != null && owner.registry == registry && Vs2411MixinGate.verifiedForThisProcess();
    }

    @SubscribeEvent public static synchronized void onStarted(ServerStartedEvent event) {
        var server = event.getServer();
        if (OWNERS.containsKey(server)) throw new IllegalStateException("Duplicate server-lifetime owner");
        var inspection = ServerOwnerPreflight.scan(server.getWorldPath(LevelResource.ROOT));
        State state = inspection.result() == ServerOwnerPreflight.Result.JOURNAL_BLOCKED
            ? State.JOURNAL_BLOCKED : Vs2411MixinGate.verifiedForThisProcess()
                ? State.WAITING_FOR_PHYSICS_HOOK : State.HOOKS_OFF;
        OWNERS.put(server, new Owner(server, inspection, state, inspection.diagnostic()));
        if (state == State.JOURNAL_BLOCKED) System.err.println("[Better Spaceflight] " + inspection.diagnostic());
    }
    @SubscribeEvent public static synchronized void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        var owner = OWNERS.get(event.getServer());
        if (owner == null || owner.stopping || owner.state != State.WAITING_FOR_PHYSICS_HOOK) return;
        // No probing on normal/default-off play; we require actual existing mixin
        // accessors AND an FN.a physics-thread callback already observed.
        if (!Vs2411MixinGate.verifiedForThisProcess()) return;
        try {
            Object candidate = VSGameUtilsKt.getVsPipeline(owner.server);
            if (!(candidate instanceof FQ pipeline) || !((Object) pipeline instanceof VsFqAccess)) return;
            var fq = (VsFqAccess) (Object) pipeline; // only after transformed-Mixin interface check
            FN physics = fq.spaceflight$physics();
            Thread thread = fq.spaceflight$physicsThread();
            if (physics == null || thread == null || !thread.isAlive() || !((Object) physics instanceof VsFnAccess)
                || !PinnedVs2411Bridge.hasPhysicsHeartbeat(physics, thread)) return;
            // Guard stop and bind under the same owner monitor. Event handlers run on
            // the server game thread; the physics hook only changes the heartbeat.
            var registry = ReservationRegistry.forWorld(UUID.randomUUID());
            PinnedVs2411Bridge.bindReviewedWorld(physics, registry, owner.server, thread);
            owner.pipeline = pipeline; owner.physics = physics; owner.physicsThread = thread;
            owner.physicsThreadObservedAlive = true; // checked !null && isAlive() above
            owner.registry = registry; owner.state = State.HOOKS_BOUND_NO_TRAVEL;
            owner.diagnostic = "Pinned physics hook observed; owner bound, but no transfer/readiness/backup available";
        } catch (RuntimeException | LinkageError ex) {
            owner.state = State.FAULT;
            owner.diagnostic = "Pinned native owner bind failed: " + ex.getClass().getSimpleName();
            System.err.println("[Better Spaceflight] " + owner.diagnostic);
        }
    }
    @SubscribeEvent public static synchronized void onStopping(ServerStoppingEvent event) {
        var owner = OWNERS.get(event.getServer());
        if (owner == null || owner.stopping) return;
        owner.stopping = true; owner.state = State.STOPPING;
        owner.diagnostic = "Server stopping; native owner may only retire after observed physics thread termination";
        if (owner.registry != null) owner.registry.shutdown(); // queues physics-owned cleanup, never releases here
    }
    @SubscribeEvent public static synchronized void onStopped(ServerStoppedEvent event) {
        var owner = OWNERS.get(event.getServer());
        if (owner == null) return;
        owner.stopping = true;
        if (owner.physicsThread == null) {
            OWNERS.remove(owner.server); // never bound; no native claims exist
            return;
        }
        // NEVER join the Minecraft game thread or the physics thread. The actual
        // FQ.h thread was observed alive when bound; TERMINATED after join proves
        // the physics loop ended, NOT that FN.j async native close completed.
        Thread waiter = new Thread(() -> waitForStoppedPhysics(owner), "better-spaceflight-vs-owner-retire");
        waiter.setDaemon(true); waiter.start();
    }
    private static void waitForStoppedPhysics(Owner owner) {
        boolean threadStopped = false;
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                if (!threadStopped) threadStopped = PhysicsThreadStopProof.awaitFromWatcher(
                    owner.physicsThread, owner.physicsThreadObservedAlive, 5_000);
                if (!threadStopped) continue;
                synchronized (ForgeVsServerOwner.class) {
                    if (OWNERS.get(owner.server) != owner || !owner.stopping) return;
                    try {
                        PinnedVs2411Bridge.retireAfterVerifiedPipelineStop(owner.physics, owner.registry);
                        owner.state = State.RETIRED;
                        owner.diagnostic = "VS physics thread terminated; claims retired (async resource close unverified)";
                        OWNERS.remove(owner.server);
                        return;
                    } catch (IllegalStateException pendingIoOrBoundary) {
                        // A terminal fsync may still own WRITING_RELEASED after the
                        // physics thread exits. Wait only on this dedicated watcher;
                        // never force-clear claims or block server/physics threads.
                        if (attempt == 5) {
                            owner.state = State.FAULT;
                            owner.diagnostic = "Physics stopped but journal I/O/boundary still active after 30s: "
                                + pendingIoOrBoundary;
                            System.err.println("[Better Spaceflight] " + owner.diagnostic);
                            return;
                        }
                    }
                }
                Thread.sleep(5_000);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
        }
        owner.state = State.FAULT;
        owner.diagnostic = "VS physics thread did not stop within 30s; claims retained and travel unavailable";
        System.err.println("[Better Spaceflight] " + owner.diagnostic);
    }
}
