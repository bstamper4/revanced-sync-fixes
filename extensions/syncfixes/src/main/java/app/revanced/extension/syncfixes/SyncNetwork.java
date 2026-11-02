package app.revanced.extension.syncfixes;

import android.util.Log;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Installed on the OkHttp client Volley uses, which Sandbox requests (Redgifs, imgur metadata) also go
 * through. The hook is the client getter call in BasicNetwork.performRequest, so it composes with the
 * official ReVanced Redgifs patch, which patches the same client's builder instead.
 */
@SuppressWarnings("unused")
public final class SyncNetwork {
    private static final SyncInterceptor INTERCEPTOR = new SyncInterceptor();

    private static OkHttpClient lastClient;
    private static OkHttpClient lastWrapped;

    private SyncNetwork() {
    }

    /** Wraps the client so every Volley request passes through the fixes. */
    public static synchronized OkHttpClient install(OkHttpClient client) {
        if (client == null) {
            return null;
        }
        if (client == lastClient) {
            return lastWrapped;
        }
        OkHttpClient wrapped = client.newBuilder().addInterceptor(INTERCEPTOR).build();
        lastClient = client;
        lastWrapped = wrapped;
        Log.i(Responses.TAG, "network fixes installed");
        return wrapped;
    }

    private static final class SyncInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            String host = request.url().host();
            if ("api.redgifs.com".equals(host)) {
                Log.i(Responses.TAG, "Redgifs: " + request.url().encodedPath());
                return RedgifsApi.intercept(chain);
            }
            if ("images.syncforreddit.com".equals(host)) {
                Log.i(Responses.TAG, "imgur: " + request.url().encodedPath());
                return ImgurApi.intercept(chain);
            }
            return chain.proceed(request);
        }
    }
}
