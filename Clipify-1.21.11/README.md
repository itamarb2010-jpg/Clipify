# Clipify

**Instant replay clipping for Minecraft, entirely inside a Fabric client mod.**

Clipify keeps a rolling buffer of the last few seconds of your gameplay and saves it to a normal
MP4 the moment you press a hotkey — exactly like Medal's "save the previous 30 seconds" feature, but
with no OBS, no Medal desktop app, no ShadowPlay and no Xbox Game Bar. It captures precisely what is
inside the Minecraft window (world, HUD, GUI, chat) and works on any multiplayer server without a
server-side mod.

- **Minecraft:** 1.21.11 (Java Edition)
- **Loader:** Fabric Loader 0.19.3+
- **Java:** 21+
- **Side:** Client only
- **Depends on:** Fabric API. *Suggests* Mod Menu for the config screen.

---

## How it works (30-second version)

1. Every rendered frame — after Minecraft has drawn the world, HUD and any open GUI — is copied off
   the GPU **asynchronously** and fed to a single long-lived FFmpeg process.
2. FFmpeg continuously encodes those frames into a **ring of one-second H.264 segments** on disk.
   Old segments are recycled, so disk and memory use are strictly bounded no matter how long you
   play.
3. When you press the hotkey, Clipify snapshots the trailing segments and **stream-copies** them
   into an MP4 (no re-encode), so a 30-second clip is written in a fraction of a second while
   recording continues uninterrupted.

There is no full-session recording and nothing unbounded: the buffer is a fixed-size circular
structure, both in RAM (a tiny pool of in-flight frames) and on disk (the segment ring).

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.11.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.11 and drop it in your
   `mods/` folder.
3. *(Optional but recommended)* Download [Mod Menu](https://modrinth.com/mod/modmenu) for the
   in-game config screen.
4. Put `clipify-1.0.0.jar` (from `build/libs/`) into your `mods/` folder.
5. Launch the game.

### FFmpeg

Clipify needs an FFmpeg binary to encode video. **You do not have to install or configure it.**
On first use the mod downloads a pinned, static FFmpeg build and verifies it against a hard-coded
SHA-256 before it is ever executed. It is stored in `.minecraft/clipify/bin/` and reused forever
after.

If you would rather supply your own, drop an `ffmpeg` / `ffmpeg.exe` into `.minecraft/clipify/bin/`
(or have one on your `PATH`) and Clipify will use it and skip the download. Automatic download can
be turned off in the config (`allowFfmpegDownload: false`).

Supported download targets: Windows x64, Linux x64, Linux arm64, macOS (x64 build, runs on Apple
Silicon via Rosetta 2). On any other platform, supply your own binary as above.

---

## Usage

| Action | Default hotkey | Notes |
| --- | --- | --- |
| **Save the last N seconds** | **Ctrl + G** | The clip length is configurable (default 30 s). |
| **Open the clips folder** | **Ctrl + Shift + O** | Opens the output folder in your file manager. |

Both keys are rebindable in **Options → Controls → Clipify**. The Ctrl / Shift / Alt modifier
requirements are set in the config screen (see below). Because vanilla key bindings have no concept
of modifiers, "Ctrl+G" is implemented as *the G binding fired while Ctrl is physically held*, with
exact modifier matching so a bare `G` never triggers a save.

When a clip is saved you get a small toast — **"Saved 30-second clip"** — with the filename. If
saving fails you get a clear error toast instead; Minecraft never crashes because of Clipify.

### Output

- **Location:** `.minecraft/clipify/clips/`
- **Filename:** `clipify-YYYY-MM-DD_HH-mm-ss.mp4`
- **Format:** H.264 MP4 (`yuv420p`, `+faststart`), playable everywhere including Discord, browsers
  and video editors.

---

## Configuration

Open **Mod Menu → Clipify → Configure**, or edit `.minecraft/config/clipify.json` directly.

| Setting | Options | Default | Meaning |
| --- | --- | --- | --- |
| `enabled` | on / off | on | Master switch for the whole capture pipeline. |
| `clipDurationSeconds` | 15 / 30 / 60 / 120 | 30 | How much history to keep and save. |
| `fps` | 30 / 60 | 60 | Capture and output frame rate. |
| `videoBitrateKbps` | 1000–200000 | 20000 | Target video bitrate (CBR). |
| `maxWidth` / `maxHeight` | e.g. 1920×1080, or `0` | 1920×1080 | Capture ceiling; `0` = match window. Larger windows are GPU-downscaled to fit. |
| `encoder` | AUTO / SOFTWARE / NVIDIA_NVENC / AMD_AMF / INTEL_QSV | AUTO | Encoder selection (see below). |
| `outputFolder` | path | `clipify/clips` | Where finished clips go (relative to `.minecraft`). |
| `tempFolder` | path | `quickclip/temp` | Where the live segment ring lives. |
| `captureGameAudio` | on / off | **off** | Experimental — see [Audio](#audio). |
| `showNotifications` | on / off | on | Show the save/error toasts. |
| `hotkeyRequireCtrl/Shift/Alt` | on / off | Ctrl only | Modifiers for the save hotkey. |
| `folderKeyRequireCtrl/Shift/Alt` | on / off | Ctrl+Shift | Modifiers for the open-folder hotkey. |
| `segmentSeconds` | 1–5 | 1 | Ring segment length / keyframe interval. |
| `allowFfmpegDownload` | on / off | on | Allow the verified auto-download. |

Config edits made in the screen are applied only when you click **Save & Apply**, which restarts the
buffer with the new settings; Cancel/Escape discards them.

### Encoder selection

`AUTO` probes for a usable hardware encoder (NVENC → AMF → QSV) by running a real two-frame test
encode — presence in the FFmpeg build is not enough, the GPU has to actually accept it — and falls
back to the always-available software encoder **libx264**. Hardware encoding keeps CPU cost low;
libx264 (`veryfast`, `zerolatency`) is used when no GPU encoder works.

---

## Performance expectations

The capture path is designed to stay off the critical frame-time path:

- **Asynchronous GPU readback.** Each frame is blitted into a private surface and read back through
  **OpenGL Pixel Buffer Objects** with a fence. The CPU never waits on the GPU: the readback from
  *this* frame is collected two frames later, once the GPU has already signalled it. If the GPU is
  busy, Clipify drops a frame rather than adding latency.
- **Bounded everything.** A small pool (default 6) of reusable direct buffers caps capture memory at
  `width × height × 4 × 6` (~50 MB at 1080p). If the encoder falls behind, frames are dropped, never
  queued without limit.
- **Encoding is out-of-process.** FFmpeg runs as a separate process, so H.264 encoding uses cores
  that are not competing with the Minecraft render thread, and a hardware encoder offloads it to the
  GPU's dedicated encode block entirely.
- **Saving never stalls the game.** Snapshotting and the stream-copy run on a background thread; the
  render thread only ever does the cheap async blit.

Typical overhead at 1080p60 with a hardware encoder is a low single-digit FPS cost. Software encoding
costs more CPU; drop to 30 FPS or a lower resolution/bitrate if you are CPU-bound. Disk use is capped
at roughly `bitrate × (clipDuration + 2 s)` — about 75 MB for the default 30 s at 20 Mbps.

---

## Audio

**The first release captures video only.** `captureGameAudio` exists in the config and wires through
the pipeline, but reliable capture of Minecraft's own OpenAL output on an unmodified client is not
solved here, so the toggle is **off by default and does not currently add an audio track.** This is
called out honestly rather than shipped as a silent no-op that claims to work.

The reason: Minecraft plays audio through OpenAL Soft, and there is no cross-platform, no-extra-setup
way from inside the JVM to tap the mixed output stream. The realistic paths are (a) an OpenAL Soft
loopback/WASAPI-loopback capture device, which is platform-specific and can disturb the player's
audio, or (b) re-mixing sounds ourselves from `SoundManager`, which is fragile. Both are planned for
a later milestone. Microphone capture is likewise deferred and is **not** required for clipping.

If you need audio today, pair Clipify's clip with system audio in your editor, or use it alongside a
system recorder for the audio track only.

---

## Architecture

```
Render thread (OpenGL only)                 Worker thread              FFmpeg process(es)
─────────────────────────────               ─────────────             ──────────────────
WindowMixin @ swapBuffers HEAD
   │  (world+HUD+GUI already in back buffer)
   ▼
FrameGrabber
   • blit → private RGBA surface (scale+flip)
   • async glReadPixels → PBO ring + fence
   • collect a finished PBO → CapturedFrame ──► SegmentRecorder.offer()
                                                   │ (bounded queue)
                                                   ▼
                                                writer thread ──stdin──► ffmpeg  ──► seg%03d.ts ring
                                                                                       + segments.csv
        press Ctrl+G                                                                        │
   ReplayBufferService.saveClip() ───────────► ClipAssembler                                │
                                                • snapshot trailing segments ◄──────────────┘
                                                • concat -c copy ──► ffmpeg ──► clipify-<ts>.mp4
                                                • toast
```

Key classes (`src/main/java/dev/clipify/`):

| Class | Responsibility |
| --- | --- |
| `Clipify` | Client entrypoint; keybinds; lifecycle wiring. |
| `mixin/WindowMixin` | The single injection point, at the head of `Window.swapBuffers`. |
| `ReplayBufferService` | State machine and **thread-ownership boundary**; the only class both threads touch. |
| `capture/FrameGrabber` | All OpenGL: PBO ring, fences, scaling blit. Render thread only. |
| `capture/FramePool` | Fixed pool of direct buffers — the memory bound. |
| `capture/CaptureMath` | Pure sizing + constant-frame-rate timing (unit-tested). |
| `encode/SegmentRecorder` | The live FFmpeg process and the stdin writer/CFR pacing. |
| `encode/ClipAssembler` | Snapshot + stream-copy into the final MP4. |
| `encode/VideoEncoder` | Hardware-encoder probing and per-encoder arguments. |
| `encode/FfmpegProvider` | Locate / verified-download / validate FFmpeg. |
| `encode/TempWorkspace` | Per-instance locked temp dir; startup cleanup of abandoned runs. |
| `config/ClipifyConfig` | Validated, atomically-saved JSON config. |
| `ui/ClipifyConfigScreen` | Mod Menu config screen. |
| `ui/Notifications` | Thread-safe toasts. |

### Thread safety & lifecycle

- **Exactly one thread does OpenGL** (the render thread), guarded by
  `RenderSystem.assertOnRenderThread()`. No GL call is ever made from anywhere else.
- **Exactly one background worker** serialises every start/stop/save/download, so those operations
  can never race each other.
- Window **resize** is absorbed by the scaling blit; a genuine **aspect-ratio change**, going
  **fullscreen**, **disconnecting** or sitting on the **menu** cleanly tears the buffer down and
  rebuilds it. **Closing the game** stops FFmpeg, releases GL objects and deletes the temp session.
- Abandoned temp directories from a crashed run are detected by an OS file lock and cleaned up on the
  next startup.

---

## Building from source

```bash
./gradlew build
```

The mod jar is written to `build/libs/clipify-1.0.0.jar`. Requires a JDK 21.

### Tests

```bash
./gradlew test
```

Unit tests cover the capture math (sizing, letterboxing, constant-frame-rate remapping), the frame
pool bounds and config validation. There is also an **opt-in end-to-end integration test**
(`PipelineIntegrationTest`) that drives the *real* encode pipeline — synthetic frames → segment ring
→ assembled MP4 — against an actual FFmpeg binary. It skips itself unless you point it at one:

```bash
./gradlew test -Dclipify.ffmpeg=/path/to/ffmpeg
```

---

## Known limitations

- **No audio track yet** (see [Audio](#audio)).
- **Modded rendering backends:** designed and tested against vanilla + Fabric's Blaze3D GL backend.
  Sodium/Iris generally still present through the same window back buffer and work, but are not part
  of the guaranteed matrix.
- **Native FFmpeg download** covers Windows x64, Linux x64/arm64 and macOS; other platforms need a
  user-supplied binary.
- **`.tar.xz` extraction on Linux** shells out to the system `tar` (universally present) rather than
  bundling an xz decoder.
- The in-progress (not-yet-closed) segment is included on a best-effort basis, so a saved clip may be
  up to ~1 second longer or shorter than the exact requested duration.

See `LICENSE` and `NOTICE.md` for licensing and third-party notices.
