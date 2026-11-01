# In-app browser — implementation notes

Feature branch: `feature/in-app-browser` (based on `main` @ bcba0be).

Adds a 1DM-style browse-and-grab flow to Hazel: an in-app browser that watches the
page for playable media and hands what it finds to the existing download sheet.
Hazel's own look and existing components are reused; no new dependencies.

## Why a branch, not a new repo

Hazel's README forbids third parties from shipping downloader apps (or derivatives)
under the Hazel name. Work inside this fork does not breach that; a second repo
named "Hazel" would.

## New files

| File | Purpose |
|---|---|
| `ui/screens/browser/DetectedMedia.kt` | Value type for one sniffed stream |
| `ui/screens/browser/MediaSniffer.kt` | JS bridge + request-level detection, de-duped by URL |
| `ui/screens/browser/BrowserViewModel.kt` | Tab state, history, detected-media list |
| `ui/screens/browser/BrowserScreen.kt` | Compose browser: URL bar, progress, download chip |
| `res/values/strings_browser.xml` | New strings (English default) |

## Detection layers

1. **JS bridge** (`MediaSniffer.JS`) — hooks `HTMLMediaElement.src`/`source` and
   `canPlayType`, `fetch`, `XMLHttpRequest` and `MediaSource.addSourceBuffer`, so
   playlist players that fetch segments by script are caught too.
2. **`shouldInterceptRequest`** — passive net for `/\.(m3u8|mpd|mp4|m4v|mov|webm|mkv|m4a|mp3)(\?|$)/`
   and `Content-Type: video/*` / `audio/*`.
3. **Link interception** — a media link tapped or long-pressed offers Download with Hazel.

All three funnel into one `onMediaDetected(DetectedMedia)`, de-duplicated by URL.

## Hand-off (single download path)

The chip and the sheet prefill the **existing** `DownloadViewModel` /
`DownloadOptions` flow with the sniffed URL. No second queue, no second set of
settings, no second proxy path.

## Deliberately untouched

`DownloadViewModel` internals, yt-dlp argument construction, the share overlay,
`CookieWebViewActivity`, `WebViewProxy`/`NetworkProxy` behaviour.

## Verification

- `./gradlew :app:testGithubDebugUnitTest` — unit tests for `MediaSniffer` dedupe +
  URL/extension classification.
- `./gradlew :app:lintGithubDebug` and `:app:compileGithubDebugKotlin`.
- Manual: load a page with a `<video>`, confirm the chip appears once per stream,
  tap it, confirm the existing sheet opens with the URL filled in.

## Known gaps (this draft)

- Strings are English-only in `strings_browser.xml`; the 20 existing locale
  files are untouched and should be translated before release.
- No download of blob-only MSE streams yet; the sniffed manifest URL is offered
  and yt-dlp resolves it, which covers most cases.
- Not wired into bottom navigation yet — see the follow-up commit.
