# Clipify 2.0.1
---

## Fixed

- **"Clip failed — FFmpeg setup failed" on a fresh install.** The first time you clip, Clipify downloads FFmpeg for you. It was pointing at a build that has since been deleted upstream, so the download came back **404** and the mod could never record — this hit every new install, not just some. Clipify now downloads a build that stays up permanently, and if a download ever fails again it automatically falls back to the current one instead of giving up.
- **A useful message instead of a Java error.** If the download genuinely can't happen — no internet, blocked network — Clipify now tells you to check your connection and shows you exactly where to drop your own `ffmpeg.exe` if you'd rather do it by hand.
- **"Trim failed" when saving over a clip you'd just been watching.** Saving an edit over the original moved the trimmed file into place while the editor's preview still had the clip open, which Windows refuses — so the save failed at random, usually right after playing or scrubbing. The preview is now closed before the file is touched, the move retries while the handle is released, and a save that still fails cleans up after itself instead of leaving a full-size `.trim.tmp.mp4` behind in your clips folder.

*Already hit this? Just update — there's nothing else to do. If you dropped in an `ffmpeg.exe` manually, Clipify keeps using it.*

---

## Changed

- **Recording costs noticeably less FPS.** Clipify used to copy every captured frame on the render thread — several megabytes a frame, which at high frame rates was the difference between "invisible" and "this mod eats my FPS". On any GPU from the last decade frames now go straight from the GPU to the encoder with no copy at all. If the GPU or the encoder falls behind, Clipify drops a frame rather than stealing frame-time from the game.
