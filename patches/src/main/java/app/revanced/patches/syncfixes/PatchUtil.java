package app.revanced.patches.syncfixes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import app.revanced.patcher.extensions.MethodKt;
import app.revanced.patcher.patch.BytecodePatchContext;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.BuilderInstruction;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;

/**
 * Locates the obfuscated Sync classes by the source file names they keep and edits their instructions.
 * Sync and Sync Pro obfuscate differently, but the source names and the code itself are the same, so the
 * lookups never depend on a class or method name.
 */
final class PatchUtil {
    private PatchUtil() {
    }

    /** Thrown when the APK is not the expected build; the patcher reports the message. */
    static RuntimeException error(String message) {
        return new IllegalStateException(message);
    }

    static MutableClassDef byType(BytecodePatchContext context, String type) {
        for (ClassDef classDef : context.getClasses()) {
            if (classDef.getType().equals(type)) {
                return mutable(context, classDef);
            }
        }
        return null;
    }

    /**
     * Every class compiled from the source file, the main one and its inner classes: Sync keeps the
     * source file name on all of them, and only one of them holds the method being looked for.
     */
    static List<MutableClassDef> bySourceFile(BytecodePatchContext context, String sourceFile) {
        List<ClassDef> matches = new ArrayList<>();
        for (ClassDef classDef : context.getClasses()) {
            if (sourceFile.equals(classDef.getSourceFile())) {
                matches.add(classDef);
            }
        }
        List<MutableClassDef> classes = new ArrayList<>(matches.size());
        for (ClassDef classDef : matches) {
            classes.add(mutable(context, classDef));
        }
        return classes;
    }

    /** The first class with the source file that has a method with the given name and parameters. */
    static MutableClassDef bySourceFileAndMethod(BytecodePatchContext context, String sourceFile,
                                                 String methodName, String... parameters) {
        for (ClassDef classDef : context.getClasses()) {
            if (!sourceFile.equals(classDef.getSourceFile())) {
                continue;
            }
            for (Method method : classDef.getMethods()) {
                if (method.getName().equals(methodName)
                        && sameParameters(method, parameters)) {
                    return mutable(context, classDef);
                }
            }
        }
        return null;
    }

    /** The first class with a method with the given name and parameters, whatever the class is called. */
    static MutableClassDef byMethod(BytecodePatchContext context, String methodName,
                                    String returnType, String... parameters) {
        for (ClassDef classDef : context.getClasses()) {
            for (Method method : classDef.getMethods()) {
                if (method.getName().equals(methodName)
                        && method.getReturnType().equals(returnType)
                        && sameParameters(method, parameters)) {
                    return mutable(context, classDef);
                }
            }
        }
        return null;
    }

    private static MutableClassDef mutable(BytecodePatchContext context, ClassDef classDef) {
        return context.getClasses().getOrReplaceMutable(classDef);
    }

    private static boolean sameParameters(Method method, String... parameters) {
        List<? extends CharSequence> actual = method.getParameterTypes();
        if (actual.size() != parameters.length) {
            return false;
        }
        for (int i = 0; i < parameters.length; i++) {
            if (!actual.get(i).toString().equals(parameters[i])) {
                return false;
            }
        }
        return true;
    }

    static MutableMethod method(MutableClassDef classDef, String name) {
        for (MutableMethod method : classDef.getMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw error(classDef.getType() + " has no method " + name);
    }

    static boolean isStatic(MutableMethod method) {
        return (method.getAccessFlags() & 0x8) != 0;
    }

    static boolean hasParameters(MutableMethod method, String... parameters) {
        return sameParameters(method, parameters);
    }

    static List<BuilderInstruction> instructions(MutableMethod method) {
        if (method.getImplementation() == null) {
            return Collections.emptyList();
        }
        return method.getImplementation().getInstructions();
    }

    static boolean hasString(MutableMethod method, String needle) {
        for (BuilderInstruction instruction : instructions(method)) {
            String string = stringReference(instruction);
            if (string != null && string.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    static String stringReference(BuilderInstruction instruction) {
        if (!(instruction instanceof ReferenceInstruction)) {
            return null;
        }
        Reference reference = ((ReferenceInstruction) instruction).getReference();
        return reference instanceof StringReference ? ((StringReference) reference).getString() : null;
    }

    /** Indexes and registers of every return-object instruction, so code can be injected before each. */
    static List<int[]> returnsObject(MutableMethod method) {
        List<int[]> returns = new ArrayList<>();
        List<BuilderInstruction> instructions = instructions(method);
        for (int i = 0; i < instructions.size(); i++) {
            BuilderInstruction instruction = instructions.get(i);
            if (instruction.getOpcode() == Opcode.RETURN_OBJECT) {
                returns.add(new int[]{i, ((OneRegisterInstruction) instruction).getRegisterA()});
            }
        }
        return returns;
    }

    /** Assembles smali against the method, reporting the code when it does not. */
    static void addInstructions(MutableMethod method, int index, String smali) {
        try {
            MethodKt.addInstructions(method, index, smali);
        } catch (RuntimeException e) {
            System.err.println("Could not assemble for " + method.getDefiningClass() + "->"
                    + method.getName() + method.getParameterTypes()
                    + " (registers " + method.getImplementation().getRegisterCount() + "):\n" + smali);
            throw e;
        }
    }

    /** Replaces the value of a register with the result of a call, through the class extension. */
    static void replaceWithStringCall(MutableMethod method, int index, int register,
                                      String descriptor, String name) {
        addInstructions(method, index,
                "invoke-static/range { v" + register + " .. v" + register + " }, "
                        + descriptor + ";->" + name + "(Ljava/lang/String;)Ljava/lang/String;\n"
                        + "move-result-object v" + register);
    }
}
