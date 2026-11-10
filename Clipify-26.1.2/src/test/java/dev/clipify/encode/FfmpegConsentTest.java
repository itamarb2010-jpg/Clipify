package dev.clipify.encode;

import dev.clipify.config.ClipifyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Modrinth's rule: nothing is downloaded until the player has agreed to it. */
class FfmpegConsentTest {

	@Test
	void freshConfigAsksFirst() {
		ClipifyConfig c = new ClipifyConfig();
		assertEquals("ask", c.ffmpegDownloadConsent, "a new instance must ask before downloading");
		c.ffmpegDownloadConsent = "yes please";
		c.validated();
		assertEquals("ask", c.ffmpegDownloadConsent, "an unknown answer falls back to asking");
		c.ffmpegDownloadConsent = "declined";
		c.validated();
		assertEquals("declined", c.ffmpegDownloadConsent, "a real answer is kept");
	}

	@Test
	void lookingForFfmpegNeverDownloads(@TempDir Path gameDir) {
		FfmpegProvider provider = new FfmpegProvider(gameDir);
		boolean found = provider.findExisting();
		// This machine may have FFmpeg on PATH; the no-download guarantee is only testable without it.
		assumeTrue(!found, "FFmpeg is on PATH here");
		assertEquals(FfmpegProvider.State.IDLE, provider.state(), "no download was started");
		assertFalse(Files.exists(gameDir.resolve("clipify")), "nothing was written to the instance");
	}

	@Test
	void consentScreenHasSomethingToSay() {
		assumeTrue(!FfmpegProvider.pinnedBuilds().isEmpty(), "no FFmpeg build for this platform");
		assertTrue(FfmpegProvider.downloadMegabytes() > 0, "size is shown");
		assertFalse(FfmpegProvider.downloadSource().isBlank(), "source is shown");
	}
}
