package com.bettercontent.spaceflight.vs.compat.recovery;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.common.capabilities.CapabilityProvider;
import net.minecraftforge.common.util.INBTSerializable;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only provider metadata around ONE original ChunkSerializer.write call.
 * No second writeCapsToNBT, no dispatcher serialization, no gather/init, and
 * no broad Mixin hook. Unsupported/changed reflection or class bytes refuse.
 * This ONLY addresses Forge's top-level catch-and-omit capability path.
 */
public final class ForgeChunkCapabilityEvidence {
    private ForgeChunkCapabilityEvidence() {}
    // Forge dispatcher stores ResourceLocation.toString() as each writer name.
    // Restrict to an explicit canonical namespace:path shape without invoking
    // Minecraft registry/component static initializers in pure checks.
    private static final java.util.regex.Pattern CAP_NAME = java.util.regex.Pattern.compile(
        "[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Map<String, Set<String>> CLASS_HASHES = Map.of(
        "net/minecraft/world/level/chunk/LevelChunk.class", Set.of(
            "cb75c2eeff49df4b7b1c5a7ce46113f9fee197ac952ef37b60032a289f2e05e9",
            "2d74f57e549e8e190bfcc83771ff3b97042638ed10e8aaa0e45e8728f5d7c13e"),
        "net/minecraft/world/level/chunk/storage/ChunkSerializer.class", Set.of(
            "a5b7110146d6a84577b4d95bf4a7e6de64920be08e991ed1278952cb05924b2e",
            "ba60daef731e0af40e36857b348a16472df17235356462177e1421a07f56e98d"),
        "net/minecraftforge/common/capabilities/CapabilityProvider.class", Set.of(
            "91d7ff7fd801cbbd2f2db9da73b522660a9f449aca0a5c6ff7b90155116d483b"),
        "net/minecraftforge/common/capabilities/CapabilityProvider$AsField.class", Set.of(
            "16f3b9d591a12553d5135bb5f5694d6118fda763ce158e4299e8271f3457dd76"),
        "net/minecraftforge/common/capabilities/CapabilityDispatcher.class", Set.of(
            "a846d830c797746a864a9c34093612b0e9f76d2902a2fde705115fda0b9e46ad",
            "13631ee9624c78b9b4175c3a12d500107da03feec7756edc7c0a3b3f4050ec03"));
    private static volatile boolean verifiedForProcess;
    /** Pin real archived runtime server/universal bytes AND mapped development variants once per classloader. */
    static synchronized void verifyClasses() throws IOException {
        if (verifiedForProcess) return;
        for (var e : CLASS_HASHES.entrySet()) {
            try (var stream = LevelChunk.class.getClassLoader().getResourceAsStream(e.getKey())) {
                if (stream == null || !e.getValue().contains(java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()))))
                    throw new IOException("Unpinned Forge capability/chunk persistence class " + e.getKey());
            } catch (IOException ex) { throw ex; }
            catch (Exception ex) { throw new IOException("Forge class hash pin inaccessible", ex); }
        }
        verifiedForProcess = true;
    }
    record Snapshot(Object providerIdentity, boolean lazy, boolean initialized, boolean supplierPresent,
                    Object dispatcherIdentity, String[] names, Object[] writerIdentities,
                    CompoundTag lazyData) {
        Snapshot {
            names = names == null ? null : names.clone();
            writerIdentities = writerIdentities == null ? null : writerIdentities.clone();
            lazyData = lazyData == null ? null : lazyData.copy();
        }
    }
    static Snapshot inspect(LevelChunk chunk) throws IOException {
        verifyClasses();
        try {
            var level = MethodHandles.privateLookupIn(LevelChunk.class, MethodHandles.lookup());
            var provider = (CapabilityProvider.AsField<?>) level.findGetter(LevelChunk.class,
                "capProvider", CapabilityProvider.AsField.class).invoke(chunk);
            if (provider == null) throw new IOException("Missing Forge chunk provider owner");
            var lookup = MethodHandles.privateLookupIn(CapabilityProvider.class, MethodHandles.lookup());
            boolean lazy = (boolean) lookup.findGetter(CapabilityProvider.class, "isLazy", boolean.class).invoke(provider);
            boolean initialized = (boolean) lookup.findGetter(CapabilityProvider.class, "initialized", boolean.class).invoke(provider);
            Object supplier = lookup.findGetter(CapabilityProvider.class,
                "lazyParentSupplier", java.util.function.Supplier.class).invoke(provider);
            var dispatcher = (CapabilityDispatcher) lookup.findGetter(CapabilityProvider.class,
                "capabilities", CapabilityDispatcher.class).invoke(provider);
            var cached = (CompoundTag) lookup.findGetter(CapabilityProvider.class,
                "lazyData", CompoundTag.class).invoke(provider);
            String[] names = null;
            Object[] writers = null;
            if (dispatcher != null) {
                var dispatch = MethodHandles.privateLookupIn(CapabilityDispatcher.class, MethodHandles.lookup());
                names = (String[]) dispatch.findGetter(CapabilityDispatcher.class, "names", String[].class).invoke(dispatcher);
                writers = (INBTSerializable<?>[]) dispatch.findGetter(CapabilityDispatcher.class,
                    "writers", INBTSerializable[].class).invoke(dispatcher);
            }
            return new Snapshot(provider, lazy, initialized, supplier != null,
                dispatcher, names, writers, cached);
        } catch (IOException ex) { throw ex; }
        catch (Throwable ex) { throw new IOException("Exact Forge provider metadata inaccessible", ex); }
    }
    /** Direct read-only field snapshots cannot be forged by an omitted serializer return. */
    static boolean validate(Snapshot before, Snapshot after, CompoundTag writerNbt) throws IOException {
        if (before == null || after == null || writerNbt == null
            || before.providerIdentity == null || after.providerIdentity != before.providerIdentity
            || before.lazy != after.lazy || before.initialized != after.initialized
            || before.supplierPresent != after.supplierPresent
            || before.dispatcherIdentity != after.dispatcherIdentity
            || !Objects.equals(before.lazyData, after.lazyData)
            || !Arrays.equals(before.names, after.names)
            || !sameWriters(before.writerIdentities, after.writerIdentities))
            throw new IOException("Forge provider identity/registration changed during chunk serializer");
        if (writerNbt.contains("ForgeCaps") && !writerNbt.contains("ForgeCaps", Tag.TAG_COMPOUND))
            throw new IOException("ForgeCaps has unknown tag encoding");
        CompoundTag actual = writerNbt.contains("ForgeCaps", Tag.TAG_COMPOUND)
            ? writerNbt.getCompound("ForgeCaps") : null;
        if (after.lazy && !after.initialized) {
            if (after.dispatcherIdentity != null || after.names != null || after.writerIdentities != null)
                throw new IOException("Uninitialized Forge provider has unexpected dispatcher");
            if (after.lazyData == null) {
                if (after.supplierPresent || actual != null)
                    throw new IOException("Uninitialized lazy provider may have omitted unknown state");
                return false; // explicit no cached/provider state
            }
            if (!after.lazyData.equals(actual)) throw new IOException("Cached lazy ForgeCaps omitted/changed");
            return true;
        }
        if (!after.initialized) throw new IOException("Unknown nonlazy Forge provider initialization");
        if (after.lazyData != null || after.supplierPresent)
            throw new IOException("Initialized Forge provider retained unreviewed lazy state");
        if (after.dispatcherIdentity == null) {
            if (after.names != null || after.writerIdentities != null || actual != null)
                throw new IOException("No-provider ForgeCaps output/registration mismatch");
            return false; // proven no provider, no lazy data
        }
        if (after.names == null || after.writerIdentities == null
            || after.names.length != after.writerIdentities.length || after.names.length > 128)
            throw new IOException("Forge capability names/writers array mismatch");
        var names = new HashSet<String>();
        for (int i = 0; i < after.names.length; i++) {
            String name = after.names[i];
            if (name == null || name.length() > 256 || !CAP_NAME.matcher(name).matches()
                || !names.add(name) || after.writerIdentities[i] == null)
                throw new IOException("Duplicate/malformed/null Forge capability writer registration");
        }
        if (actual == null || !actual.getAllKeys().equals(names))
            throw new IOException("Forge chunk serializer silently omitted/added capability providers");
        return true;
    }
    private static boolean sameWriters(Object[] a, Object[] b) {
        if (a == null || b == null) return a == b;
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }
}
