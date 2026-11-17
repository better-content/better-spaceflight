package com.bettercontent.spaceflight.vs.compat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.jar.JarFile;

/** Exact original Eureka 1.6.3 only; does not whitelist an arbitrary same-named listener. */
public final class Eureka163ArtifactGuard {
    private static final String ORIGINAL_SHA256 =
        "1d59778581a533d2bb7702a3d403fb37eaddb94aa786ebf3f4ab889dded82c55";
    private static final Map<String, String> CLASS_BYTES = Map.of(
        "org/valkyrienskies/eureka/ship/EurekaShipControl.class",
            "d4393536de73c28ab173c2243602ea5e9c7b68feffeba6f7c0f98773bf716024",
        "org/valkyrienskies/eureka/blockentity/ShipHelmBlockEntity.class",
            "f7a94a914d826fd688aa694e267ea5e7cf997b14375bff61e26b3115c2ff216e");
    private Eureka163ArtifactGuard() {}
    public static void requireExactOriginal(Path path, ClassLoader loader) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Missing Eureka original archive");
        try (InputStream archive = Files.newInputStream(path)) {
            if (!ORIGINAL_SHA256.equals(digest(archive))) throw new IOException("Wrong Eureka 1.6.3 archive");
        }
        try (var jar = new JarFile(path.toFile())) {
            for (var entry : CLASS_BYTES.entrySet()) {
                var zipped = jar.getJarEntry(entry.getKey());
                if (zipped == null) throw new IOException("Missing Eureka target: " + entry.getKey());
                try (InputStream source = jar.getInputStream(zipped)) {
                    if (!entry.getValue().equals(digest(source)))
                        throw new IOException("Altered Eureka target: " + entry.getKey());
                }
            }
        }
        // SecureJar may expose union: URLs; compare raw classpath resource bytes
        // rather than assuming jar: URL paths or guessing a transformed source.
        for (var entry : CLASS_BYTES.entrySet()) {
            try (InputStream exposed = loader.getResourceAsStream(entry.getKey())) {
                if (exposed == null || !entry.getValue().equals(digest(exposed)))
                    throw new IOException("Eureka loader bytes missing/altered: " + entry.getKey());
            }
        }
    }
    private static String digest(InputStream input) throws IOException {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) md.update(buffer, 0, count);
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
