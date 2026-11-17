package com.bettercontent.spaceflight.vs.compat.internal;

import com.bettercontent.spaceflight.vs.compat.Vs2411ArtifactGuard;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.minecraftforge.fml.loading.FMLLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Shipping mixins are DEFAULT OFF. Explicit one-time JVM flag is experimental ONLY,
 * not admission to ship transfer. Never patch a different or altered upstream JAR.
 */
public final class Vs2411MixinGate implements IMixinConfigPlugin {
    public static final String PROPERTY = "better_spaceflight.experimental.vs2411_hooks";
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(PROPERTY, "false"));
    private static volatile boolean verified;
    private boolean pinned;

    /** True only after explicit opt-in AND exact original VS target artifact verification. */
    public static boolean verifiedForThisProcess() { return ENABLED && verified; }

    /**
     * Independent Mixin config discovery order is unspecified. Either plugin
     * verifies the SAME original Forge ModFile and exposed bytes once. This
     * is only an artifact gate, never evidence that VS Mixins applied or that
     * native transfer is safe; the binder checks accessors and real heartbeat.
     */
    public static synchronized boolean ensureVerifiedFromForgeArtifacts() {
        if (!ENABLED) return false;
        if (verified) return true;
        try {
            var loading = FMLLoader.getLoadingModList();
            if (loading == null || loading.getModFileById("valkyrienskies") == null)
                throw new IllegalStateException("Forge VS ModFile unavailable at Mixin plugin phase");
            Path archive = loading.getModFileById("valkyrienskies").getFile().getFilePath();
            if (!Files.isRegularFile(archive))
                throw new IllegalStateException("Original VS archive unavailable: " + archive);
            Vs2411ArtifactGuard.requireExactJar(archive);
            Vs2411ArtifactGuard.requireExactClassloaderBytes(Vs2411MixinGate.class.getClassLoader());
            verified = true;
            return true;
        } catch (Exception | LinkageError ex) {
            throw new IllegalStateException("Unsupported/altered VS ModFile or SecureJar class bytes; hooks refused", ex);
        }
    }
    @Override public void onLoad(String mixinPackage) {
        pinned = ensureVerifiedFromForgeArtifacts();
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!ENABLED) return false;
        if (!pinned) throw new IllegalStateException("Experimental VS hooks requested before artifact verification");
        return true;
    }
    @Override public void acceptTargets(Set<String> mine, Set<String> other) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode targetClass, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode targetClass, String mixin, IMixinInfo info) {}
}
