package com.bettercontent.spaceflight.vs.compat.internal;

import com.bettercontent.spaceflight.vs.compat.Eureka163ArtifactGuard;
import net.minecraftforge.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Optional Eureka hooks only if experimental VS hooks and exact original Eureka are verified. */
public final class Eureka163MixinGate implements IMixinConfigPlugin {
    private static volatile boolean verified;
    private boolean pinned;
    public static boolean verifiedForThisProcess() { return verified && Vs2411MixinGate.verifiedForThisProcess(); }
    @Override public void onLoad(String mixinPackage) {
        if (!Boolean.parseBoolean(System.getProperty(Vs2411MixinGate.PROPERTY, "false"))) return;
        if (!Vs2411MixinGate.ensureVerifiedFromForgeArtifacts())
            throw new IllegalStateException("Eureka hooks require exact VS 2.4.11 artifacts and opt-in");
        var loading = FMLLoader.getLoadingModList();
        if (loading == null) throw new IllegalStateException("Forge loading ModFile list unavailable");
        var mod = loading.getModFileById("eureka");
        if (mod == null) return; // optional addon truly absent
        try {
            Path archive = mod.getFile().getFilePath();
            Eureka163ArtifactGuard.requireExactOriginal(archive, Eureka163MixinGate.class.getClassLoader());
            pinned = true;
            verified = true;
        } catch (Exception | LinkageError ex) {
            throw new IllegalStateException("Unsupported/altered Eureka 1.6.3; optional hooks refused", ex);
        }
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) { return pinned; }
    @Override public void acceptTargets(Set<String> mine, Set<String> other) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode targetClass, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode targetClass, String mixin, IMixinInfo info) {}
}
