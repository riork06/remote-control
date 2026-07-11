# Remote Control — Screen Capture + WebSocket Relay (Step 2)

Full loop is now wired up: phone captures + encodes its screen, sends it
over a WebSocket relay, a browser-based controller decodes and displays it
and sends taps/swipes back, which the phone injects via AccessibilityService.

## Components

1. **`server/`** — Node.js WebSocket relay + pairing server.
   - `POST /new-room` mints a 6-digit pairing code.
   - Agent connects to `ws://host:8080/?role=agent&code=XXXXXX`.
   - Controller connects to `ws://host:8080/?role=controller&code=XXXXXX`.
   - Relays binary video frames agent→controller and JSON commands
     controller→agent. Refuses a second connection to an occupied role,
     and tears the whole room down when either side disconnects — no
     silent reconnection or session hijacking.
   - Run it: `cd server && npm install && npm start`

2. **Android app** (`app/src/main/java/com/example/remotecontrol/`)
   - `MainActivity.kt` — fetches a pairing code from the relay, then shows
     the system MediaProjection consent dialog. Displays the code so the
     user can hand it to whoever they want controlling the device.
   - `ScreenCaptureService.kt` — foreground service, MediaProjection +
     MediaCodec H.264 encoder, now forwards every encoded frame to
     `ControlChannelClient`. Persistent "Stop sharing" notification for
     the whole session, as before.
   - `ControlChannelClient.kt` — WebSocket client (Java-WebSocket). Sends
     video frames with a 9-byte header (keyframe flag + presentation
     timestamp), receives JSON input commands, dispatches them to the
     accessibility service.
   - `RemoteInputAccessibilityService.kt` — unchanged from step 1; only
     active once manually enabled in system settings.
   - **Before building**: replace `YOUR_RELAY_SERVER` in `MainActivity.kt`
     with your actual relay host/port.

3. **`controller-web/controller.html`** — self-contained browser
   controller. No build step — open it directly in Chrome/Edge (needs
   WebCodecs support). Enter the relay URL and pairing code shown on the
   phone, hit Connect. Decodes incoming H.264 via `VideoDecoder`, renders
   to a canvas, and turns clicks/drags into `tap`/`swipe` JSON commands.

## Running it end to end

1. `cd server && npm install && npm start` (defaults to port 8080)
2. Update `relayHttpBase`/`relayWsBase` in `MainActivity.kt` to point at
   that server (use your machine's LAN IP if testing on a real phone,
   e.g. `http://192.168.1.50:8080`)
3. Build and install the Android app, enable the Accessibility Service
   from the app's button, then tap "Start screen sharing" — note the
   pairing code shown.
4. Open `controller-web/controller.html` in Chrome, enter the same relay
   URL and the pairing code, click Connect.

## Known limitations / next steps

- **No TLS**: this is `ws://` for local testing. For real internet use,
  put the server behind TLS (`wss://`, e.g. via nginx or Caddy in front
  of it) — browsers will refuse to open insecure WebSockets from an
  HTTPS page, and unencrypted screen/input data over the open internet
  is a real risk.
- **No auth beyond the pairing code**: the code is single-use and
  room-locked, but there's no rate limiting on `/new-room` or on guessing
  codes. Add a max-attempts lockout or longer codes before any public
  deployment.
- **WebCodecs browser support**: Chrome/Edge only, no Firefox/Safari yet.
  If you need broader browser support, that's the point where switching
  to WebRTC (which has a JS decode path built in) starts to pay off.
- **No audio, no clipboard/file transfer** — video + touch input only.
- **Resolution handling is naive**: the controller guesses 1080×2400
  until the first decoded frame reports its real size. Fine for a single
  session, but worth sending an explicit resolution message up front if
  you extend this.

## Design principles baked in (unchanged, keep these if you extend this)

- Capture cannot start without the OS-level MediaProjection consent dialog.
- A persistent notification with a "Stop sharing" action is shown for the
  entire duration of any capture session.
- The Accessibility Service must be enabled manually by the device owner.
- The relay never lets a session be silently rejoined — losing a
  connection means re-pairing, not resuming.

