package app.revanced.patches.syncfixes;

import java.io.InputStream;
import java.util.Collections;
import java.util.Set;

import app.revanced.patcher.patch.BytecodePatchBuilder;
import app.revanced.patcher.patch.PatchException;
import kotlin.Pair;
import kotlin.jvm.functions.Function0;

/** Shared constants and builder helpers for the Sync for Reddit fixes. */
public final class Constants {
    /** Sync's final release; the same version name in the free, Pro and dev apps. */
    public static final String VERSION = "v23.06.30-13:39";

    public static final String EXTENSION = "extensions/syncfixes.rve";
    public static final String EXTENSION_PACKAGE = "Lapp/revanced/extension/syncfixes";

    public static final String NETWORK_RESPONSE = "Lcom/android/volley/NetworkResponse;";
    public static final String VOLLEY_RESPONSE = "Lcom/android/volley/Response;";
    public static final String VOLLEY_REQUEST = "Lcom/android/volley/Request;";
    public static final String OKHTTP_CLIENT = "Lokhttp3/OkHttpClient;";
    public static final String STRING = "Ljava/lang/String;";

    private static final Set<String> VERSIONS = Collections.singleton(VERSION);

    private static final String[] PACKAGES = {
            "com.laurencedawson.reddit_sync",
            "com.laurencedawson.reddit_sync.pro",
            "com.laurencedawson.reddit_sync.dev",
    };

    private Constants() {
    }

    /** Adds the extension to the patch. */
    public static void extend(BytecodePatchBuilder builder) {
        builder.extendWith((Function0<InputStream>) () -> {
            InputStream stream = Constants.class.getClassLoader().getResourceAsStream(EXTENSION);
            if (stream == null) {
                throw PatchUtil.error("Extension not found in the patches file: " + EXTENSION);
            }
            return stream;
        });
    }

    /** Restricts the patch to the three Sync packages at the supported version. */
    @SuppressWarnings("unchecked")
    public static void compatibleWith(BytecodePatchBuilder builder) {
        Pair<String, Set<String>>[] packages = new Pair[PACKAGES.length];
        for (int i = 0; i < PACKAGES.length; i++) {
            packages[i] = new Pair<>(PACKAGES[i], VERSIONS);
        }
        builder.compatibleWith(packages);
    }
}
