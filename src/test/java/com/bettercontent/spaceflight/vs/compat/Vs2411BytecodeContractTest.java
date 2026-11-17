package com.bettercontent.spaceflight.vs.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Reads pinned release class BYTES only; never loads a VS class or executes native physics. */
final class Vs2411BytecodeContractTest {
    private static final String ROOT = "org/valkyrienskies/core/impl/shadow/";
    private static final String JOINT = "org/valkyrienskies/core/internal/joints/VSJoint;";
    private static byte[] classBytes(String name) throws IOException {
        try (var stream = Vs2411BytecodeContractTest.class.getClassLoader().getResourceAsStream(ROOT + name + ".class")) {
            assertNotNull(stream, "Pinned VS class not on unit classpath: " + name);
            return stream.readAllBytes();
        }
    }
    private static String hex(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        return java.util.HexFormat.of().formatHex(hash);
    }
    private static int invokes(String className, String method, String descriptor,
                               String targetOwner, String targetName, String targetDescriptor) throws Exception {
        var methods = new HashMap<String, Integer>();
        var hits = new int[1];
        new ClassReader(classBytes(className)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc,
                                                        String signature, String[] exceptions) {
                if (!name.equals(method) || !desc.equals(descriptor)) return null;
                methods.merge(name + desc, 1, Integer::sum);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String called,
                                                          String calledDescriptor, boolean isInterface) {
                        if (owner.equals(targetOwner) && called.equals(targetName)
                            && calledDescriptor.equals(targetDescriptor)) hits[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        assertEquals(1, methods.getOrDefault(method + descriptor, 0), "Pinned method must occur once");
        return hits[0];
    }
    @Test void handlerDependenciesRemainPublicAndOutsideMixinNamespace() throws Exception {
        String prefix = "com/bettercontent/spaceflight/vs/compat/";
        String helper = prefix + "internal/PinnedVs2411Bridge";
        String mixinPackage = prefix + "mixin/";
        String[] classes = {"VsFnAccess", "VsFnMixin", "VsFuAccess", "VsFuMixin",
            "VsFqAccess", "VsFqMixin", "VsFrameMixin", "VsPhysShipMixin"};
        byte[] helperBytes;
        try (var stream = getClass().getClassLoader().getResourceAsStream(helper + ".class")) {
            assertNotNull(stream, "Internal helper must be in main output");
            helperBytes = stream.readAllBytes();
        }
        var methods = new HashMap<String, Integer>();
        new ClassReader(helperBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int version, int access, String name, String sig, String parent, String[] interfaces) {
                assertTrue((access & Opcodes.ACC_PUBLIC) != 0, "Transplanted handlers must access helper class");
                assertFalse(name.startsWith(mixinPackage));
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                        String signature, String[] exceptions) {
                methods.put(name + descriptor, access);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (String name : classes) {
            try (var stream = getClass().getClassLoader().getResourceAsStream(mixinPackage + name + ".class")) {
                assertNotNull(stream, "Configured Mixin missing from main output: " + name);
                new ClassReader(stream.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String method, String desc,
                                                                String sig, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode, String owner, String called,
                                                                  String descriptor, boolean itf) {
                                if (!owner.equals(helper)) return;
                                int flags = methods.getOrDefault(called + descriptor, 0);
                                assertTrue((flags & Opcodes.ACC_PUBLIC) != 0 && (flags & Opcodes.ACC_STATIC) != 0,
                                    "Mixin handler must only call public static helper " + called + descriptor);
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        try (var config = getClass().getClassLoader().getResourceAsStream("better_spaceflight.vs2411.mixins.json")) {
            assertNotNull(config, "Required Mixin config must be packaged as resource");
            String text = new String(config.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String name : classes) assertTrue(text.contains("\"" + name + "\""));
            assertFalse(text.contains("PinnedVs2411Bridge"));
        }
    }
    @Test void exactOriginalFuAndFnBytesRequired() throws Exception {
        assertEquals("f225738b45a84a46591993f18a6db9b5681084b963cb9d163b0acdccee02c853", hex(classBytes("FU")));
        assertEquals("e105a78e1c06be0793be05c64e7980b49c4139022defe2332e6b57364f1b5641", hex(classBytes("FN")));
    }
    @Test void exactFrameDeleteAndSameIdRestoreCallsitesAreUnique() throws Exception {
        assertEquals(1, invokes("FQ", "postTickGame", "()V", ROOT + "FH", "b", "()L" + ROOT + "FG;"));
        assertEquals(1, invokes("FN", "a", "(DZ)L" + ROOT + "FL;", ROOT + "FN", "b", "(L" + ROOT + "FG;)V"));
        assertEquals(1, invokes("FN", "b", "(L" + ROOT + "FG;)V", ROOT + "FU", "removeJoint", "(I)Z"));
        assertEquals(1, invokes("FU", "removeJoint", "(I)Z", ROOT + "Ek", "b", "(I)V"));
        String event = "(IL" + JOINT + "Lorg/valkyrienskies/core/api/events/JointSerializedEvent$Operation;)V";
        assertEquals(1, invokes("FU", "removeJoint", "(I)Z", ROOT + "FU", "b", event));
        assertEquals(1, invokes("FU", "a", "(IL" + JOINT + ")Z", ROOT + "FU", "a",
            "(IL" + JOINT + "Lorg/valkyrienskies/core/api/events/JointSerializedEvent$Operation;)Z"));
        assertEquals(1, invokes("FU", "a", "(IL" + JOINT + "Lorg/valkyrienskies/core/api/events/JointSerializedEvent$Operation;)Z",
            ROOT + "Ek", "a", "(IL" + JOINT + ")V"));
        assertEquals(1, invokes("FU", "a", "(IL" + JOINT + "Lorg/valkyrienskies/core/api/events/JointSerializedEvent$Operation;)Z",
            ROOT + "FU", "b", event));
    }
}
