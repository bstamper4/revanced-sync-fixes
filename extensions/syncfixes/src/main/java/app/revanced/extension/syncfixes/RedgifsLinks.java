package app.revanced.extension.syncfixes;

/**
 * Sync's LinkHandler.getGfycatId builds the /v2/gifs/{id} URL. The original strips a trailing slash
 * before the query, so "redgifs.com/watch/{id}/?92/" gives an empty id and a 404. Here the query and
 * fragment go first; the suffix cleanup is the original's.
 */
@SuppressWarnings("unused")
public final class RedgifsLinks {
    private RedgifsLinks() {
    }

    public static String gifId(String url) {
        if (url == null) {
            return null;
        }
        int query = url.indexOf('?');
        if (query >= 0) {
            url = url.substring(0, query);
        }
        int fragment = url.indexOf('#');
        if (fragment >= 0) {
            url = url.substring(0, fragment);
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String id = url.substring(url.lastIndexOf('/') + 1)
                .replace(".gif", "").replace(".mp4", "").replace(".webm", "")
                .replace("-mobile", "").replace("-size_restricted", "").replace("-max-1mb-poster", "");
        return id.contains("-") ? id.split("-")[0] : id;
    }
}
