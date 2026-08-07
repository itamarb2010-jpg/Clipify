# Clipify 2.0.0 — What's New

🚧 Still adding to this — here's everything new so far. Works on Minecraft **1.21.11** and **26.1.2**.

<!-- DEV NOTE (not shown when rendered): baseline 1.1.0. Keep logging every shipped change here in plain language. When 2.0.0 ships, drop this comment and the "still adding" line. Two jars ship next to this file: Clipify-2.0.0-1.21.11.jar and Clipify-2.0.0-26.1.2.jar. -->

##  New stuff
- **Discord shows what you're playing.** While you're in-game, your Discord profile shows a **"Playing Minecraft with Clipify"** card with the Clipify logo, and a **Download Clipify** button your friends can click. You can turn it off in **Settings → General → Show on Discord**.

- **Search and rename your clips.** The Clips screen has a **search box** at the top that filters as you type, and you can **right-click any clip to rename it**.

- **"Open last clip" hotkey.** Add a hotkey that jumps **straight into the editor** with your most recent clip.

- **As many clip keys as you want - each with its own length.** New **Hotkeys** tab in settings. Click **Add Hotkey** and pick what it does. For clip keys you choose the length, so you can have one key that saves the **last 30 seconds** and another that saves the **last 2 minutes**.

- **One-click quality.** Pick **Low**, **Standard**, or **High** and you're done - or choose **Custom** to set everything yourself.

- **Shared clips get a watermark.** When you share, Clipify stamps a small **"clipify"** logo in the bottom-right and uploads that copy, so the link plays inline in Discord. Your saved file stays untouched: no logo.

- **Screenshot key.** Set a screenshot key: it snaps the picture, saves it in a **screenshots** folder, and **copies it to your clipboard** so you can paste it straight into Discord or anywhere. The Clips menu can now sort by **All / Clips / Screenshots**.

##  Changes
- **Clip & screenshot anywhere.** You can now grab a clip or screenshot from the **menus too** (multiplayer list, title screen, pause) — not just while in a world. (The recorder runs in menus now, so it uses a little GPU while you sit on them.)
- **Settings tidied up.** The old **Video** tab is now **Quality**, and **Controls** is now **Hotkeys**. General and Audio are unchanged.
- **Any frame rate you like.** Type any FPS from **10 to 240** instead of picking from a short list.
- **Clip keys moved.** Clipify's keybinds got removed from the vanila keybinds screen they live in the new **Hotkeys** tab.

##  Fixes
- **Sound now lines up with the picture.** Clip audio could run about a second ahead of the video, and it drifted further whenever the game went quiet for a while. Clipify now cuts the sound from the exact moment the clip's first frame was captured, so voice and game audio land where they belong.
- **Clips no longer lose their last moment.** Saving with audio on could cut the final second or so off the end of the video — the part you pressed the key for. Fixed.
- **Same quality everywhere.** Your clip used to look different in every place you saw it: the share link was shrunk to 1080p and re-compressed hard, the editor preview was decoded small (and got softer the moment you hit play), and dragging the playhead showed a blurry stand-in. Now the **link** keeps the clip's own resolution and near-original quality, the **editor** decodes at the exact size it's drawn — so the preview matches the file — and **dragging** snaps to the sharp frame as soon as it's ready. **Trimming** keeps its quality too. (Uploads are bigger, so they take a little longer.)
- **Edited clips keep their sound.** Trimming a clip in the editor used to make it silent. Fixed - edited clips now keep their audio.
- **Turning off "Show on Discord" no longer crashes the game.**
