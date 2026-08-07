package dev.clipify.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import dev.clipify.Clipify;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Persistent, human-editable configuration stored in {@code .minecraft/config/clipify.json}.
 *
 * <p>Every field is validated on load so that a hand-edited or corrupt file can never put the
 * capture pipeline into an unsupported state (which would otherwise surface as a hard crash deep
 * inside OpenGL or FFmpeg).
 */
public final class ClipifyConfig {

	/** Common replay durations shown as a hint. Any custom value in the range below is allowed. */
	public static final int[] DURATION_CHOICES = { 15, 30, 60, 120 };
	/** Common capture frame rates shown as a hint. Any custom value in the range below is allowed. */
	public static final int[] FPS_CHOICES = { 30, 60, 120, 144 };

	/** Allowed range for {@link #clipDurationSeconds}; custom values (not a fixed list) are clamped here. */
	public static final int DURATION_MIN_SECONDS = 5;
	public static final int DURATION_MAX_SECONDS = 600;
	/** Allowed range for {@link #fps}; custom values (not a fixed list) are clamped here. */
	public static final int FPS_MIN = 10;
	public static final int FPS_MAX = 240;

	// GLFW key codes for the migrated legacy defaults (kept as literals so this class needs no LWJGL import).
	private static final int GLFW_KEY_G = 71;
	private static final int GLFW_KEY_O = 79;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	// ---------------------------------------------------------------- fields

	/** Master switch. When false no frames are captured and no FFmpeg process runs. */
	public boolean enabled = true;

	/**
	 * Replay-buffer length in seconds — kept equal to the LONGEST {@link HotkeyAction#CLIP} hotkey's
	 * duration by {@link #validated()}. The ring and audio buffers are sized from this, so every clip
	 * hotkey (all of which are ≤ this) can be served. Clamped to [{@code DURATION_MIN_SECONDS},
	 * {@code DURATION_MAX_SECONDS}].
	 */
	public int clipDurationSeconds = 30;

	/**
	 * User-defined hotkeys shown on the Hotkeys settings tab. Multiple bindings per action are allowed,
	 * and each {@link HotkeyAction#CLIP} binding saves a clip of its own {@link Hotkey#durationSeconds}.
	 * A fresh or pre-2.0.0 config has an empty list, which {@link #validated()} seeds from the legacy
	 * single-key fields.
	 */
	public java.util.List<Hotkey> hotkeys = new java.util.ArrayList<>();

	/** Capture / output frame rate. A custom value in [{@link #FPS_MIN}, {@link #FPS_MAX}]. */
	public int fps = 60;

	/** Target video bitrate in kbit/s. 20 Mbps keeps 1080p60 gameplay crisp on a hardware encoder. */
	public int videoBitrateKbps = 20000;

	/**
	 * Maximum capture width/height. The frame is downscaled on the GPU when the game window is
	 * larger than this. {@code 0} means "use the native window size".
	 */
	public int maxWidth = 1920;
	public int maxHeight = 1080;

	/** Encoder selection strategy. */
	public EncoderPreference encoder = EncoderPreference.AUTO;

	/** Where finished clips are written. Relative paths are resolved against the game directory. */
	public String outputFolder = "clipify/clips";

	/** Where the rolling segment ring lives. Relative paths are resolved against the game directory. */
	public String tempFolder = "clipify/temp";

	/** Where screenshots (the Screenshot hotkey) are written. Relative paths resolve against the game directory. */
	public String screenshotFolder = "clipify/screenshots";

	/**
	 * Capture PC (desktop/game) audio into the clip — the "PC Audio" row in the Audio settings.
	 *
	 * <p>This drives the experimental OpenAL loopback tap. It is <b>off by default</b> because it
	 * replaces Minecraft's audio output device; see the README for the full caveat list.
	 */
	public boolean captureGameAudio = false;

	/** Capture the microphone alongside the clip — the "Microphone" row in the Audio settings. */
	public boolean captureMicrophone = false;

	// ------------------------------------------------------------ audio mixer (Recording Audio panel)

	/** "Audio Processes" selector. Only {@code "All PC audio"} is meaningful until per-app capture lands. */
	public String audioProcesses = "All PC audio";

	/** Microphone capture level, 0–200 % (100 % = unity gain). */
	public int micVolume = 100;

	/** PC-audio capture level, 0–200 % (100 % = unity gain). */
	public int pcAudioVolume = 100;

	/** Output devices selected for PC-audio capture, by display name (the indented list under "PC Audio"). */
	public java.util.List<String> pcAudioDevices = new java.util.ArrayList<>();

	// ------------------------------------------------------------ microphone settings panel

	/** Selected microphone device name, or {@code "Auto"}. */
	public String micDevice = "Auto";

	/** Reduce background noise on the mic. */
	public boolean noiseSuppression = false;

	/** Noise-suppression gate threshold in dB, -100..-25. */
	public int noiseSuppressionDb = -100;

	/** Only capture the mic while {@link #pushToTalkKey} is held. */
	public boolean pushToTalk = false;

	/** GLFW key code bound to push-to-talk, or -1 when unset. */
	public int pushToTalkKey = -1;

	/** Duplicate a mono mic input into both channels so it plays back in both ears. */
	public boolean monoAudioInput = false;

	/**
	 * After saving a clip, upload it to a free host and copy a shareable link to the clipboard —
	 * the "paste it in Discord" flow. Off by default because it publishes the clip to a public URL.
	 */
	public boolean shareEnabled = false;

	/** Which host to upload to when a share link is generated. Defaults to the free-host chain (x0.at …);
	 *  the self-hosted {@link ShareTarget#CLIPS} host is archived — see that enum value. */
	public ShareTarget shareTarget = ShareTarget.CATBOX;

	/** Retention for the temporary host, in hours. One of 1 / 12 / 24 / 72. */
	public int litterboxHours = 72;

	public enum ShareTarget {
		/**
		 * ARCHIVED (mod 2.0.0): self-hosted clips.lucastudios.com — branded link + inline-Discord embed
		 * page, auto-deleted after 14 days. The server ({@code clips-deploy/}) is still live; this target
		 * is just no longer selected. To bring it back, point the default + migration below at CLIPS —
		 * the uploaded file is unaffected either way (it's already watermarked + downscaled client-side).
		 */
		CLIPS,
		/** Permanent link via the free-host chain (x0.at → Litterbox → tmpfiles). The v1.1.0 default. */
		CATBOX,
		/** Temporary link (auto-expires), 1 GB limit. */
		LITTERBOX
	}

	/** Show an on-screen toast when a clip is saved or fails. */
	public boolean showNotifications = true;

	/** Show the "Playing Minecraft with Clipify" card on your Discord profile (Rich Presence). */
	public boolean discordPresence = true;

	/**
	 * Modifier keys that must be held alongside the "Save clip" binding. Together with the default
	 * {@code G} binding this produces Ctrl+G. The key itself is rebindable from Options → Controls.
	 */
	public boolean hotkeyRequireCtrl = true;
	public boolean hotkeyRequireShift = false;
	public boolean hotkeyRequireAlt = false;

	/** Modifiers for the "Open clips folder" binding; with the default {@code O} that is Ctrl+Shift+O. */
	public boolean folderKeyRequireCtrl = true;
	public boolean folderKeyRequireShift = true;
	public boolean folderKeyRequireAlt = false;

	/** Length of one ring-buffer segment in seconds. Also the keyframe interval. */
	public int segmentSeconds = 1;

	/**
	 * Allow downloading a verified FFmpeg build if none is bundled//present. When false the mod
	 * stays idle until the user drops an {@code ffmpeg} binary into {@code clipify/bin/}.
	 */
	public boolean allowFfmpegDownload = true;

	public enum EncoderPreference {
		AUTO,
		SOFTWARE,
		NVIDIA_NVENC,
		AMD_AMF,
		INTEL_QSV
	}

	/** Output video codec. H.265/HEVC makes smaller files but encodes slower and is less compatible. */
	public VideoCodec codec = VideoCodec.H264;

	public enum VideoCodec {
		H264,
		H265
	}

	/** What a {@link Hotkey} triggers when its combo is pressed in-game. */
	public enum HotkeyAction {
		/** Save the previous {@link Hotkey#durationSeconds} seconds of gameplay. */
		CLIP,
		/** Capture a still screenshot to the screenshots folder and copy it to the clipboard. */
		SCREENSHOT,
		/** Open the most recent clip straight in the editor. */
		OPEN_LAST_CLIP,
		/** Open the clips output folder. */
		OPEN_FOLDER,
		/** Open this Clipify settings screen. */
		OPEN_SETTINGS
	}

	/**
	 * One user-defined key combo and what it does. Clip hotkeys additionally carry their own clip
	 * length, so different keys can save different amounts of footage. Plain mutable fields because
	 * this is (de)serialised by Gson.
	 */
	public static final class Hotkey {
		public HotkeyAction action = HotkeyAction.CLIP;
		/** GLFW key code, or {@code -1} (GLFW_KEY_UNKNOWN) when unbound. */
		public int key = -1;
		public boolean ctrl;
		public boolean shift;
		public boolean alt;
		/** Clip length in seconds — used only when {@link #action} is {@link HotkeyAction#CLIP}. */
		public int durationSeconds = 30;

		public Hotkey() {
		}

		public Hotkey(HotkeyAction action, int key, boolean ctrl, boolean shift, boolean alt, int durationSeconds) {
			this.action = action;
			this.key = key;
			this.ctrl = ctrl;
			this.shift = shift;
			this.alt = alt;
			this.durationSeconds = durationSeconds;
		}

		public boolean isBound() {
			return key != -1;
		}
	}

	// ------------------------------------------------------------ validation

	/** Clamps every field into a supported range. Returns {@code this} for chaining. */
	public ClipifyConfig validated() {
		validateHotkeys();
		fps = clamp(fps, FPS_MIN, FPS_MAX);
		if (codec == null) {
			codec = VideoCodec.H264;
		}
		videoBitrateKbps = clamp(videoBitrateKbps, 1000, 200_000);
		maxWidth = maxWidth <= 0 ? 0 : clamp(maxWidth, 128, 7680);
		maxHeight = maxHeight <= 0 ? 0 : clamp(maxHeight, 128, 4320);
		segmentSeconds = clamp(segmentSeconds, 1, 5);
		litterboxHours = nearest(new int[] { 1, 12, 24, 72 }, litterboxHours, 72);
		if (encoder == null) {
			encoder = EncoderPreference.AUTO;
		}
		// clips.lucastudios.com link host archived (mod 2.0.0): default to the free-host chain and move
		// any existing CLIPS configs back onto it. (The uploaded file stays watermarked + downscaled.)
		if (shareTarget == null || shareTarget == ShareTarget.CLIPS) {
			shareTarget = ShareTarget.CATBOX;
		}
		if (outputFolder == null || outputFolder.isBlank()) {
			outputFolder = "clipify/clips";
		}
		if (tempFolder == null || tempFolder.isBlank()) {
			tempFolder = "clipify/temp";
		}
		if (screenshotFolder == null || screenshotFolder.isBlank()) {
			screenshotFolder = "clipify/screenshots";
		}
		micVolume = clamp(micVolume, 0, 200);
		pcAudioVolume = clamp(pcAudioVolume, 0, 200);
		noiseSuppressionDb = clamp(noiseSuppressionDb, -100, -25);
		if (audioProcesses == null || audioProcesses.isBlank()) {
			audioProcesses = "All PC audio";
		}
		if (micDevice == null || micDevice.isBlank()) {
			micDevice = "Auto";
		}
		if (pcAudioDevices == null) {
			pcAudioDevices = new java.util.ArrayList<>();
		}
		return this;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static int nearest(int[] choices, int value, int fallback) {
		for (int c : choices) {
			if (c == value) {
				return value;
			}
		}
		return fallback;
	}

	/**
	 * Ensures {@link #hotkeys} is non-null and populated, clamps every clip duration, and keeps
	 * {@link #clipDurationSeconds} equal to the longest clip hotkey so the ring buffer always covers
	 * them all. An empty list (fresh install or a pre-2.0.0 config) is seeded from the legacy
	 * single-key fields so existing users keep their Ctrl+G / Ctrl+Shift+O bindings.
	 */
	private void validateHotkeys() {
		if (hotkeys == null) {
			hotkeys = new java.util.ArrayList<>();
		}
		hotkeys.removeIf(h -> h == null || h.action == null);
		if (hotkeys.isEmpty()) {
			hotkeys.add(new Hotkey(HotkeyAction.CLIP, GLFW_KEY_G,
					hotkeyRequireCtrl, hotkeyRequireShift, hotkeyRequireAlt,
					clamp(clipDurationSeconds, DURATION_MIN_SECONDS, DURATION_MAX_SECONDS)));
			hotkeys.add(new Hotkey(HotkeyAction.OPEN_FOLDER, GLFW_KEY_O,
					folderKeyRequireCtrl, folderKeyRequireShift, folderKeyRequireAlt, 30));
		}
		int maxClip = 0;
		for (Hotkey h : hotkeys) {
			h.durationSeconds = clamp(h.durationSeconds, DURATION_MIN_SECONDS, DURATION_MAX_SECONDS);
			if (h.action == HotkeyAction.CLIP) {
				maxClip = Math.max(maxClip, h.durationSeconds);
			}
		}
		clipDurationSeconds = maxClip > 0
				? maxClip
				: clamp(clipDurationSeconds, DURATION_MIN_SECONDS, DURATION_MAX_SECONDS);
	}

	// -------------------------------------------------------------- derived

	public Path resolveOutputFolder() {
		return resolveAgainstGameDir(outputFolder);
	}

	public Path resolveTempFolder() {
		return resolveAgainstGameDir(tempFolder);
	}

	public Path resolveScreenshotFolder() {
		return resolveAgainstGameDir(screenshotFolder);
	}

	private static Path resolveAgainstGameDir(String raw) {
		Path p = Path.of(raw.trim());
		return p.isAbsolute() ? p.normalize() : FabricLoader.getInstance().getGameDir().resolve(p).normalize();
	}

	/** Number of segments the ring must hold to always cover {@link #clipDurationSeconds}. */
	public int ringSegmentCount() {
		// +2 gives one spare for the segment currently being written and one for the segment that
		// is being recycled, so the oldest usable segment is always older than the requested window.
		return (int) Math.ceil((double) clipDurationSeconds / segmentSeconds) + 2;
	}

	// ------------------------------------------------------------ persistence

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("clipify.json");
	}

	public static ClipifyConfig load() {
		Path path = configPath();
		if (!Files.isRegularFile(path)) {
			ClipifyConfig fresh = new ClipifyConfig().validated();
			fresh.save();
			return fresh;
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			ClipifyConfig loaded = GSON.fromJson(reader, ClipifyConfig.class);
			if (loaded == null) {
				loaded = new ClipifyConfig();
			}
			return loaded.validated();
		} catch (IOException | JsonSyntaxException e) {
			Clipify.LOGGER.error("Could not read {} — falling back to defaults", path, e);
			return new ClipifyConfig().validated();
		}
	}

	/** Writes the config atomically so a crash mid-write cannot leave a truncated file behind. */
	public void save() {
		Path path = configPath();
		try {
			Files.createDirectories(path.getParent());
			Path tmp = path.resolveSibling("clipify.json.tmp");
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
			try {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFailed) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			Clipify.LOGGER.error("Could not save {}", path, e);
		}
	}

	/** Deep copy, used by the config screen so Cancel can discard edits. */
	public ClipifyConfig copy() {
		return GSON.fromJson(GSON.toJson(this), ClipifyConfig.class).validated();
	}
}
