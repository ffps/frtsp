# <p align="center"><img src="assets/icon.svg" /><br />fRTSP — простой конвертер RTSP → MJPEG</p>

Android application for converting RTSP camera streams to MJPEG over HTTP.

## Current scaffold

- Android minSdk 17 (Android 4.2.1), Java.
- HTTP server listens on `0.0.0.0:8554` so other devices on the same LAN can reach it.
- Pretty-printed JSON API at `/api/v1/status`, `/stats`, `/config`, `/version`, `/logs`.
- API requests from loopback do not require authentication.
- API requests from other addresses require Basic Auth (`api:<password>`). An empty password denies external API requests.
- API password is configured in the app settings screen and stored in private SharedPreferences.
- MJPEG routes are reserved but currently return 503 until FFmpeg camera pipeline is added.

## FFmpeg binary

Place the already-built Android ARMv7 FFmpeg 3.3.2 executable here:

`app/src/main/assets/ffmpeg-armv7`

It must be the tested executable with H.264/HEVC decoders, MJPEG encoder and `image2pipe` support. Do not rebuild it.

## Build

Requires JDK 11 and Gradle 7.6.4:

```bash
gradle assembleDebug
```

Release builds are triggered by pushing a tag such as `v0.1.0`.

GitHub Actions secrets:
- `KEYSTORE_BASE64`
- `KEY_ALIAS`
- `KEYSTORE_PASSWORD`
- `KEY_PASSWORD`

For unsigned test builds, omit signing secrets. The version name comes from the tag; version code is `major*1000000 + minor*1000 + patch`.

## API authentication

Username is fixed to `api`. Example:

```bash
curl -u api:YOUR_PASSWORD http://TABLET_IP:8554/api/v1/status
```

Camera MJPEG URLs when the pipeline is implemented:

- `http://TABLET_IP:8554/camera/1`
- `http://TABLET_IP:8554/camera/1.jpg`


## Лицензия

Код написан ChatGPT (OpenAI) free. Лицензия: свободное использование.
