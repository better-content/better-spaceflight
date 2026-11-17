package com.bettercontent.spaceflight.vs.compat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarFile;

/** Pinned *original ZIP entries*, identical in the ForgeGradle mapped VS 2.4.11 jar. */
public final class Vs2411ArtifactGuard {
    private static final String ORIGINAL_SHA256 =
        "f99f24de62015451a047f90484cf9d25970cac350105b581c22b2d5247ebfd53";
    private static final Map<String, String> ENTRIES = entries();
    private Vs2411ArtifactGuard() {}
    private static Map<String, String> entries() {
        var result = new LinkedHashMap<String, String>();
        String shadow = "org/valkyrienskies/core/impl/shadow/";
        result.put(shadow + "Et.class", "82c21d181bfb0a954bbab8e76e158412aba467d654c9c77999ea2d6e20806d46");
        result.put(shadow + "Ek.class", "b804b88edb0f2396aaad099fb379296720efdca7a007399d27ff47879c4d6575");
        result.put(shadow + "El.class", "cc7ae9ff607723152e0575a135588d11e953c708b5be674925ee9490f40a9bed");
        result.put(shadow + "FN.class", "e105a78e1c06be0793be05c64e7980b49c4139022defe2332e6b57364f1b5641");
        result.put(shadow + "FU.class", "f225738b45a84a46591993f18a6db9b5681084b963cb9d163b0acdccee02c853");
        result.put(shadow + "FH.class", "04e2caa8fc8614c61175b4d1ff5434bdb5c000a0b4bb15f65df7793caee8b950");
        result.put(shadow + "FG.class", "4440c6ac91654d212294c0b7b4fdc233fd341d7a368ebb2726a2fd9e177e9231");
        result.put(shadow + "FQ.class", "ece2aea998138fd85fe2f2160b5be5c7562ca4714997f36966848d8c5ca08ea2");
        result.put("org/valkyrienskies/core/impl/game/ships/PhysShipImpl.class",
            "326e9b22f59a1f9d869efe8a3e34408252a6713dc4e9aae8a3a678821529ef4f");
        // Exact ORIGINAL + Forge-mapped class hashes match for these pure VS
        // codec classes. A source-only capture may use no broader serializer.
        String core = "org/valkyrienskies/core/impl/";
        result.put(core + "game/ships/serialization/shipserver/ServerShipDataConverterImpl.class",
            "c01827f8fa235304a645d49e643dcf06865b1d84dce125c878015c9b5b1d6459");
        result.put(core + "game/ships/serialization/shipserver/dto/ServerShipDataV4.class",
            "75a45aaaed15912856ef3753b67a0569c46d422a08b379531a035727ddb8909f");
        result.put(core + "game/ships/serialization/shipinertia/ShipInertiaConverter.class",
            "801d6a156700fa6fa3d74b21f301047632ebf6c3586b04ae9e5657524e8753fb");
        result.put(core + "game/ships/serialization/shiptransform/ShipTransformConverterImpl.class",
            "dcd4d11d33a07c0bcb7c8d223db615cf3552953c6b0ee186722ee98a246a6e10");
        result.put(core + "util/serialization/VSJacksonUtil.class",
            "c7e0f360c066f3e665096671dbb9c4a1999b975e6769670e8537cc3f04c054f7");
        result.put(core + "program/VSCoreImpl.class",
            "e103cb366d0764d3082f55dfedeb228515adc798524da298ed044fef223113b4");
        result.put(core + "shadow/CA.class", "98a3e97782809f926e661fe4c47a1ecef329c59d6337370905f92e022ec909d3");
        result.put(core + "shadow/CH.class", "56c0b1e82a9ed8d4eb3cf8691c292365204694724c98d75b182a6e06b926fb7a");
        result.put(core + "shadow/CD.class", "75563e275b6c60d6994850b3cfd3e07521f242b3e47d0013c15bca922816cba1");
        result.put(core + "shadow/CK.class", "c91db06d7516e1bc9f906e8f1e16c456b3d3aec8da13645f4676cd0163e5b5e5");
        result.put(core + "shadow/Cy.class", "f19dafe581025ff17d2a5b1ce7b6cedf10d1ae32fa829e6caba6192ec59e4f1d");
        result.put(core + "game/ships/serialization/vspipeline/VSPipelineSerializer.class",
            "faf3d4117f0b9ac7de980d3c5bd30ffa944309ee2cd09ee261cf28c274ce652b");
        result.put(core + "game/ships/ShipData.class",
            "0a621dc12b7154e76c014b373f8dcf1da67ba0e28b191d59fe75aeeed33b5e5b");
        result.put(core + "game/ships/ShipDataCommon.class",
            "f4ce863af14ce1ba07300b976a487996ccfa9c1836a10ee55495f69bc0040d06");
        result.put(core + "game/ships/serialization/shiptransform/dto/ShipTransformDataV0.class",
            "b240c06acc94609e7090d624eb1855ceb0b29e5607bbcf78d758d55b90e50b9f");
        result.put(core + "game/ships/serialization/shipinertia/dto/ShipInertiaDataV0.class",
            "96262c5f9b5e59f105a7d6a81df86ea480316cbafdf231a8387b51c541ebcb3c");
        result.put("org/valkyrienskies/physics_api_krunch/KrunchNativePhysicsWorldReference.class",
            "e209dd857265d90f2d9b61f7a348fdacddce997599f0ed0b7c7294c770738d42");
        result.put("META-INF/mods.toml", "28b423ff99034da57e7000d701c891beb833ff88d900e98d98bb132ef02a328a");
        return Map.copyOf(result);
    }
    public static void requireExactJar(Path path) throws IOException {
        var md = sha256();
        try (InputStream in = java.nio.file.Files.newInputStream(path)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) md.update(buffer, 0, count);
        }
        if (!java.util.HexFormat.of().formatHex(md.digest()).equals(ORIGINAL_SHA256))
            throw new IOException("VS original archive SHA-256 mismatch");
        try (JarFile jar = new JarFile(path.toFile())) { requireExactJar(jar); }
    }
    /** Verify the classes exposed through Forge's actual SecureJar/union classloader too. */
    public static void requireExactClassloaderBytes(ClassLoader loader) throws IOException {
        for (var expected : ENTRIES.entrySet()) {
            if (!expected.getKey().endsWith(".class")) continue;
            try (InputStream in = loader.getResourceAsStream(expected.getKey())) {
                if (in == null || !digest(in.readAllBytes()).equals(expected.getValue()))
                    throw new IOException("VS classloader entry missing/altered: " + expected.getKey());
            }
        }
    }
    public static void requireExactJar(JarFile jar) throws IOException {
        for (var expected : ENTRIES.entrySet()) {
            var entry = jar.getJarEntry(expected.getKey());
            if (entry == null) throw new IOException("VS 2.4.11 entry missing: " + expected.getKey());
            String actual;
            try (InputStream in = jar.getInputStream(entry)) { actual = digest(in.readAllBytes()); }
            if (!actual.equals(expected.getValue())) {
                throw new IOException("Unsupported VS 2.4.11 artifact entry: " + expected.getKey());
            }
        }
        String metadata;
        try (InputStream in = jar.getInputStream(jar.getJarEntry("META-INF/mods.toml"))) {
            metadata = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (!metadata.contains("modId = \"valkyrienskies\"") || !metadata.contains("version = \"2.4.11\"")) {
            throw new IOException("Not the exact VS 2.4.11 release metadata");
        }
    }
    private static String digest(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(sha256().digest(bytes));
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
