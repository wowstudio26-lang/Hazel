# Hazel Remote Bridge

The Remote Bridge is an optional experimental screen for using Hazel with a separately deployed PC-Free HTTP bridge.

## What is implemented

- Open **More → Remote Bridge**.
- Configure the bridge base URL and bearer token. Remote URLs must use HTTPS; HTTP is accepted only for loopback development addresses.
- Test connectivity against the bridge's public `/health` endpoint.
- Save the endpoint and token encrypted with AES-GCM using a key held by Android Keystore. The token is not hard-coded or written to the repository.
- Submit a magnet link, poll the job, see the status and available completed files, and ask the bridge to cancel a remote job.
- Download a completed file with transfer-size updates and a separate cancellation control.
- Publish finished files to the public `Download/Hazel/` folder using MediaStore on Android 10 and later. On older Android versions the helper writes to the public Downloads directory and may need the legacy storage permission.
- Remember the last remote job ID and magnet link in private app preferences, so the screen can reconnect to that job after it is reopened.
- Avoid overwriting a same-named file when choosing a destination name.

## Configure PC-Free

Use the companion [PC-Free bridge pull request](https://github.com/wowstudio26-lang/PC-Free/pull/1).

1. Run the bridge on a host and network where BitTorrent downloads are allowed.
2. Generate a random `BRIDGE_TOKEN` of at least 32 characters and set it in the bridge's `.env`.
3. Start the service from `bridge/` with `docker compose up --build -d`.
4. The compose file binds to `127.0.0.1:8787` by default. For phone access, place the service behind a private access gateway or an HTTPS reverse proxy. Do not expose port 8787 directly to the public Internet.
5. Enter the gateway's HTTPS base URL and the exact same token in Hazel, then use **Test connection**.

## State and recovery

- PC-Free persists job metadata at `/state/jobs.json` and keeps completed payloads under `/downloads`; the Compose file mounts both as named volumes.
- A completed job and its available files survive a container restart.
- Queued/interrupted jobs are rescheduled using their original job folder. aria2 can resume partial files when its control files are intact; when those files are missing or damaged, it may need to restart the transfer.
- Hazel stores the last job ID and magnet locally so it can query the bridge again when reopened. If the bridge has lost or no longer recognises the job, the screen reports the API error.
- Downloads run from the Remote Bridge screen's coroutine scope. They are not yet part of Hazel's canonical persistent download queue and do not have a dedicated background worker/notification. Keep Hazel in a stable foreground session during large file transfers until background transfer support is added.

## Security and limitations

- Only download content you are authorised to access.
- This feature is not designed to evade company network controls. The bridge must be run where torrent traffic is permitted.
- Treat the bearer token like a password. Anyone who gets it can submit jobs and download completed files.
- API transport helpers validate job IDs and reject traversal components in file paths. The bridge only accepts `magnet:` links, not arbitrary file URLs.
- The bridge's storage quota is a coarse guard rather than a hard per-job byte limit.
- Build checks are not a substitute for a physical Android end-to-end test. A live bridge, legal test torrent, device storage check, interrupted transfer test, and cancellation test are still required before calling the feature production-ready.
