package com.bettercontent.spaceflight.qualification;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Author-only native oracle skeleton, NOT registered in this batch. Never call
 * the historical destructive Vs2411Adapter.teleportForProbe or fake readiness.
 *
 * When separately authorized after a real production dispatcher + restorable
 * full-world backup exist, each native route/fault test must capture fresh
 * authoritative game-thread source/target chunks (BEs, ForgeCaps, scheduled
 * ticks, cargo), actor/seat/riding UUID topology, exact connected membership,
 * and physics-thread FN/FU bodies, inertia, pose/velocities and original-ID
 * native/logical joints before and after dispatch. Require the exact five route
 * oracles and independent fault/restart/client evidence; a safe Twilight veto
 * is NOT successful Twilight transit. None of those authoritative callbacks
 * or recovery operations exists here, so this annotated method MUST refuse.
 */
@PrefixGameTestTemplate(false)
public final class QualificationNativeGameTests {
    private QualificationNativeGameTests() {}

    @GameTest(templateNamespace = QualificationTestMod.ID, template = "empty", timeoutTicks = 350)
    public static void connectedAssemblyAndRouteEvidence(GameTestHelper helper) {
        throw new IllegalStateException("BLOCKED: no authenticated production connected-set dispatch, "
            + "full-world backup/restore, actor/chunk closure or safe destination owner; "
            + "do not substitute the historical probe or mark a GameTest pass");
    }
}
