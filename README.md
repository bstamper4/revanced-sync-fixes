# revanced-sync-fixes

ReVanced patches for **Sync for Reddit v23.06.30-13:39** (the final release) that bring back what Reddit
and Redgifs switched off:

| Patch | What it fixes |
| --- | --- |
| Fix Reddit galleries | "Could not load gallery": Reddit blocks `/api/info`, so galleries and their captions are loaded from `/comments` instead. |
| Fix load more comments | "Error loading more comments": Reddit blocks `/api/morechildren`, so the comments are loaded from `/comments/{link}/_/{parent}` and cut back to the list Sync expects. |
| Fix Redgifs | "Error connecting to Redgifs": Sync's removed token, IP and `/info` calls are answered with Redgifs' temporary token, rejected tokens are refreshed, and ids in links like `redgifs.com/watch/{id}/?92` are parsed correctly. |
| Fix imgur albums | Albums and single images whose metadata came from `images.syncforreddit.com` (gone) are answered from imgur directly, and links with the title in the path (`imgur.com/a/some-title-AbC123`) resolve to the right id. |

Supported packages, all at `v23.06.30-13:39`:

- `com.laurencedawson.reddit_sync`
- `com.laurencedawson.reddit_sync.pro`
- `com.laurencedawson.reddit_sync.dev`

The patches are made to be used **together with the official ReVanced bundle**. Sync cannot reach
Reddit at all without the official **Spoof client** patch, so select it too. The official **Fix Redgifs
API** patch can be selected at the same time; this bundle wraps the OkHttp client at a different point,
so the two do not overwrite each other.

## Install with ReVanced Manager

Add the bundle as a remote patch source:

1. Patches → ✏️ → **+** → **Enter URL** and add
   `https://raw.githubusercontent.com/bstamper4/revanced-sync-fixes/main/bundles/stable.json`
2. Pick your own Sync APK (v23.06.30-13:39).
3. Select the official **Spoof client** patch, enter your Reddit OAuth client ID, and select the four
   patches from this bundle.
4. Patch and install. The patched app has a different signature, so uninstall the Play Store/APKMirror
   copy first (Sync's settings can be exported and imported again).

Alternatively, download `revanced-sync-fixes.rvp` from the
[release](https://github.com/bstamper4/revanced-sync-fixes/releases/latest) and add it from storage
(**+** → **Select from storage**). `scripts/serve-local.sh` serves the built bundle and a matching
manifest over the local network if you prefer not to use the public URL:

```sh
./scripts/serve-local.sh
# On the phone: Patches → ✏️ → + → Enter URL → http://192.168.1.10:8000/stable.json
```

## Install with ReVanced CLI

Download `revanced-sync-fixes.rvp` from the
[release](https://github.com/bstamper4/revanced-sync-fixes/releases/latest) (or use
`build/revanced-sync-fixes.rvp`). Use CLI 6.0.0 (or newer) and the official patches release for the
same Sync version:

```sh
java -jar revanced-cli-6.0.0-all.jar patch -b \
  -bp patches-<version>.rvp \
  -bp revanced-sync-fixes.rvp \
  -o sync-patched.apk sync-23.06.30.apk
```

`-b` (bypass verification) is required for this bundle: it is not PGP signed, and CLI 6 needs a
signature, key ring and attestation together to verify a bundle. Manager does not check signatures.

To use only this bundle and check the hooks (for example on a copy of the APK):

```sh
java -jar revanced-cli-6.0.0-all.jar patch -b --exclusive \
  -e 'Fix Reddit galleries' -e 'Fix load more comments' \
  -e 'Fix Redgifs' -e 'Fix imgur albums' \
  -p revanced-sync-fixes.rvp -o sync-patched.apk sync-23.06.30.apk
```

## Build from source

The ReVanced Gradle plugin is fetched from GitHub Packages, which needs credentials
(`gpr.user`/`gpr.key` in `gradle.properties`):

```sh
./gradlew buildAndroid   # build/libs/patches-1.0.0.rvp
```

Without GitHub Packages access, the same bundle is built by the portable scripts, which only need a
JDK:

```sh
./scripts/fetch-toolchain.sh /tmp/toolchain
JAVA_HOME=/tmp/toolchain/jdk-17* TOOLCHAIN=/tmp/toolchain ./scripts/build.sh
# build/revanced-sync-fixes.rvp
```

`buildAndroid` is required for Manager: it puts `classes.dex` inside the `.rvp`. The scripts do that
too.

The extension's logic runs off-device:

```sh
JAVA_HOME=/tmp/toolchain/jdk-17* TOOLCHAIN=/tmp/toolchain ./scripts/test-extension.sh
```

To hand the bundle to Manager over the local network, serve it with the generated manifest:

```sh
./scripts/serve-local.sh
```

## Verification status

| What | Status |
| --- | --- |
| Patches apply to the free build v23.06.30-13:39 | Verified |
| Hook sites in the patched DEX | Verified: all 10 injection points, 103 extension calls |
| Gallery, load-more, Redgifs and imgur logic | Verified off-device, in `scripts/test-extension.sh` |
| Combined with the official bundle, including **Spoof client** | Verified: official patches 6.0.0 and this bundle patch the APK in one run, and both extensions end up in the output |
| Running on a phone | Not done here: this machine has no Android device, emulator acceleration or binder |
| Sync Pro / dev | Not patched here: those APKs were not available; the same source names and code are assumed |

## Test on a device

The fixes log under the `SyncFixes` tag. To check that the hooks run after installing the patched APK:

```sh
adb logcat -c
adb logcat -s SyncFixes
```

Then open a post with a gallery, tap "load more comments" on a large thread, and open a Redgifs or
imgur link. Successful interception prints `network fixes installed` once and `Redgifs: /…` or
`imgur: /…` for each handled request; failures print a `Could not …` warning with the reason.

## How it works

Sync keeps its Java source file names, and its code is the same in the free, Pro and dev builds even
though the obfuscated class names differ. The patches therefore find classes by source file name and
the shape of their methods, not by fingerprints or hard-coded class names (Volley's own classes are
not obfuscated, but `BasicNetwork.performRequest` also has a fallback lookup).

- **Galleries**: `OAuthGalleryRequest`'s URL builder is changed from
  `oauth.reddit.com/api/info.json?id=t3_…` to `oauth.reddit.com/comments/….json?limit=1`, and
  `parseNetworkResponse` unwraps the comment listing's first element back into the Listing Sync parses
  (and caches).
- **Load more**: `CommentsPresenterImpl` records the parent of the clicked "more" row, the
  `OAuthCommentsMoreRequest` constructor is switched from a POST to a GET of
  `/comments/{link}/_/{parent}.json?limit=500`, and `parseNetworkResponse` walks the result into the
  flat `{"json":{"data":{"things":[…]}}}` list `morechildren` returned.
- **Redgifs**: the OkHttp client Volley uses is wrapped with an interceptor at
  `BasicNetwork.performRequest`. It answers `/info` and `/v2/oauth/client` locally from
  `api.redgifs.com/v2/auth/temporary`, retries `/v2/gifs` once with a fresh token, and the URL → id
  method in `LinkHandler` gets a stricter parser.
- **imgur**: the same interceptor answers `images.syncforreddit.com/a/<md5>/<id>` and
  `/image/<md5>/<id>` from `api.imgur.com/3/album|image/<id>`, falling back to the album page's
  `window.postDataJSON` (the API rate limits aggressively) and to `og:image` for single images. The URL
  → id method in `ImgurImageHelper` trims the title before the last dash.

## Limitations

- Reddit returns at most 500 comments per request. At the bottom of a very large thread some requested
  comments can be outside that window; Sync then keeps the "load more" row so it can be tapped again.
- imgur's API can answer `429`; the album page fallback then provides the images, without post titles.
- The APK patched during development was the free build. Pro and dev use the same code and source
  names, so the lookups work the same, but they were not patched in this environment.
- No Android device or emulator was available while building this, so the fixes were verified by
  patching the APK, disassembling the hook sites and running the extension logic on the JVM, not by
  using the app on a phone.

## License

GPL-3.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). This project is not affiliated with Sync for
Reddit, Reddit, Redgifs or imgur.
