# Clipify 2.1.0
---

## Added

- **Minecraft 26.3 support.** Clipify now ships for 1.21.11, 26.1.2 and 26.3, with the same features in all three. 26.3 rebuilt Minecraft's renderer (it can run on OpenGL or Vulkan) and moved keyboard input to SDL, so the 26.3 build captures frames through the new renderer and works on both graphics backends. Fabric Loader 0.20.1+ is required on 26.3.
- **Volume control in the clip editor.** A speaker icon and a slider sit next to the fullscreen button, like a YouTube player. Drag the slider anywhere from 0% to 200% (with a small notch at 100%, the level it was recorded at), scroll over it to step by 10%, or click the speaker to mute and unmute. The preview plays at the level you set, and **Save copy** / **Overwrite** write it into the file. A muted save has no sound track at all. The same control is in fullscreen.

---

## Changed

- **Clipify now asks before downloading FFmpeg.** The first time you open the game in an instance that doesn't have FFmpeg yet, a screen explains what FFmpeg is, how big the download is and where it comes from, with **Download FFmpeg** and **Don't download**. Nothing is downloaded until you click Download. The answer is remembered per instance, so each new instance asks once. If you said no, pressing a clip hotkey brings the question back. Instances that already have FFmpeg (or have it on PATH) never see the screen, because there's nothing to download.
- **New mod icon**, matching the CurseForge page: a play button inside a replay arrow (shown in the mod list / Mod Menu).

---

## Fixed

- **Clips turning into a slideshow on slow PCs.** When the encoder couldn't keep up with the recording frame rate, Clipify filled each missed frame by sending the previous one through the same slow pipe again. Those repeats cost as much as real frames and crowded them out: a 2.0.1 clip on an Intel UHD laptop held about 3 real frames per second, skipped time and drifted away from its audio. A missed frame now costs almost nothing, so a PC that can only manage part of the frames records that part and the clip stays real-time and in sync.
- The log now reports frames the capture skipped because every GPU readback slot was busy, and how often the timeline had to resync, so a choppy clip can be traced to its cause.

---

## Notes

- After **Overwrite**, the volume slider goes back to 100%, because the new level is now part of the file.
- The slow-PC fix was tested against real FFmpeg with a simulated slow encoder (1440p at 120 fps: 2.8 real fps and 4 s of lost time before, 35.8 real fps and no lost time after). It hasn't been run on the laptop that reported the problem; under heavy overload frames can still arrive in uneven bursts.