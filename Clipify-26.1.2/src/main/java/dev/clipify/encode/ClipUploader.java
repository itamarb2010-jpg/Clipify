package dev.clipify.encode;

import dev.clipify.ClipifyLog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uploads a finished clip to a free anonymous host and returns a shareable direct link — the
 * "post it in Discord" half of the Medal experience.
 *
 * <p>Free hosts come and go, so this tries a <b>chain</b> of them until one works, all returning a
 * direct link that Discord embeds and plays inline:
 * <ul>
 *   <li><b>x0.at</b> — a "null pointer" host; direct {@code .mp4} link, size-based retention, and an
 *       optional {@code expires} for temporary links.</li>
 *   <li><b>Litterbox</b> (catbox.moe) — temporary only (1–72&nbsp;h).</li>
 *   <li><b>tmpfiles.org</b> — 1&nbsp;hour, used as a last-resort fallback.</li>
 * </ul>
 * Catbox's permanent host and 0x0.st were dropped after both stopped accepting uploads.
 *
 * <p>No Minecraft reference, so both the 1.21.11 and 26.1.2 builds share it. The multipart body is
 * <b>streamed</b> from disk with a known Content-Length, so a large clip is never held in the heap.
 */
public final class ClipUploader {

	private static final long MAX_BYTES = 500L * 1024 * 1024;
	private static final Pattern JSON_URL = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");

	/**
	 * Self-hosted clip host (the {@code clips-deploy/} nginx + Node service on the home server).
	 * Unlike the free hosts this takes a <b>raw</b> {@code video/mp4} body with a bearer token and
	 * replies {@code {"url": …}}. The token is shipped in the jar on purpose — abuse is bounded by the
	 * server's per-file size cap and nginx rate limit, and it can be rotated by editing
	 * {@code /etc/clips-server.env} and rebuilding.
	 */
	private static final String CLIPS_ENDPOINT = "https://clips.lucastudios.com/upload";
	private static final String CLIPS_TOKEN = "47dbfdeb78814cdaed1bfa9e3303de9fd8b48a7c9ec4b9d7";

	public enum Service {
		/** Self-hosted clips.lucastudios.com — branded direct link, auto-deleted after 14 days. */
		CLIPS(false),
		/** Kept as long as the host allows (size-based). */
		CATBOX(false),
		/** Given an explicit expiry in hours. */
		LITTERBOX(true);

		final boolean temporary;

		Service(boolean temporary) {
			this.temporary = temporary;
		}
	}

	/** Raised with a message safe to show the player. */
	public static final class UploadException extends Exception {
		public UploadException(String message) {
			super(message);
		}

		public UploadException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	@FunctionalInterface
	private interface Attempt {
		String run() throws UploadException;
	}

	private ClipUploader() {
	}

	/**
	 * Uploads {@code file} and returns the resulting URL. Blocking — call from a background thread.
	 *
	 * @param hours retention in hours when {@code service} is temporary; ignored otherwise
	 */
	public static String upload(Path file, Service service, int hours) throws UploadException {
		long size;
		try {
			size = Files.size(file);
		} catch (IOException e) {
			throw new UploadException("Could not read the clip: " + e.getMessage(), e);
		}
		if (size <= 0) {
			throw new UploadException("The clip is empty.");
		}
		if (size > MAX_BYTES) {
			throw new UploadException(String.format(
					"Clip is %.0f MB — over the %.0f MB host limit. Lower the bitrate/length.",
					size / 1e6, MAX_BYTES / 1e6));
		}

		List<Attempt> chain = new ArrayList<>();
		if (service == Service.CLIPS) {
			chain.add(() -> uploadClips(file, size));
			// If the self-host is briefly down, still get a (non-branded) link out rather than nothing.
			chain.add(() -> uploadX0(file, size, 0));
			chain.add(() -> uploadTmpfiles(file, size));
		} else if (service.temporary) {
			chain.add(() -> uploadX0(file, size, hours));
			chain.add(() -> uploadLitterbox(file, size, hours));
			chain.add(() -> uploadTmpfiles(file, size));
		} else {
			chain.add(() -> uploadX0(file, size, 0));
			chain.add(() -> uploadLitterbox(file, size, 72)); // 72h fallback before the 1h tmpfiles net
			chain.add(() -> uploadTmpfiles(file, size));
		}

		UploadException last = null;
		for (Attempt attempt : chain) {
			try {
				return attempt.run();
			} catch (UploadException e) {
				last = e;
				ClipifyLog.LOGGER.warn("Share host failed, trying the next: {}", e.getMessage());
			}
		}
		throw last != null ? last : new UploadException("Upload failed on every host.");
	}

	// --- providers -----------------------------------------------------------

	/**
	 * Self-hosted clips.lucastudios.com: a raw {@code video/mp4} body (no multipart) with a bearer
	 * token, streamed from disk with a real Content-Length. Reply is {@code {"id","url","bytes"}}.
	 */
	private static String uploadClips(Path file, long size) throws UploadException {
		Supplier<InputStream> body = () -> {
			try {
				return Files.newInputStream(file);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		};
		HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(30))
				.build();
		ClipifyLog.LOGGER.info("Uploading {} ({} MiB) to clips.lucastudios.com", file.getFileName(), size / (1024 * 1024));
		HttpRequest request = HttpRequest.newBuilder(URI.create(CLIPS_ENDPOINT))
				.header("User-Agent", "Clipify")
				.header("Authorization", "Bearer " + CLIPS_TOKEN)
				.header("Content-Type", "video/mp4")
				.timeout(Duration.ofMinutes(10))
				.POST(fixedLength(body, size))
				.build();
		try {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			int code = response.statusCode();
			String reply = response.body() == null ? "" : response.body().strip();
			if (code == 200) {
				Matcher m = JSON_URL.matcher(reply);
				if (m.find()) {
					return m.group(1).replace("\\/", "/");
				}
				throw new UploadException("clips host gave an unexpected response.");
			}
			if (code == 401) {
				throw new UploadException("clips host rejected the upload token.");
			}
			if (code == 413) {
				throw new UploadException("Clip is too large for the clips host.");
			}
			throw new UploadException("clips host returned HTTP " + code
					+ (reply.isEmpty() ? "" : ": " + truncate(reply)));
		} catch (IOException e) {
			throw new UploadException("clips.lucastudios.com is unreachable: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new UploadException("Upload was interrupted.");
		}
	}

	/** x0.at (0x0 protocol): field "file", optional "expires" (hours), plain-text URL reply. */
	private static String uploadX0(Path file, long size, int hours) throws UploadException {
		String boundary = boundary();
		StringBuilder sb = new StringBuilder();
		if (hours > 0) {
			appendField(sb, boundary, "expires", String.valueOf(normaliseHours(hours)));
		}
		filePart(sb, boundary, "file", file.getFileName().toString());
		String body = post("https://x0.at", sb, file, size, boundary, "x0.at");
		if (body.startsWith("http")) {
			return body;
		}
		throw new UploadException("x0.at rejected the upload: " + truncate(body));
	}

	/** Litterbox (catbox.moe): temporary only; fields reqtype/time, part "fileToUpload". */
	private static String uploadLitterbox(Path file, long size, int hours) throws UploadException {
		String boundary = boundary();
		StringBuilder sb = new StringBuilder();
		appendField(sb, boundary, "reqtype", "fileupload");
		appendField(sb, boundary, "time", normaliseHours(hours) + "h");
		filePart(sb, boundary, "fileToUpload", file.getFileName().toString());
		String body = post("https://litterbox.catbox.moe/resources/internals/api.php", sb, file, size, boundary, "Litterbox");
		if (body.startsWith("http")) {
			return body;
		}
		throw new UploadException("Litterbox rejected the upload: " + truncate(body));
	}

	/** tmpfiles.org: 1-hour fallback; JSON reply, converted to the direct-download URL. */
	private static String uploadTmpfiles(Path file, long size) throws UploadException {
		String boundary = boundary();
		StringBuilder sb = new StringBuilder();
		filePart(sb, boundary, "file", file.getFileName().toString());
		String body = post("https://tmpfiles.org/api/v1/upload", sb, file, size, boundary, "tmpfiles.org");
		Matcher m = JSON_URL.matcher(body);
		if (!m.find()) {
			throw new UploadException("tmpfiles.org gave an unexpected response.");
		}
		String url = m.group(1).replace("\\/", "/");
		// The API returns a viewer URL; "/dl/" is the direct file that Discord can embed.
		return url.replaceFirst("://tmpfiles\\.org/", "://tmpfiles.org/dl/");
	}

	// --- shared HTTP ---------------------------------------------------------

	/** Streams the multipart body with retry and returns the 200 response body; throws otherwise. */
	private static String post(String endpoint, StringBuilder prefixBuilder, Path file, long size,
			String boundary, String host) throws UploadException {
		byte[] prefix = prefixBuilder.toString().getBytes(StandardCharsets.UTF_8);
		byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
		long contentLength = (long) prefix.length + size + suffix.length;

		Supplier<InputStream> streamSupplier = () -> {
			try {
				return new SequenceInputStream(Collections.enumeration(List.of(
						new ByteArrayInputStream(prefix),
						Files.newInputStream(file),
						new ByteArrayInputStream(suffix))));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		};

		HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(30))
				.build();

		ClipifyLog.LOGGER.info("Uploading {} ({} MiB) to {}", file.getFileName(), size / (1024 * 1024), host);

		int maxAttempts = 3;
		UploadException lastError = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
					.header("User-Agent", "Mozilla/5.0 (compatible; Clipify/1.0)")
					.header("Content-Type", "multipart/form-data; boundary=" + boundary)
					.timeout(Duration.ofMinutes(10))
					.POST(fixedLength(streamSupplier, contentLength))
					.build();
			boolean retryable;
			try {
				HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
				int code = response.statusCode();
				String body = response.body() == null ? "" : response.body().strip();
				if (code == 200 && !body.isEmpty()) {
					return body;
				}
				if (code == 200) {
					lastError = new UploadException(host + " gave an empty response — trying again.");
					retryable = true;
				} else if (code == 429 || code >= 500) {
					lastError = new UploadException(host + " is busy (HTTP " + code + ").");
					retryable = true;
				} else {
					throw new UploadException(host + " returned HTTP " + code
							+ (body.isEmpty() ? "" : ": " + truncate(body)));
				}
			} catch (IOException e) {
				lastError = new UploadException(host + " unreachable: " + e.getMessage(), e);
				retryable = true;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new UploadException("Upload was interrupted.");
			}
			if (!retryable || attempt == maxAttempts) {
				break;
			}
			try {
				Thread.sleep(1000L * attempt);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new UploadException("Upload was interrupted.");
			}
		}
		throw lastError != null ? lastError : new UploadException(host + " upload failed.");
	}

	private static String boundary() {
		return "----Clipify" + Long.toHexString(System.nanoTime());
	}

	private static void filePart(StringBuilder sb, String boundary, String fieldName, String filename) {
		sb.append("--").append(boundary).append("\r\n");
		sb.append("Content-Disposition: form-data; name=\"").append(fieldName)
				.append("\"; filename=\"").append(sanitise(filename)).append("\"\r\n");
		sb.append("Content-Type: video/mp4\r\n\r\n");
	}

	private static void appendField(StringBuilder sb, String boundary, String name, String value) {
		sb.append("--").append(boundary).append("\r\n");
		sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n");
		sb.append(value).append("\r\n");
	}

	private static int normaliseHours(int hours) {
		int[] allowed = { 1, 12, 24, 72 };
		int best = 72;
		int bestDiff = Integer.MAX_VALUE;
		for (int h : allowed) {
			int d = Math.abs(h - hours);
			if (d < bestDiff) {
				bestDiff = d;
				best = h;
			}
		}
		return best;
	}

	private static String sanitise(String name) {
		return name.replaceAll("[\"\\r\\n]", "_");
	}

	private static String truncate(String s) {
		return s.length() <= 200 ? s : s.substring(0, 197) + "...";
	}

	/**
	 * Wraps {@link HttpRequest.BodyPublishers#ofInputStream} so the request carries a real
	 * Content-Length instead of chunked transfer-encoding, while still streaming the file.
	 */
	private static HttpRequest.BodyPublisher fixedLength(Supplier<InputStream> streamSupplier, long length) {
		HttpRequest.BodyPublisher delegate = HttpRequest.BodyPublishers.ofInputStream(streamSupplier);
		return new HttpRequest.BodyPublisher() {
			@Override
			public long contentLength() {
				return length;
			}

			@Override
			public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
				delegate.subscribe(subscriber);
			}
		};
	}
}
