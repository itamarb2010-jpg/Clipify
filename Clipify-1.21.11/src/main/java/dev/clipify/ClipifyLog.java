package dev.clipify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared SLF4J logger, kept in its own class free of any Minecraft reference.
 *
 * <p>This lets the {@code capture} and {@code encode} packages log without dragging in
 * {@link Clipify} — whose static initialiser touches Minecraft's key-binding registry — so the
 * FFmpeg pipeline can be exercised by plain JUnit tests on a headless JVM.
 */
public final class ClipifyLog {

	public static final Logger LOGGER = LoggerFactory.getLogger("Clipify");

	private ClipifyLog() {
	}
}
