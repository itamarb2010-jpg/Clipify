package dev.clipify.encode;

import dev.clipify.ClipifyLog;
import dev.clipify.config.ClipifyConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Chooses an H.264 encoder and produces the FFmpeg arguments for it.
 *
 * <p>Presence of an encoder in {@code ffmpeg -encoders} only proves the binary was compiled with
 * it, not that this machine can actually use it — a build with NVENC support still fails on an AMD
 * GPU. {@link #probe} therefore runs a real two-frame encode against each candidate and keeps the
 * first that exits cleanly, falling back to libx264 which always works.
 */
public final class VideoEncoder {

	/** FFmpeg encoder name, e.g. {@code h264_nvenc}. */
	private final String codec;
	private final boolean hardware;

	private VideoEncoder(String codec, boolean hardware) {
		this.codec = codec;
		this.hardware = hardware;
	}

	public static final VideoEncoder SOFTWARE = new VideoEncoder("libx264", false);

	public String codec() {
		return codec;
	}

	public boolean isHardware() {
		return hardware;
	}

	@Override
	public String toString() {
		return codec + (hardware ? " (hardware)" : " (software)");
	}

	// ---------------------------------------------------------------- probing

	/** Blocking hardware-encoder detection. Call from a background thread. */
	public static VideoEncoder probe(Path ffmpeg, Set<String> available, ClipifyConfig.EncoderPreference preference) {
		List<String> candidates = switch (preference) {
			case SOFTWARE -> List.of();
			case NVIDIA_NVENC -> List.of("h264_nvenc");
			case AMD_AMF -> List.of("h264_amf");
			case INTEL_QSV -> List.of("h264_qsv");
			case AUTO -> List.of("h264_nvenc", "h264_amf", "h264_qsv");
		};

		for (String candidate : candidates) {
			if (!available.isEmpty() && !available.contains(candidate)) {
				continue;
			}
			if (canEncodeWith(ffmpeg, candidate)) {
				ClipifyLog.LOGGER.info("Using hardware encoder: {}", candidate);
				return new VideoEncoder(candidate, true);
			}
			ClipifyLog.LOGGER.debug("Hardware encoder {} is unusable on this machine", candidate);
		}

		if (preference != ClipifyConfig.EncoderPreference.SOFTWARE && preference != ClipifyConfig.EncoderPreference.AUTO) {
			ClipifyLog.LOGGER.warn("Requested encoder for {} is unavailable — falling back to libx264", preference);
		} else if (preference == ClipifyConfig.EncoderPreference.AUTO) {
			ClipifyLog.LOGGER.info("No usable hardware encoder found — using libx264");
		}
		return SOFTWARE;
	}

	private static boolean canEncodeWith(Path ffmpeg, String codec) {
		List<String> cmd = List.of(
				ffmpeg.toAbsolutePath().toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
				"-f", "lavfi", "-i", "color=c=black:s=640x360:r=30",
				"-frames:v", "2", "-c:v", codec, "-f", "null", "-");
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			byte[] out = p.getInputStream().readAllBytes();
			if (!p.waitFor(30, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return false;
			}
			if (p.exitValue() != 0) {
				ClipifyLog.LOGGER.debug("Probe of {} failed: {}", codec, new String(out).strip());
				return false;
			}
			return true;
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	// ------------------------------------------------------------------ args

	/**
	 * Encoder-specific arguments. All variants are configured for low latency (no B-frames, no
	 * lookahead) because frames must reach the segment files promptly for the replay buffer to be
	 * accurate, and for a bounded bitrate so the on-disk ring stays predictable.
	 */
	public List<String> arguments(int bitrateKbps, int gopFrames, int segmentSeconds) {
		List<String> args = new ArrayList<>();
		args.add("-c:v");
		args.add(codec);

		switch (codec) {
			case "h264_nvenc" -> {
				args.addAll(List.of("-preset", "p4", "-tune", "ll", "-rc", "cbr", "-bf", "0"));
			}
			case "h264_amf" -> {
				args.addAll(List.of("-quality", "speed", "-rc", "cbr", "-bf", "0"));
			}
			case "h264_qsv" -> {
				args.addAll(List.of("-preset", "veryfast", "-bf", "0"));
			}
			default -> {
				args.addAll(List.of("-preset", "veryfast", "-tune", "zerolatency", "-profile:v", "high"));
			}
		}

		args.addAll(List.of(
				"-pix_fmt", "yuv420p",
				"-b:v", bitrateKbps + "k",
				"-maxrate", bitrateKbps + "k",
				"-bufsize", (bitrateKbps * 2) + "k",
				"-g", Integer.toString(gopFrames),
				// A keyframe exactly on every segment boundary is what makes the later
				// concat-and-copy produce a seekable, playable MP4 with no re-encoding.
				"-force_key_frames", "expr:gte(t,n_forced*" + segmentSeconds + ")"));
		return args;
	}
}
