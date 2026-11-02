package app.revanced.extension.syncfixes;

/**
 * Newer imgur links put the album title in the path (/a/some-album-title-AbC123). Sync's id parser keeps
 * everything before the first dash, so it looks up an album called "some". The id is the part after the
 * last dash; plain ids never contain one.
 */
@SuppressWarnings("unused")
public final class ImgurLinks {
    private ImgurLinks() {
    }

    /** Hooked into the start of ImgurImageHelper's URL to id method. */
    public static String stripTitle(String url) {
        if (url == null) {
            return null;
        }
        int album = indexOfIgnoreCase(url, "/a/");
        int gallery = indexOfIgnoreCase(url, "/gallery/");
        int start;
        if (album < 0 || (gallery >= 0 && gallery < album)) {
            if (gallery < 0) {
                return url;
            }
            start = gallery + "/gallery/".length();
        } else {
            start = album + "/a/".length();
        }

        int end = start;
        while (end < url.length()) {
            char c = url.charAt(end);
            if (c == '/' || c == '?' || c == '#') {
                break;
            }
            end++;
        }
        String segment = url.substring(start, end);
        int dash = segment.lastIndexOf('-');
        if (dash < 0 || dash == segment.length() - 1) {
            return url;
        }
        return url.substring(0, start) + segment.substring(dash + 1) + url.substring(end);
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        int max = haystack.length() - needle.length();
        for (int i = 0; i <= max; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }
}
