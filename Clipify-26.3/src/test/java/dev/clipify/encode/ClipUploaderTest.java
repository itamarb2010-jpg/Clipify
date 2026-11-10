package dev.clipify.encode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the real multipart upload path against the live host. Opt-in (it hits the network and
 * creates a public file), enabled with {@code -Dclipify.testUpload=1}. Uploads to the temporary
 * host so the artifact auto-expires.
 */
class ClipUploaderTest {

	@Test
	void uploadsAndReturnsALink(@TempDir Path tempDir) throws Exception {
		assumeTrue("1".equals(System.getProperty("clipify.testUpload")),
				"Opt-in: set -Dclipify.testUpload=1 to run the live upload test");

		Path file = tempDir.resolve("clipify-uploadtest.txt");
		Files.writeString(file, "Clipify upload test — safe to ignore. " + System.nanoTime());

		String url = ClipUploader.upload(file, ClipUploader.Service.LITTERBOX, 1);
		System.out.println("Uploaded to: " + url);

		assertTrue(url.startsWith("https://"), "should return an https URL, got: " + url);
		assertTrue(url.contains("catbox") || url.contains("litter"), "unexpected host: " + url);
	}
}
