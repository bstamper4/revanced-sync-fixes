package app.revanced.extension.syncfixes;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Sync loads imgur albums from its own metadata host, images.syncforreddit.com, which no longer resolves.
 * The host returned {"data":{"images":[...]},"position":n} for albums and {"data":{"link":...}} for single
 * images. The same data is assembled here from imgur's API, and from the album page when the API refuses
 * the request (it answers 429 often).
 */
final class ImgurApi {
    private static final String HOST = "images.syncforreddit.com";
    /** The Client-ID Sync itself sends with its imgur requests. */
    private static final String CLIENT_ID = "981c2f80e996ca3";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0 Mobile Safari/537.36";
    private static final MediaType JSON = MediaType.get("application/json");

    /** Overridable so the tests can point the fetches at a local server; production values never change. */
    static String apiBase = "https://api.imgur.com";
    static String webBase = "https://imgur.com";

    private ImgurApi() {
    }

    static Response intercept(Interceptor.Chain chain) throws IOException {
        Request request = chain.request();
        String path = request.url().encodedPath();
        String[] parts = path.split("/");
        // "/a/{md5}/{albumId}" or "/image/{md5}/{imageId}".
        if (parts.length < 4) {
            return chain.proceed(request);
        }
        String kind = parts[1];
        String id = parts[parts.length - 1];

        String body;
        if ("a".equals(kind)) {
            body = album(id);
        } else if ("image".equals(kind)) {
            body = image(id);
        } else {
            return chain.proceed(request);
        }
        if (body == null) {
            Log.w(Responses.TAG, "Could not load imgur " + kind + ": " + id);
            return error(request);
        }
        return json(request, body);
    }

    /** Album metadata in the shape Sync's ImgurGalleryRequest parses. */
    private static String album(String id) {
        JSONObject data = api(apiBase + "/3/album/" + id);
        if (data != null) {
            JSONArray images = data.optJSONArray("images");
            JSONArray out = fromApiImages(images);
            if (out != null && out.length() > 0) {
                return albumJson(out);
            }
        }

        JSONObject page = postData(get(webBase + "/a/" + id, null));
        if (page != null) {
            JSONArray media = page.optJSONArray("media");
            if (media == null) {
                media = page.optJSONArray("images");
            }
            JSONArray out = fromMedia(media);
            if (out != null && out.length() > 0) {
                return albumJson(out);
            }
        }
        return null;
    }

    /** Single image metadata in the shape Sync's ImgurSingleImageRequest parses. */
    private static String image(String id) {
        JSONObject data = api(apiBase + "/3/image/" + id);
        if (data != null) {
            String link = data.optString("link");
            if (!isEmpty(link)) {
                return imageJson(link, data.optString("title"), data.optString("description"));
            }
        }

        String html = get(webBase + "/" + id, null);
        String link = meta(html, "og:image");
        if (link != null) {
            return imageJson(link, meta(html, "og:title"), meta(html, "og:description"));
        }
        return null;
    }

    private static JSONArray fromApiImages(JSONArray images) {
        if (images == null) {
            return null;
        }
        JSONArray out = new JSONArray();
        for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i);
            if (image == null) {
                continue;
            }
            String link = image.optString("link");
            if (isEmpty(link)) {
                continue;
            }
            try {
                JSONObject item = new JSONObject();
                item.put("link", link);
                String mp4 = image.optString("mp4");
                if (!isEmpty(mp4)) {
                    item.put("mp4", mp4);
                }
                item.put("width", Math.max(1, image.optInt("width")));
                item.put("height", Math.max(1, image.optInt("height")));
                item.put("title", image.optString("title"));
                item.put("description", image.optString("description"));
                out.put(item);
            } catch (JSONException e) {
                Log.w(Responses.TAG, "Could not convert imgur image", e);
            }
        }
        return out;
    }

    private static JSONArray fromMedia(JSONArray media) {
        if (media == null) {
            return null;
        }
        JSONArray out = new JSONArray();
        for (int i = 0; i < media.length(); i++) {
            JSONObject image = media.optJSONObject(i);
            if (image == null) {
                continue;
            }
            String link = image.optString("url");
            if (isEmpty(link)) {
                link = image.optString("link");
            }
            if (isEmpty(link)) {
                continue;
            }
            JSONObject metadata = image.optJSONObject("metadata");
            try {
                JSONObject item = new JSONObject();
                item.put("link", link);
                if ("video".equalsIgnoreCase(image.optString("type"))) {
                    item.put("mp4", link);
                }
                item.put("width", Math.max(1, image.optInt("width")));
                item.put("height", Math.max(1, image.optInt("height")));
                item.put("title", metadata == null ? "" : metadata.optString("title"));
                item.put("description", metadata == null ? "" : metadata.optString("description"));
                out.put(item);
            } catch (JSONException e) {
                Log.w(Responses.TAG, "Could not convert imgur media", e);
            }
        }
        return out;
    }

    private static String albumJson(JSONArray images) {
        try {
            return new JSONObject()
                    .put("data", new JSONObject().put("images", images))
                    .put("position", 0)
                    .toString();
        } catch (JSONException e) {
            return null;
        }
    }

    private static String imageJson(String link, String title, String description) {
        try {
            return new JSONObject()
                    .put("data", new JSONObject()
                            .put("link", link)
                            .put("title", title)
                            .put("description", description))
                    .put("position", 0)
                    .toString();
        } catch (JSONException e) {
            return null;
        }
    }

    private static JSONObject api(String url) {
        String body = get(url, "Client-ID " + CLIENT_ID);
        if (body == null) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(body);
            return json.optBoolean("success") ? json.optJSONObject("data") : null;
        } catch (JSONException e) {
            return null;
        }
    }

    private static String get(String url, String authorization) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.8");
            if (authorization != null) {
                connection.setRequestProperty("Authorization", authorization);
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            return read(connection.getInputStream());
        } catch (IOException e) {
            Log.w(Responses.TAG, "imgur request failed: " + url, e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            for (int n; (n = in.read(buffer)) >= 0; ) {
                out.write(buffer, 0, n);
            }
        } finally {
            in.close();
        }
        return out.toString("UTF-8");
    }

    /**
     * imgur pages carry their state in window.postDataJSON, a JavaScript string literal holding JSON.
     */
    private static JSONObject postData(String html) {
        if (html == null) {
            return null;
        }
        int key = html.indexOf("window.postDataJSON");
        if (key < 0) {
            return null;
        }
        int equals = html.indexOf('=', key);
        if (equals < 0) {
            return null;
        }
        int start = equals + 1;
        while (start < html.length() && Character.isWhitespace(html.charAt(start))) {
            start++;
        }
        if (start >= html.length()) {
            return null;
        }
        try {
            if (html.charAt(start) == '"') {
                int end = start + 1;
                boolean escaped = false;
                while (end < html.length()) {
                    char c = html.charAt(end);
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        break;
                    }
                    end++;
                }
                if (end >= html.length()) {
                    return null;
                }
                return new JSONObject(unescapeJs(html.substring(start + 1, end)));
            }
            if (html.charAt(start) == '{') {
                int end = matchingBrace(html, start);
                return end < 0 ? null : new JSONObject(html.substring(start, end + 1));
            }
        } catch (JSONException e) {
            Log.w(Responses.TAG, "Could not parse imgur page", e);
        }
        return null;
    }

    private static int matchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Decodes a JavaScript string literal body into the JSON text it holds. */
    private static String unescapeJs(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '\\') {
                out.append(c);
                i++;
                continue;
            }
            i++;
            if (i >= text.length()) {
                break;
            }
            char escape = text.charAt(i);
            switch (escape) {
                case '"':
                case '\'':
                case '\\':
                case '/':
                    out.append(escape);
                    i++;
                    break;
                case 'b':
                    out.append('\b');
                    i++;
                    break;
                case 'f':
                    out.append('\f');
                    i++;
                    break;
                case 'n':
                    out.append('\n');
                    i++;
                    break;
                case 'r':
                    out.append('\r');
                    i++;
                    break;
                case 't':
                    out.append('\t');
                    i++;
                    break;
                case 'u':
                    if (i + 4 < text.length()) {
                        try {
                            out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                            i += 5;
                        } catch (NumberFormatException e) {
                            out.append('u');
                            i++;
                        }
                    } else {
                        i++;
                    }
                    break;
                case 'x':
                    if (i + 2 < text.length()) {
                        try {
                            out.append((char) Integer.parseInt(text.substring(i + 1, i + 3), 16));
                            i += 3;
                        } catch (NumberFormatException e) {
                            out.append('x');
                            i++;
                        }
                    } else {
                        i++;
                    }
                    break;
                default:
                    out.append(escape);
                    i++;
                    break;
            }
        }
        return out.toString();
    }

    private static String meta(String html, String property) {
        if (html == null) {
            return null;
        }
        int from = 0;
        while (true) {
            int tag = html.indexOf("<meta", from);
            if (tag < 0) {
                return null;
            }
            int end = html.indexOf('>', tag);
            if (end < 0) {
                return null;
            }
            String tagText = html.substring(tag, end);
            if (tagText.contains("property=\"" + property + "\"")
                    || tagText.contains("name=\"" + property + "\"")) {
                int content = tagText.indexOf("content=\"");
                if (content < 0) {
                    from = end;
                    continue;
                }
                int valueStart = content + "content=\"".length();
                int valueEnd = tagText.indexOf('"', valueStart);
                if (valueEnd < 0) {
                    from = end;
                    continue;
                }
                String value = tagText.substring(valueStart, valueEnd)
                        .replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"");
                int query = value.indexOf("?");
                return query < 0 ? value : value.substring(0, query);
            }
            from = end;
        }
    }

    private static Response json(Request request, String body) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "application/json")
                .body(ResponseBody.create(body, JSON))
                .build();
    }

    private static Response error(Request request) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .header("Content-Type", "application/json")
                .body(ResponseBody.create("{}", JSON))
                .build();
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
