package app.revanced.patches.syncfixes;

import java.util.List;

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.Patch;
import app.revanced.patcher.patch.PatchKt;
import com.android.tools.smali.dexlib2.builder.BuilderInstruction;
import kotlin.Unit;

/**
 * Sync loads every Redgifs video through /v2/oauth/client and /info, both removed by Redgifs. The
 * interceptor answers them with Redgifs' temporary token, and the gif id is taken from the URL with the
 * query removed first, so links like redgifs.com/watch/{id}/?92 work.
 */
public final class FixRedgifsPatch {
    @SuppressWarnings("unused")
    public static final Patch INSTANCE = PatchKt.bytecodePatch(
            "Fix Redgifs",
            "Fixes \"Error connecting to Redgifs\": answers the Redgifs endpoints Sync relies on that were "
                    + "removed, uses Redgifs' temporary token, and fixes video ids taken from links like "
                    + "redgifs.com/watch/{id}/?92. Can be used together with the official Redgifs patch.",
            true,
            builder -> {
                Constants.extend(builder);
                Constants.compatibleWith(builder);
                builder.dependsOn(NetworkPatch.INSTANCE);
                builder.apply(context -> {
                    patch(context);
                    return Unit.INSTANCE;
                });
                return Unit.INSTANCE;
            });

    private FixRedgifsPatch() {
    }

    private static void patch(BytecodePatchContext context) {
        MutableMethod gfycatId = null;
        for (MutableClassDef classDef : PatchUtil.bySourceFile(context, "LinkHandler.java")) {
            for (MutableMethod method : classDef.getMethods()) {
                if (PatchUtil.isStatic(method)
                        && Constants.STRING.equals(method.getReturnType())
                        && PatchUtil.hasParameters(method, Constants.STRING)
                        && PatchUtil.hasString(method, "-size_restricted")
                        && PatchUtil.hasString(method, "-max-1mb-poster")) {
                    gfycatId = method;
                    break;
                }
            }
        }
        if (gfycatId == null) {
            throw PatchUtil.error("LinkHandler.getGfycatId was not found");
        }

        List<BuilderInstruction> instructions = PatchUtil.instructions(gfycatId);
        if (instructions.isEmpty()) {
            throw PatchUtil.error("LinkHandler.getGfycatId is empty");
        }
        PatchUtil.addInstructions(gfycatId, 0,
                "invoke-static/range { p0 .. p0 }, " + Constants.EXTENSION_PACKAGE
                        + "/RedgifsLinks;->gifId(" + Constants.STRING + ")" + Constants.STRING + "\n"
                        + "move-result-object p0\n"
                        + "return-object p0");
    }
}
