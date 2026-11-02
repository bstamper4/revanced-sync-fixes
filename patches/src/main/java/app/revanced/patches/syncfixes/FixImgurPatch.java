package app.revanced.patches.syncfixes;

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.Patch;
import app.revanced.patcher.patch.PatchKt;
import kotlin.Unit;

/**
 * Two imgur problems. Links that put the title in the path (/a/some-title-AbC123) lose their id, because
 * Sync keeps everything before the first dash; the id is the part after the last one. And Sync's imgur
 * metadata host, images.syncforreddit.com, no longer resolves; the network fix answers its album and
 * single image requests from imgur itself.
 */
public final class FixImgurPatch {
    @SuppressWarnings("unused")
    public static final Patch INSTANCE = PatchKt.bytecodePatch(
            "Fix imgur albums",
            "Fixes imgur albums and images: imgur links with the title in the path "
                    + "(imgur.com/a/some-title-AbC123) are resolved to the right id, and the metadata host "
                    + "Sync uses, which no longer resolves, is answered from imgur directly.",
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

    private FixImgurPatch() {
    }

    private static void patch(BytecodePatchContext context) {
        MutableMethod idOfUrl = null;
        for (MutableClassDef classDef : PatchUtil.bySourceFile(context, "ImgurImageHelper.java")) {
            for (MutableMethod method : classDef.getMethods()) {
                if (PatchUtil.isStatic(method)
                        && Constants.STRING.equals(method.getReturnType())
                        && PatchUtil.hasParameters(method, Constants.STRING)
                        && PatchUtil.hasString(method, "horizontal")
                        && PatchUtil.hasString(method, "layout")) {
                    idOfUrl = method;
                    break;
                }
            }
        }
        if (idOfUrl == null) {
            throw PatchUtil.error("ImgurImageHelper's URL to id method was not found");
        }

        // Take the album id from after the last dash, for the title in the path links.
        PatchUtil.addInstructions(idOfUrl, 0,
                "invoke-static/range { p0 .. p0 }, " + Constants.EXTENSION_PACKAGE
                        + "/ImgurLinks;->stripTitle(" + Constants.STRING + ")" + Constants.STRING + "\n"
                        + "move-result-object p0");
    }
}
