# Remote bridge client integration notes

The optional `RemoteBridgeClient` is a transport helper for a separately deployed PC-Free bridge. It supports creating a magnet job, reading/listing job state, cancellation, and downloading a completed file into a temporary file before finalizing it.

## Security requirements

- Only connect to an HTTPS endpoint outside loopback development.
- Do not hard-code a bearer token or commit it to source control.
- The bridge must be deployed on a host/network where torrent use is permitted.
- Treat the returned file list as untrusted; the client rejects path traversal segments.
- This commit adds a client helper only. It does not add a settings screen, persist credentials, or wire the helper into the existing Compose download queue yet.

## Follow-up integration work

1. Add a user-controlled bridge settings screen with endpoint validation and secure token storage.
2. Add a bridge job state to the persistent queue, including server job ID and the selected destination URI.
3. Poll job status with bounded backoff while the app is active; avoid tight polling.
4. When complete, stream the selected file into Hazel's existing storage/MediaStore flow.
5. Add unit tests and an instrumented end-to-end test against a controlled bridge instance.
6. Verify foreground/background behavior and HTTP failure recovery on a physical Android device.
