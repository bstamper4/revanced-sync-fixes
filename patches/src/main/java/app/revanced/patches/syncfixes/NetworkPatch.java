package app.revanced.patches.syncfixes;

import java.util.List;

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.Patch;
import app.revanced.patcher.patch.PatchKt;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.BuilderInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import kotlin.Unit;

/**
 * Wraps the OkHttp client Volley sends Sync's requests through, which is where the Redgifs and imgur
 * fixes live. The client getter call in BasicNetwork.performRequest is patched rather than the client's
 * builder in OkHttpHelper, so the official ReVanced Redgifs patch (which patches that builder) can be
 * used at the same time.
 */
public final class NetworkPatch {
    @SuppressWarnings("unused")
    public static final Patch INSTANCE = PatchKt.bytecodePatch(
            "Sync network fixes",
            "Internal patch, required by the Redgifs and imgur fixes. Routes Sync's Volley traffic through "
                    + "the extension.",
            false,
            builder -> {
                Constants.extend(builder);
                Constants.compatibleWith(builder);
                builder.apply(context -> {
                    install(context);
                    return Unit.INSTANCE;
                });
                return Unit.INSTANCE;
            });

    private NetworkPatch() {
    }

    private static void install(BytecodePatchContext context) {
        // Volley is not obfuscated, but Sync Pro may still be renamed; fall back to the shape of the class.
        MutableClassDef classDef = PatchUtil.bySourceFileAndMethod(context, "BasicNetwork.java",
                "performRequest", Constants.VOLLEY_REQUEST);
        if (classDef == null) {
            classDef = PatchUtil.byType(context, "Lcom/android/volley/toolbox/BasicNetwork;");
        }
        if (classDef == null) {
            classDef = PatchUtil.byMethod(context, "performRequest", Constants.NETWORK_RESPONSE,
                    Constants.VOLLEY_REQUEST);
        }
        if (classDef == null) {
            throw PatchUtil.error("Volley's BasicNetwork.performRequest was not found");
        }
        MutableMethod method = PatchUtil.method(classDef, "performRequest");

        List<BuilderInstruction> instructions = PatchUtil.instructions(method);
        int resultIndex = -1;
        int register = -1;
        for (int i = 0; i + 1 < instructions.size(); i++) {
            BuilderInstruction instruction = instructions.get(i);
            if (instruction.getOpcode() != Opcode.INVOKE_STATIC
                    && instruction.getOpcode() != Opcode.INVOKE_STATIC_RANGE) {
                continue;
            }
            Reference reference = ((ReferenceInstruction) instruction).getReference();
            if (!(reference instanceof MethodReference)) {
                continue;
            }
            MethodReference called = (MethodReference) reference;
            if (!Constants.OKHTTP_CLIENT.equals(called.getReturnType())
                    || !called.getParameterTypes().isEmpty()) {
                continue;
            }
            BuilderInstruction next = instructions.get(i + 1);
            if (next.getOpcode() == Opcode.MOVE_RESULT_OBJECT) {
                resultIndex = i + 1;
                register = ((OneRegisterInstruction) next).getRegisterA();
                break;
            }
        }
        if (resultIndex < 0) {
            throw PatchUtil.error("BasicNetwork.performRequest does not call an OkHttpClient getter");
        }

        PatchUtil.addInstructions(method, resultIndex + 1,
                "invoke-static/range { v" + register + " .. v" + register + " }, "
                        + Constants.EXTENSION_PACKAGE + "/SyncNetwork;->install("
                        + Constants.OKHTTP_CLIENT + ")" + Constants.OKHTTP_CLIENT + "\n"
                        + "move-result-object v" + register);
    }
}
