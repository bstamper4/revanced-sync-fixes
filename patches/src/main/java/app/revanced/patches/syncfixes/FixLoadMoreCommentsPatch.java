package app.revanced.patches.syncfixes;

import java.util.List;

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.Patch;
import app.revanced.patcher.patch.PatchKt;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.BuilderInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import kotlin.Unit;

/**
 * Reddit blocks /api/morechildren, so "load more comments" fails. Sync's request is sent to
 * /comments/{link}/_/{parent}, the endpoint it already uses for single comment threads, and the reply is
 * cut down to the flat list morechildren returned.
 */
public final class FixLoadMoreCommentsPatch {
    private static final String EXTENSION = Constants.EXTENSION_PACKAGE + "/LoadMoreComments;";

    @SuppressWarnings("unused")
    public static final Patch INSTANCE = PatchKt.bytecodePatch(
            "Fix load more comments",
            "Fixes \"Error loading more comments\" because Reddit blocked /api/morechildren for third party "
                    + "clients. The missing comments are loaded from /comments instead. At the bottom of very "
                    + "large threads Reddit's 500 comment limit can still leave some out.",
            true,
            builder -> {
                Constants.extend(builder);
                Constants.compatibleWith(builder);
                builder.apply(context -> {
                    patch(context);
                    return Unit.INSTANCE;
                });
                return Unit.INSTANCE;
            });

    private FixLoadMoreCommentsPatch() {
    }

    private static void patch(BytecodePatchContext context) {
        MutableMethod handler = null;
        for (MutableClassDef classDef : PatchUtil.bySourceFile(context, "CommentsPresenterImpl.java")) {
            for (MutableMethod method : classDef.getMethods()) {
                if (method.getParameterTypes().size() == 1
                        && PatchUtil.hasString(method, "Requesting more: ")) {
                    handler = method;
                    break;
                }
            }
        }
        if (handler == null) {
            throw PatchUtil.error("CommentsPresenterImpl's load more handler was not found");
        }

        MutableClassDef requestClass = null;
        MutableMethod constructor = null;
        MutableMethod parseResponse = null;
        for (MutableClassDef classDef : PatchUtil.bySourceFile(context, "OAuthCommentsMoreRequest.java")) {
            for (MutableMethod method : classDef.getMethods()) {
                if (method.getName().equals("parseNetworkResponse")) {
                    parseResponse = method;
                } else if (method.getName().equals("<init>")
                        && PatchUtil.hasString(method,
                        "api/morechildren?always_show_media=1&profile_img=true")) {
                    constructor = method;
                    requestClass = classDef;
                }
            }
        }
        if (constructor == null || parseResponse == null) {
            throw PatchUtil.error("OAuthCommentsMoreRequest no longer looks like the supported version");
        }

        // Remember the parent of the "more" item: the request itself is not given it.
        PatchUtil.addInstructions(handler, 0,
                "invoke-static/range { p1 .. p1 }, " + EXTENSION + "->setMoreItem(Ljava/lang/Object;)V");

        patchConstructor(requestClass, constructor);
        patchResponse(parseResponse);
    }

    private static void patchConstructor(MutableClassDef classDef, MutableMethod constructor) {
        List<BuilderInstruction> instructions = PatchUtil.instructions(constructor);
        String superClass = classDef.getSuperclass();

        int callIndex = -1;
        int methodRegister = -1;
        int urlRegister = -1;
        for (int i = 0; i < instructions.size(); i++) {
            BuilderInstruction instruction = instructions.get(i);
            Opcode opcode = instruction.getOpcode();
            if (opcode != Opcode.INVOKE_DIRECT && opcode != Opcode.INVOKE_DIRECT_RANGE) {
                continue;
            }
            Reference reference = ((ReferenceInstruction) instruction).getReference();
            if (!(reference instanceof MethodReference)) {
                continue;
            }
            MethodReference called = (MethodReference) reference;
            if (!called.getName().equals("<init>") || !called.getDefiningClass().equals(superClass)) {
                continue;
            }
            List<? extends CharSequence> parameters = called.getParameterTypes();
            if (parameters.size() != 5
                    || !parameters.get(0).toString().equals("Landroid/content/Context;")
                    || !parameters.get(1).toString().equals("I")
                    || !parameters.get(2).toString().equals(Constants.STRING)
                    || !parameters.get(3).toString().equals("Lcom/android/volley/Response$Listener;")
                    || !parameters.get(4).toString().equals("Lcom/android/volley/Response$ErrorListener;")) {
                continue;
            }
            if (instruction instanceof RegisterRangeInstruction) {
                int start = ((RegisterRangeInstruction) instruction).getStartRegister();
                methodRegister = start + 2;
                urlRegister = start + 3;
            } else if (instruction instanceof FiveRegisterInstruction) {
                methodRegister = ((FiveRegisterInstruction) instruction).getRegisterE();
                urlRegister = ((FiveRegisterInstruction) instruction).getRegisterF();
            } else {
                throw PatchUtil.error("Unexpected super constructor call: " + opcode);
            }
            callIndex = i;
            break;
        }
        if (callIndex < 0) {
            throw PatchUtil.error("The RedditRequest super constructor call was not found");
        }

        String methodCall = urlRegister == methodRegister + 1
                ? "invoke-static/range { v" + methodRegister + " .. v" + urlRegister + " }"
                : "invoke-static { v" + methodRegister + ", v" + urlRegister + " }";

        // Swap the morechildren POST for a GET of /comments right before the super constructor uses them.
        PatchUtil.addInstructions(constructor, callIndex,
                "invoke-static/range { v" + urlRegister + " .. v" + urlRegister + " }, " + EXTENSION
                        + "->moreUrl(" + Constants.STRING + ")" + Constants.STRING + "\n"
                        + "move-result-object v" + urlRegister + "\n"
                        + methodCall + ", " + EXTENSION + "->moreMethod(I" + Constants.STRING + ")I\n"
                        + "move-result v" + methodRegister);

        // Hand over link id (p3), children (p5) and sort (p8). v0-v2 are written by the constructor before
        // it reads them.
        int locals = constructor.getImplementation().getRegisterCount()
                - constructor.getParameterTypes().size() - 1;
        if (locals < 3) {
            throw PatchUtil.error("Expected at least 3 local registers, found " + locals);
        }
        PatchUtil.addInstructions(constructor, 0,
                "move-object/from16 v0, p3\n"
                        + "move-object/from16 v1, p5\n"
                        + "move-object/from16 v2, p8\n"
                        + "invoke-static { v0, v1, v2 }, " + EXTENSION
                        + "->setMoreArgs(" + Constants.STRING + Constants.STRING + Constants.STRING + ")V");
    }

    private static void patchResponse(MutableMethod parseResponse) {
        PatchUtil.addInstructions(parseResponse, 0,
                "invoke-virtual/range { p0 .. p0 }, " + Constants.VOLLEY_REQUEST + "->getUrl()"
                        + Constants.STRING + "\n"
                        + "move-result-object v0\n"
                        + "move-object/from16 v1, p1\n"
                        + "invoke-static { v0, v1 }, " + EXTENSION + "->moreResponse("
                        + Constants.STRING + Constants.NETWORK_RESPONSE + ")" + Constants.NETWORK_RESPONSE + "\n"
                        + "move-result-object v1\n"
                        + "move-object/from16 p1, v1");
    }
}
