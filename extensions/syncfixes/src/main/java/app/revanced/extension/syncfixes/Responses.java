package app.revanced.extension.syncfixes;

import com.android.volley.NetworkResponse;

import java.nio.charset.Charset;

/**
 * Helpers shared by the Volley level fixes.
 */
public final class Responses {
    public static final String TAG = "SyncFixes";

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private Responses() {
    }

    public static String body(NetworkResponse response) {
        return new String(response.data, UTF_8);
    }

    /**
     * A copy of the response with a new body. Volley caches what the request parses, so the copy is what
     * ends up in Sync's cache as well.
     */
    public static NetworkResponse withBody(NetworkResponse response, String body) {
        return new NetworkResponse(response.statusCode, body.getBytes(UTF_8), response.headers,
                response.notModified, response.networkTimeMs);
    }
}
