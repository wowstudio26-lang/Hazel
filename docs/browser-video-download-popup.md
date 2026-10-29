# Hazel in-browser video download popup — implementation handoff

## Goal
Retain Hazel's current UI and integrate a 1DM-style workflow: when a user plays a video inside Hazel's browser, detect supported media and offer a download action with the real available resolutions, audio languages, and subtitles.

## Existing code to reuse
- `MediaProbe` / `LinkResolver` and the yt-dlp-based extraction path.
- `MediaInfo` / `MediaFormat` for actual discovered stream metadata.
- `FormatSheet.kt`, `FormatSelectionSheet.kt`, and `AudioLanguageSheet.kt`.
- Existing download queue, service, notifications, and playback components.

## Implementation sequence
1. Trace the browser/playback route and confirm how the active page URL, media URL, cookies, and relevant request headers are available.
2. Bridge only eligible media events/URLs into the existing extractor. Filter false positives and prevent repeated events from creating duplicate prompts.
3. Resolve formats asynchronously and show a non-intrusive download affordance after supported media is detected.
4. Reuse Hazel's current picker UI. Show only resolutions, audio tracks, and subtitles actually reported by the extractor.
5. Pass selected format IDs, language/subtitle choices, and authorized session context into the existing queue.
6. Handle unsupported, login-gated, expired, DRM-protected, or inaccessible sources with a clear explanation. Do not bypass DRM or access controls.
7. Add unit tests for media-event filtering, duplicate suppression, format-to-queue handoff, dismissed prompts, and extraction errors.
8. Run repository CI and Android APK build. Report CI and build outcomes accurately; device playback still requires an Android device.

## Acceptance criteria
- Current Hazel UI and existing paste/share download workflows remain unchanged.
- On a controlled test page containing a supported direct-video URL, playback causes a download affordance to appear.
- The picker contains only real extractor-reported formats/languages/subtitles.
- The selected options are passed to the existing download queue.
- Repeated playback/network events do not repeatedly show the prompt.
- CI tests and APK assembly pass; physical-device tests are reported separately.

## Boundaries
This is not a promise that every website will work. Compatibility depends on extractor support, website implementation, authentication, and stream delivery. DRM-protected media and access controls are out of scope for bypassing.
