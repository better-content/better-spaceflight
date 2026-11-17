package com.bettercontent.spaceflight.vs.compat.recovery;

import com.bettercontent.spaceflight.vs.compat.Vs2411ArtifactGuard;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Pure static/pinned-byte contracts: not a Minecraft/serializer/native execution. */
final class CaptureAdapterContractTest {
    @Test void originalAndMappedVsCodecEntriesAreIdenticalButMappedArchiveIsNotBlessedAsOriginal() throws Exception {
        String mapped = System.getProperty("spaceflight.vs.mappedJar");
        org.junit.jupiter.api.Assumptions.assumeTrue(mapped != null, "Mapped fixture path required");
        try (var jar = new JarFile(Path.of(mapped).toFile())) { Vs2411ArtifactGuard.requireExactJar(jar); }
        assertThrows(java.io.IOException.class, () -> Vs2411ArtifactGuard.requireExactJar(Path.of(mapped)));
    }
    @Test void originalCoreShipConverterInvokesActualHolderAndSharedMapper() throws Exception {
        var calls = new HashSet<String>();
        String original = System.getProperty("spaceflight.vs.originalJar");
        org.junit.jupiter.api.Assumptions.assumeTrue(original != null, "Original fixture path required");
        try (var jar = new JarFile(Path.of(original).toFile())) {
            byte[] converter;
            try (var in = jar.getInputStream(jar.getJarEntry(
                "org/valkyrienskies/core/impl/game/ships/serialization/shipserver/ServerShipDataConverterImpl.class"))) {
                converter = in.readAllBytes();
            }
            new ClassReader(converter).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    if (!name.equals("convertToDto")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                              String descriptor, boolean itf) {
                            calls.add(owner + "." + method);
                        }
                    };
                }
            }, 0);
        }
        assertTrue(calls.contains("org/valkyrienskies/core/impl/shadow/CA.a"));
        assertTrue(calls.contains("org/valkyrienskies/core/impl/game/ships/ShipData.getAttachmentHolder"));
        var capture = source("PinnedVsShipCapture.java");
        assertTrue(capture.contains("mapper != core.getDefaultMapper() || mapper != VSJacksonUtil.INSTANCE.getDefaultMapper()"));
        assertTrue(capture.contains("holder.getAllAttachments().iterator().hasNext()"));
        assertTrue(capture.contains("mapper.writerFor(ServerShipDataV4.class).writeValue(output, dto)"));
        assertFalse(capture.contains("writeValueAsBytes" + "(dto)"));
        assertFalse(capture.contains("convertToModel("));
    }
    @Test void originalVsShipMountingEntityUsesDirectShipyardFallbackNotUnknownProvider() throws Exception {
        String original = System.getProperty("spaceflight.vs.originalJar");
        org.junit.jupiter.api.Assumptions.assumeTrue(original != null, "Original VS fixture required");
        try (var jar = new JarFile(Path.of(original).toFile())) {
            var seat = jar.getJarEntry("org/valkyrienskies/mod/common/entity/ShipMountingEntity.class");
            assertNotNull(seat);
            var interfaces = new java.util.ArrayList<String>();
            new ClassReader(jar.getInputStream(seat).readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public void visit(int version, int access, String name, String sig, String parent, String[] implemented) {
                    interfaces.addAll(java.util.List.of(implemented));
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            assertFalse(interfaces.contains("org/valkyrienskies/mod/common/entity/ShipMountedToDataProvider"));
            var utils = jar.getJarEntry("org/valkyrienskies/mod/common/VSGameUtilsKt.class");
            var calls = new HashSet<String>();
            new ClassReader(jar.getInputStream(utils).readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] errors) {
                    if (!name.equals("getShipMountedToData")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) {
                            calls.add(method);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            assertTrue(calls.contains("getLoadedShipManagingPos"));
            assertTrue(calls.contains("provideShipMountedToData"));
        }
        var selection = source("LoadedOnboardActorSelection.java");
        assertTrue(selection.contains("actor.getVehicle().getClass() != ShipMountingEntity.class"));
    }
    @Test void actualChunkAndActorPathsNeverCallAnExtraSerializerToHideSilentOmission() throws Exception {
        var chunk = source("LoadedChunkCapture.java");
        assertTrue(chunk.contains("ChunkSerializer.write(source, chunk)"));
        assertTrue(chunk.contains("expectedBePositions"));
        assertTrue(chunk.contains("savedBePositions.equals(expectedBePositions)"));
        assertFalse(chunk.contains("chunk.writeCapsToNBT()"));
        assertTrue(chunk.contains("capabilityPayloadObserved"));
        var actor = source("LoadedActorCapture.java");
        assertTrue(actor.contains("root.saveAsPassenger(nbt)"));
        assertTrue(actor.contains("player.saveWithoutId(nbt)"));
        assertTrue(actor.contains("Nested passenger UUID/ordering changed"));
        assertTrue(actor.contains("RIDING_GRAPH"));
        assertTrue(actor.contains("identityByUuid.putIfAbsent(entity.getUUID(), entity)"));
        assertTrue(actor.contains("duplicate != null && duplicate != entity"));
        var coordinator = source("ReviewOnlyCapture.java");
        assertTrue(coordinator.contains("new CaptureEnvelope.Status(false, false, true"));
        assertTrue(coordinator.contains("CHUNK_CAPABILITY_STATUS"));
        assertTrue(coordinator.contains("builder.add(captured.exactTickDetails())"));
        assertTrue(coordinator.contains("LoadedShipyardChunkSelection.current(source, claims)"));
        assertTrue(coordinator.contains("LoadedOnboardActorSelection.current(source, memberIds)"));
        assertTrue(coordinator.contains("Forge provider omission checked for selected FULL chunks only"));
        assertTrue(chunk.contains("ForgeChunkCapabilityEvidence.validate(capabilityBefore, capabilityAfter, nbt)"));
        assertTrue(coordinator.contains("ForgeVsServerOwner.ownsCaptureRegistry(source.getServer(), registry)"));
        assertTrue(coordinator.contains("registry.stageIfKnown(joint.nonce()) != ReservationRegistry.Stage.PREPARING"));
    }
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/com/bettercontent/spaceflight/vs/compat/recovery/" + name));
    }
}
