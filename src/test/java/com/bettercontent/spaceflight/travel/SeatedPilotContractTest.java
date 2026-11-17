package com.bettercontent.spaceflight.travel;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/** Class-contract only: no Minecraft server/player may be constructed in pure tests. */
final class SeatedPilotContractTest {
    @Test void serverSeatControllerAndMountedVsShipAreAllNecessarySources() throws Exception {
        var calls = new HashSet<String>();
        try (var in = getClass().getClassLoader().getResourceAsStream(
            "com/bettercontent/spaceflight/travel/SeatedVsPilotAuthority.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    if (!name.equals("select")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                              String descriptor, boolean isInterface) {
                            calls.add(owner + "." + method);
                        }
                    };
                }
            }, 0);
        }
        assertTrue(calls.contains("org/valkyrienskies/mod/common/entity/ShipMountingEntity.isController"));
        assertTrue(calls.contains("org/valkyrienskies/mod/common/entity/ShipMountingEntity.getControllingPassenger"));
        assertTrue(calls.contains("org/valkyrienskies/mod/common/VSGameUtilsKt.getShipMountedTo"));
        assertTrue(calls.contains("org/valkyrienskies/mod/common/VSGameUtilsKt.getDimensionId"));
        assertTrue(calls.contains("net/minecraft/resources/ResourceKey.location"));
    }
    @Test void vsInternalDimensionIsNeverPassedAsPublicPolicySource() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/bettercontent/spaceflight/travel/SeatedVsPilotAuthority.java"));
        assertTrue(source.contains("String vsDimension = VSGameUtilsKt.getDimensionId(player.serverLevel())"));
        assertTrue(source.contains("vsDimension.equals(loaded.getChunkClaimDimension())"));
        assertTrue(source.contains("String policySource = player.serverLevel().dimension().location().toString()"));
        assertTrue(source.contains("new Selection(actor, loaded.getId(), policySource"));
        assertFalse(source.contains("new Selection(actor, loaded.getId(), vsDimension"));
    }
}
