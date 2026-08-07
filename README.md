# Clipify

> **Instant replay for Minecraft.** Save the last 15–120 seconds of your gameplay with a single keypress - no OBS, no Medal, no ShadowPlay, no account.

[![Fabric](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/supported/fabric_vector.svg)](https://fabricmc.net/) [![Modrinth](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/modrinth_vector.svg)](https://modrinth.com/mod/clipify) [![Discord](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/social/discord-plural_vector.svg)](https://discord.gg/g3a7pZTU9F)

Clipify is a A Medal-like instant replay mod for Minecraft. Save your recent gameplay as an MP4 with one keybind, manage and trim clips in-game, and optionally upload them for easy sharing.

***

## Features

### Instant replay, always recording

*   Rolling buffer of the last **15 / 30 / 60 / 120 seconds** - configurable.
*   Captures **exactly what's on your screen** - world, HUD, chat, and open GUIs - as a standard, shareable `.mp4`.
*   **Hardware encoding** (NVIDIA NVENC, AMD AMF, Intel QuickSync) with an automatic **libx264** software fallback, so it runs well on almost any machine.
*   Saves are **near-instant** - clips are stitched from the buffer with no re-encode.

### Microphone & PC audio _(new in 1.1.0)_

*   Clips now record **sound** - your **microphone** and your **PC / desktop audio**, mixed straight into the saved MP4.
*   **Zero-setup PC audio on Windows** - captures whatever is playing to any output device (headphones, speakers, a virtual-cable output), nothing to install.
*   Pick your mic and output device, set **per-source volume (0–200 %)**, and check your input with the **Mic Test** live level meter before you record.
*   The **in-game editor now plays clip audio** in sync with the preview, so you can actually hear your recordings and trims.
*   New **Audio** category in settings, styled to match the rest of the menu.
*   _macOS: microphone capture works, PC audio isn’t supported there yet._

### In-game clip editor _(new in 1.1.0)_

*   Browse every recording from the new **Clips** screen and edit it **without leaving Minecraft**.
*   Live preview with **play/pause**, a **timeline**, and **draggable in/out trim handles** over a thumbnail filmstrip - with **audio playing back in sync**.
*   **Save the trim as a copy or overwrite the original**, open the clip in your system player, or delete it.
*   Reach it from the **“Clips” button in the pause menu** or a rebindable **Open Clips** hotkey.

### One-click sharing

*   Share links are **generated on demand** - click **Generate link** and it's copied to your clipboard, ready to paste into Discord and play inline. **Copy link is instant** afterwards.
*   Resilient host chain: **x0.at** (primary, long-lived links) with **litterbox** (72 h) and **tmpfiles.org** (1 h) as automatic fallbacks.

### Settings & controls

*   Clean, redesigned **in-game settings** (via Mod Menu or a hotkey): clip length, frame rate, bitrate, resolution cap, encoder, and output folder.
*   **Rebind the clip hotkey** by pressing the whole combo (e.g. `Ctrl+Shift+G`) directly, with Ctrl/Shift/Alt modifier options.

Clips are saved to `.minecraft/clipify/clips/` with timestamped filenames.

***

## Requirements

*   **Fabric Loader** and **Fabric API**
*   **Java 21+**
*   **Mod Menu** recommended (opens the in-game settings screen)

> FFmpeg is downloaded automatically on first launch (a verified, pinned build) - or drop your own binary into `clipify/bin/`.

***

## Compatibility

*   **Client-side only** - install it yourself and play on any server.
*   Windows, macOS, and Linux for recording, hardware encoders are detected automatically.
*   **Audio:** microphone works on all platforms; **PC audio is Windows-only** for now.

***

## Known limitations

*   **PC audio is Windows-only.** On macOS the microphone still records, but system-audio capture isn't available yet - macOS has no zero-setup loopback, so the PC Audio section is hidden there.
*   **Share links are public** - anyone with the link can view the clip.

***

## Links

*   💬 **Chat & support** - [https://discord.gg/jAXF5vARYm](https://discord.gg/jAXF5vARYm)
*   🌐 **Portfolio** - [https://vaguestan.pages.dev](https://vaguestan.pages.dev/)

***

_Clipify is an independent project and is not affiliated with or endorsed by Medal, OBS, or NVIDIA._
