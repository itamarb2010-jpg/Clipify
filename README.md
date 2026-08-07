# Clipify

> **Instant replay for Minecraft.** Save the last 15–120 seconds of your gameplay with a single keypress - no OBS, no Medal, no ShadowPlay, no account.

[![Fabric](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/supported/fabric_vector.svg)](https://fabricmc.net/) [![Modrinth](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/modrinth_vector.svg)](https://modrinth.com/mod/clipify) [![Discord](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/social/discord-plural_vector.svg)](https://discord.gg/g3a7pZTU9F)

Clipify is a Medal-like instant replay mod for Minecraft. Save your recent gameplay as an MP4 with one keybind, manage and trim clips in-game, and optionally upload them for easy sharing.

Supports Minecraft **1.21.11** and **26.1.2**.

***

## Features

### Instant replay, always recording

*   Rolling buffer of your last **5 seconds to 10 minutes** - configurable, and **each clip hotkey has its own length**.
*   Captures **exactly what's on your screen** - world, HUD, chat, and open GUIs - as a standard, shareable `.mp4`.
*   **Hardware encoding** (NVIDIA NVENC, AMD AMF, Intel QuickSync) with an automatic **libx264** software fallback, so it runs well on almost any machine.
*   Saves are **near-instant** - clips are stitched from the buffer with no re-encode.
*   **Clip anywhere** - the recorder runs in menus too, so you can grab something from the server list, title screen or pause menu, not just in a world.

### Microphone & PC audio

*   Clips now record **sound** - your **microphone** and your **PC / desktop audio**, mixed straight into the saved MP4.
*   **Zero-setup PC audio on Windows** - captures whatever is playing to any output device (headphones, speakers, a virtual-cable output), nothing to install.
*   Pick your mic and output device, set **per-source volume (0–200 %)**, and check your input with the **Mic Test** live level meter before you record.
*   The **in-game editor now plays clip audio** in sync with the preview, so you can actually hear your recordings and trims.
*   New **Audio** category in settings, styled to match the rest of the menu.
*   _macOS: microphone capture works, PC audio isn’t supported there yet._

### In-game clip editor

*   Browse every recording from the **Clips** screen and edit it **without leaving Minecraft**.
*   **Search as you type** to find a clip, and **right-click any clip to rename it**.
*   Live preview with **play/pause**, a **timeline**, and **draggable in/out trim handles** over a thumbnail filmstrip - with **audio playing back in sync**.
*   **Save the trim as a copy or overwrite the original**, open the clip in your system player, or delete it.
*   Reach it from the **“Clips” button in the pause menu**, or bind **Open last clip** to jump straight into the editor with your most recent recording.

### Screenshots

*   Bind a **screenshot key**: it snaps the picture, saves it to a **screenshots** folder, and **copies it to your clipboard** so you can paste it straight into Discord.
*   The Clips screen sorts by **All / Clips / Screenshots**.

### One-click sharing

*   Share links are **generated on demand** - click **Generate link** and it's copied to your clipboard, ready to paste into Discord and play inline. **Copy link is instant** afterwards.
*   Shared clips get a small **“clipify” watermark** in the bottom-right - only on the uploaded copy. **Your saved file stays untouched.**
*   The link keeps your clip's **own resolution and near-original quality**, so what your friends see is what you recorded.
*   Resilient host chain: **x0.at** (primary, long-lived links) with **litterbox** (72 h) and **tmpfiles.org** (1 h) as automatic fallbacks.

### Discord presence

*   While you're in-game your Discord profile shows a **“Playing Minecraft with Clipify”** card with the Clipify logo and a **Download Clipify** button your friends can click.
*   Turn it off any time in **Settings → General → Show on Discord**.

### Settings & controls

*   Clean, redesigned **in-game settings** (via Mod Menu, the pause menu, or a hotkey), split into **General**, **Quality**, **Audio** and **Hotkeys**.
*   **One-click quality** - pick **Low** (360p24), **Standard** (720p60) or **High** (1080p60), or go **Custom** and set resolution, bitrate, codec and encoder yourself.
*   **Any frame rate from 10 to 240** - type it in instead of picking from a short list.
*   **As many hotkeys as you want.** Hit **Add Hotkey**, choose what it does - *clip*, *screenshot*, *open last clip*, *open folder*, *open settings* - and press the whole combo (e.g. `Ctrl+Shift+G`) to bind it. Clip keys each carry their own length, so one key can save the **last 30 seconds** and another the **last 2 minutes**.
*   Clipify's keys live in the **Hotkeys** tab now, not the vanilla Controls screen.

Clips are saved to `.minecraft/clipify/clips/` with timestamped filenames; screenshots go to
`.minecraft/clipify/screenshots/`.

***

## What's new in 2.0.0

**New**

*   **Discord** shows a “Playing Minecraft with Clipify” card while you play.
*   **Search and rename** your clips; **right-click to rename**.
*   **“Open last clip”** hotkey jumps straight into the editor.
*   **Unlimited hotkeys**, each with its own action - and clip keys with their own length.
*   **One-click quality**: Low / Standard / High, or Custom.
*   **Screenshot key** that also copies to your clipboard.
*   **Watermark on shared clips** (uploaded copy only).

**Changed**

*   Clip and screenshot **from menus** too, not just in a world.
*   **Video → Quality** and **Controls → Hotkeys**; Clipify's keys left the vanilla Controls screen.
*   Any frame rate from **10 to 240**.

**Fixed**

*   **Audio now lines up with the picture** - sound could run ~a second ahead and drift further during quiet stretches. Clips are now cut from the exact moment the first frame was captured.
*   **Clips no longer lose their last moment** when audio was on.
*   **Same quality everywhere** - the share link no longer gets shrunk to 1080p and re-compressed, the editor preview decodes at the size it's drawn, and scrubbing snaps to a sharp frame. Trims keep their quality too.
*   **Edited clips keep their sound** - trimming used to make them silent.
*   Turning off **Show on Discord** no longer crashes the game.

***

## Requirements

*   **Fabric Loader** and **Fabric API**
*   **Java 21+** (Minecraft 1.21.11) / **Java 25+** (Minecraft 26.1.2)
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
