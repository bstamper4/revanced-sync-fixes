package app.revanced.extension.syncfixes;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Sync loads a Redgifs video with three requests: an OAuth token from /v2/oauth/client, the client IP
 * from /info, then /v2/gifs/{id}. Redgifs removed the first two endpoints, so the chain fails with
 * "Error connecting to Redgifs". This answers both locally, using Redgifs' current temporary token, and
 * retries /v2/gifs with a fresh token when Redgifs rejects the one it has (tokens are tied to the IP
 * address that requested them).
 */
final class RedgifsApi {
    private static final String HOST = "api.redgifs.com";
    /** Overridable so the tests can point the token fetch at a local server. */
    static String temporaryTokenUrl = "https://" + HOST + "/v2/auth/temporary";
    /** Redgifs tokens last a day; refresh an hour early. */
    private static final long TOKEN_LIFETIME_MILLIS = 23 * 60 * 60 * 1000L;
    private static final MediaType JSON = MediaType.get("application/json");
    /** Used only when a request carries no user agent of its own. */
    private static final String FALLBACK_USER_AGENT =
            "android:com.laurencedawson.reddit_sync:vv23.06.30-13:39 (by /u/ljdawson)";

    /** Tokens by user agent: Redgifs checks that requests use the user agent the token was issued to. */
    private static final Map<String, Token> TOKENS = new HashMap<>();

    private RedgifsApi() {
    }

    static Response intercept(Interceptor.Chain chain) throws IOException {
        Request request = chain.request();
        String path = request.url().encodedPath();

        // Only used to fill the legacy user-addr parameter, which Redgifs now ignores.
        if (path.equals("/info")) {
            return jsonResponse(request, "{\"remote-addr\":\"0.0.0.0\"}");
        }

        String userAgent = userAgent(request);
        boolean forceRefresh = false;
        if (request.header("Authorization") != null && !path.equals("/v2/oauth/client")) {
            Response response = chain.proceed(request.newBuilder().header("User-Agent", userAgent).build());
            if (response.isSuccessful()) {
                return response;
            }
            Log.w(Responses.TAG, "Redgifs " + path + " returned " + response.code() + ", refreshing token");
            // A later interceptor may have replaced the user agent: the token must match the one sent.
            String sentUserAgent = response.request().header("User-Agent");
            if (sentUserAgent != null && !sentUserAgent.isEmpty()) {
                userAgent = sentUserAgent;
            }
            response.close();
            forceRefresh = true;
        }

        Token token = token(userAgent, forceRefresh);
        if (path.equals("/v2/oauth/client")) {
            try {
                return jsonResponse(request, new JSONObject()
                        .put("access_token", token.value)
                        .put("expiry_time", (token.expiresAtMillis - System.currentTimeMillis()) / 1000)
                        .put("scope", "read")
                        .put("token_type", "Bearer")
                        .toString());
            } catch (org.json.JSONException e) {
                throw new IOException("Could not build Redgifs token response", e);
            }
        }
        return chain.proceed(request.newBuilder()
                .header("Authorization", "Bearer " + token.value)
                .header("User-Agent", userAgent)
                .build());
    }

    private static String userAgent(Request request) {
        String userAgent = request.header("User-Agent");
        return userAgent == null || userAgent.isEmpty() ? FALLBACK_USER_AGENT : userAgent;
    }

    private static Token token(String userAgent, boolean forceRefresh) throws IOException {
        synchronized (TOKENS) {
            Token token = TOKENS.get(userAgent);
            if (!forceRefresh && token != null && token.expiresAtMillis > System.currentTimeMillis()) {
                return token;
            }
            HttpURLConnection connection =
                    (HttpURLConnection) new URL(temporaryTokenUrl).openConnection();
            try {
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", userAgent);
                connection.setRequestProperty("Accept", "application/json");
                connection.setUseCaches(false);
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Redgifs temporary token request returned " + code);
                }
                String value = new JSONObject(read(connection.getInputStream())).getString("token");
                token = new Token(value, System.currentTimeMillis() + TOKEN_LIFETIME_MILLIS);
            } catch (org.json.JSONException e) {
                throw new IOException("Could not read Redgifs token", e);
            } finally {
                connection.disconnect();
            }
            TOKENS.put(userAgent, token);
            return token;
        }
    }

    static Response jsonResponse(Request request, String body) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(HttpURLConnection.HTTP_OK)
                .message("OK")
                .header("Content-Type", "application/json")
                .body(ResponseBody.create(body, JSON))
                .build();
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            for (int n; (n = in.read(buffer)) >= 0; ) {
                out.write(buffer, 0, n);
            }
        } finally {
            in.close();
        }
        return out.toString("UTF-8");
    }

    private static final class Token {
        final String value;
        final long expiresAtMillis;

        Token(String value, long expiresAtMillis) {
            this.value = value;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
