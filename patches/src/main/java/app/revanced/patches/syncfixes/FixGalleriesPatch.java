package app.revanced.patches.syncfixes;

import java.util.List;

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableClassDef;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.patcher.patch.Patch;
import app.revanced.patcher.patch.PatchKt;
import kotlin.Unit;

/**
 * Reddit blocks /api/info for third party clients, which is where Sync's gallery media_metadata comes
 * from. The post is loaded from /comments instead and the reply is unwrapped into the Listing Sync
 * expects.
 */
public final class FixGalleriesPatch {
    @SuppressWarnings("unused")
    public static final Patch INSTANCE = PatchKt.bytecodePatch(
            "Fix Reddit galleries",
            "Fixes Reddit image galleries failing to load (\"Could not load gallery\") because Reddit blocked "
                    + "/api/info for third party clients. Galleries are loaded from /comments instead.",
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

    private FixGalleriesPatch() {
    }

    private static void patch(BytecodePatchContext context) {
        MutableMethod urlBuilder = null;
        MutableMethod parseResponse = null;
        for (MutableClassDef classDef : PatchUtil.bySourceFile(context, "OAuthGalleryRequest.java")) {
            for (MutableMethod method : classDef.getMethods()) {
                if (method.getName().equals("parseNetworkResponse")) {
                    parseResponse = method;
                } else if (PatchUtil.isStatic(method)
                        && Constants.STRING.equals(method.getReturnType())
                        && PatchUtil.hasParameters(method, Constants.STRING)
                        && PatchUtil.hasString(method, "https://oauth.reddit.com/api/info.json?id=t3_")) {
                    urlBuilder = method;
                }
            }
        }
        if (urlBuilder == null || parseResponse == null) {
            throw PatchUtil.error("OAuthGalleryRequest no longer looks like the supported version");
        }

        // Send the request to /comments/{id}.json instead of /api/info.
        List<int[]> returns = PatchUtil.returnsObject(urlBuilder);
        if (returns.isEmpty()) {
            throw PatchUtil.error("The gallery URL builder has no return-object");
        }
        for (int i = returns.size() - 1; i >= 0; i--) {
            int index = returns.get(i)[0];
            int register = returns.get(i)[1];
            PatchUtil.replaceWithStringCall(urlBuilder, index, register,
                    Constants.EXTENSION_PACKAGE + "/Galleries", "galleryUrl");
        }

        // Unwrap the /comments reply into the Listing that Sync parses (and caches).
        PatchUtil.addInstructions(parseResponse, 0,
                "invoke-static/range { p1 .. p1 }, " + Constants.EXTENSION_PACKAGE
                        + "/Galleries;->galleryResponse(" + Constants.NETWORK_RESPONSE + ")"
                        + Constants.NETWORK_RESPONSE + "\n"
                        + "move-result-object p1");
    }
}
