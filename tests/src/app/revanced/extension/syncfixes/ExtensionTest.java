package app.revanced.extension.syncfixes;

import com.android.volley.NetworkResponse;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;

/** Runs the extension's pure logic off-device. */
public final class ExtensionTest {
    private static int failures;
    private static String fixture = "tests/fixtures/album.html";

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            fixture = args[0];
        }
        galleries();
        moreComments();
        redgifsLinks();
        imgurLinks();
        imgurPageParsing();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void galleries() throws Exception {
        eq("https://oauth.reddit.com/comments/abc123.json?limit=1",
                Galleries.galleryUrl("https://oauth.reddit.com/api/info.json?id=t3_abc123"));
        eq("https://oauth.reddit.com/comments/abc123.json?limit=1",
                Galleries.galleryUrl("https://oauth.reddit.com/api/info.json?id=t3_abc123&is_gallery=true"));
        eq("https://oauth.reddit.com/comments/abc123.json?limit=1",
                Galleries.galleryUrl("https://oauth.reddit.com/api/info/?id=t3_abc123&raw_json=1"));
        eq("https://oauth.reddit.com/comments/abc123.json?limit=1",
                Galleries.galleryUrl("https://oauth.reddit.com/api/info?id=t3_abc123&is_gallery=true"));
        eq("https://oauth.reddit.com/comments/x.json",
                Galleries.galleryUrl("https://oauth.reddit.com/comments/x.json"));

        NetworkResponse listing = response("[{\"kind\":\"Listing\",\"data\":{\"children\":[]}}]");
        String converted = new String(Galleries.galleryResponse(listing).data, StandardCharsets.UTF_8);
        check(new JSONObject(converted).has("data"), "listing unwrapped: " + converted);
        check(!converted.trim().startsWith("["), "listing is an object: " + converted);
        NetworkResponse already = response("{\"kind\":\"Listing\",\"data\":{\"children\":[]}}");
        eq(already, Galleries.galleryResponse(already));
    }

    private static void moreComments() throws Exception {
        LoadMoreComments.setMoreItem(
                "Post{id=c0, link_id=t3_link1, parent_id=t1_c0, body=hi, author=me}");
        LoadMoreComments.setMoreArgs("t3_link1", "c1,c9", "best");
        String url = LoadMoreComments.moreUrl(
                "https://oauth.reddit.com/api/morechildren?always_show_media=1&profile_img=true&api_type=json");
        check(url.startsWith("https://oauth.reddit.com/comments/link1/_/c0.json?"), "nested url: " + url);
        check(url.contains("limit=500"), "limit: " + url);
        check(url.contains("depth=10"), "depth: " + url);
        check(url.contains("sort=best"), "sort: " + url);
        check(url.contains("sync_fixes_more="), "key: " + url);
        eq(0, LoadMoreComments.moreMethod(1, url));
        eq(1, LoadMoreComments.moreMethod(1, "https://example.com/api/morechildren"));

        String body = "[{\"kind\":\"Listing\",\"data\":{\"children\":[]}},{\"kind\":\"Listing\",\"data\":"
                + "{\"children\":["
                + "{\"kind\":\"t1\",\"data\":{\"name\":\"t1_c0\",\"id\":\"c0\",\"parent_id\":\"t3_link1\","
                + "\"replies\":{\"kind\":\"Listing\",\"data\":{\"children\":["
                + "{\"kind\":\"t1\",\"data\":{\"name\":\"t1_c1\",\"id\":\"c1\",\"parent_id\":\"t1_c0\",\"replies\":\"\"}},"
                + "{\"kind\":\"more\",\"data\":{\"parent_id\":\"t1_c0\",\"children\":[\"c9\"]}}"
                + "]}}}}]}}]";
        String result = new String(LoadMoreComments.moreResponse(url, response(body)).data, StandardCharsets.UTF_8);
        JSONObject json = new JSONObject(result).getJSONObject("json").getJSONObject("data");
        String things = json.getJSONArray("things").toString();
        check(things.contains("\"t1_c1\""), "nested comment kept: " + things);
        check(things.contains("\"children\":[\"c9\"]"), "nested more kept: " + things);
        check(!things.contains("\"name\":\"t1_c0\""), "parent not duplicated: " + things);

        // Top level "load more": the comments are fetched by their own ids.
        LoadMoreComments.setMoreItem("Post{id=abc, link_id=t3_link1, parent_id=t3_link1, body=x}");
        LoadMoreComments.setMoreArgs("t3_link1", "c2", "new");
        String topUrl = LoadMoreComments.moreUrl(
                "https://oauth.reddit.com/api/morechildren?always_show_media=1&profile_img=true");
        check(topUrl.startsWith("https://oauth.reddit.com/comments/link1.json?"), "top url: " + topUrl);
        check(topUrl.contains("depth=2"), "top depth: " + topUrl);
        check(!topUrl.contains("/_/"), "no parent: " + topUrl);
        String topBody = "[{\"kind\":\"Listing\",\"data\":{\"children\":[]}},{\"kind\":\"Listing\",\"data\":"
                + "{\"children\":["
                + "{\"kind\":\"t1\",\"data\":{\"name\":\"t1_c2\",\"id\":\"c2\",\"parent_id\":\"t3_link1\",\"replies\":\"\"}},"
                + "{\"kind\":\"more\",\"data\":{\"parent_id\":\"t3_link1\",\"children\":[\"c3\"]}}"
                + "]}}]";
        String topResult = new String(LoadMoreComments.moreResponse(topUrl, response(topBody)).data,
                StandardCharsets.UTF_8);
        check(topResult.contains("\"t1_c2\""), "top comment kept: " + topResult);
        // A different "more" row at the top level is already in Sync's list; sending it again would show it twice.
        check(!topResult.contains("\"children\":[\"c3\"]"), "other top more not repeated: " + topResult);

        // Nothing new: Sync needs a data object to keep the row.
        LoadMoreComments.setMoreItem("Post{id=abc, link_id=t3_link1, parent_id=t1_gone, body=x}");
        LoadMoreComments.setMoreArgs("t3_link1", "zzz", "new");
        String goneUrl = LoadMoreComments.moreUrl("https://oauth.reddit.com/api/morechildren?x=1");
        String goneResult = new String(LoadMoreComments.moreResponse(goneUrl, response(topBody)).data,
                StandardCharsets.UTF_8);
        check(goneResult.contains("SYNC_FIXES"), "error keeps row: " + goneResult);
    }

    private static void redgifsLinks() {
        eq("abc123", RedgifsLinks.gifId("https://www.redgifs.com/watch/abc123"));
        eq("abc123", RedgifsLinks.gifId("https://www.redgifs.com/watch/abc123/?92"));
        eq("abc123", RedgifsLinks.gifId("https://www.redgifs.com/watch/abc123?92"));
        eq("abc123", RedgifsLinks.gifId("https://media.redgifs.com/abc123-mobile.mp4"));
        eq("abc123", RedgifsLinks.gifId("https://media.redgifs.com/abc123-size_restricted.mp4"));
        eq("abc123", RedgifsLinks.gifId("https://media.redgifs.com/abc123-max-1mb-poster.mp4"));
        eq("abc123", RedgifsLinks.gifId("https://www.redgifs.com/ifr/abc123"));
    }

    private static void imgurLinks() {
        eq("https://imgur.com/a/AbC123",
                ImgurLinks.stripTitle("https://imgur.com/a/some-album-title-AbC123"));
        eq("https://imgur.com/a/AbC123?x=1",
                ImgurLinks.stripTitle("https://imgur.com/a/some-album-title-AbC123?x=1"));
        eq("https://imgur.com/a/AbC123",
                ImgurLinks.stripTitle("https://imgur.com/a/AbC123"));
        eq("https://imgur.com/gallery/AbC123",
                ImgurLinks.stripTitle("https://imgur.com/gallery/my-title-AbC123"));
        eq("https://i.imgur.com/AbC123.png",
                ImgurLinks.stripTitle("https://i.imgur.com/AbC123.png"));
    }

    private static void imgurPageParsing() throws Exception {
        Method postData = ImgurApi.class.getDeclaredMethod("postData", String.class);
        postData.setAccessible(true);
        String album = new String(Files.readAllBytes(Paths.get(fixture)), StandardCharsets.UTF_8);
        JSONObject data = (JSONObject) postData.invoke(null, album);
        check(data != null, "album postDataJSON parsed");
        if (data != null) {
            check(data.has("media"), "album has media");
            check(data.getJSONArray("media").length() > 0, "album media items");
        }

        Method fromMedia = ImgurApi.class.getDeclaredMethod("fromMedia", org.json.JSONArray.class);
        fromMedia.setAccessible(true);
        org.json.JSONArray images = (org.json.JSONArray) fromMedia.invoke(null,
                data == null ? new org.json.JSONArray() : data.optJSONArray("media"));
        check(images.length() > 0, "media converted");
        JSONObject first = images.getJSONObject(0);
        check(!first.optString("link").isEmpty(), "media link");
        check(first.optInt("width") > 0, "media width: " + first);
        check(first.optInt("height") > 0, "media height: " + first);

        Method albumJson = ImgurApi.class.getDeclaredMethod("albumJson", org.json.JSONArray.class);
        albumJson.setAccessible(true);
        JSONObject albumOutput = new JSONObject((String) albumJson.invoke(null, images));
        check(albumOutput.getJSONObject("data").getJSONArray("images").length() > 0, "album shape");
        check(albumOutput.has("position"), "album position");

        Method imageJson = ImgurApi.class.getDeclaredMethod("imageJson", String.class, String.class,
                String.class);
        imageJson.setAccessible(true);
        JSONObject image = new JSONObject((String) imageJson.invoke(null, "https://i.imgur.com/x.png",
                "title", "description"));
        eq("https://i.imgur.com/x.png", image.getJSONObject("data").getString("link"));
        eq("title", image.getJSONObject("data").getString("title"));

        Method unescape = ImgurApi.class.getDeclaredMethod("unescapeJs", String.class);
        unescape.setAccessible(true);
        eq("{\"a\":\"b/c\"}", (String) unescape.invoke(null, "{\\\"a\\\":\\\"b\\/c\\\"}"));

        Method meta = ImgurApi.class.getDeclaredMethod("meta", String.class, String.class);
        meta.setAccessible(true);
        String html = "<meta property=\"og:image\" content=\"https://i.imgur.com/AbC123.png?fb\">"
                + "<meta name=\"twitter:image\" content=\"https://i.imgur.com/other.jpg\">";
        eq("https://i.imgur.com/AbC123.png", (String) meta.invoke(null, html, "og:image"));
        eq("https://i.imgur.com/other.jpg", (String) meta.invoke(null, html, "twitter:image"));
    }

    private static NetworkResponse response(String body) {
        return new NetworkResponse(200, body.getBytes(StandardCharsets.UTF_8), new HashMap<>(), false, 0);
    }

    private static void eq(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            failures++;
            System.out.println("FAIL: expected <" + expected + "> got <" + actual + ">");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + message);
        }
    }
}
