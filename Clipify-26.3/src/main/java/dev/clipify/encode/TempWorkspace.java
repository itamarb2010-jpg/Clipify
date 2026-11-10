package dev.clipify.encode;

import dev.clipify.ClipifyLog;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;

/**
 * Owns this game instance's scratch directory under the configured temp folder.
 *
 * <p>Each run gets its own {@code session-<pid>-<millis>} directory held open with an OS file lock.
 * On startup every other session directory is tested: if its lock can be taken then no process owns
 * it any more and it is deleted, which is how segments left behind by a crash get cleaned up. A
 * second Minecraft instance running concurrently still holds its own lock, so its data is left
 * alone rather than being deleted out from under it.
 */
public final class TempWorkspace implements Closeable {

	private final Path sessionDir;
	private final Path segmentsDir;
	private final Path clipWorkDir;
	private final FileChannel lockChannel;
	private final FileLock lock;

	private TempWorkspace(Path sessionDir, FileChannel lockChannel, FileLock lock) throws IOException {
		this.sessionDir = sessionDir;
		this.lockChannel = lockChannel;
		this.lock = lock;
		this.segmentsDir = sessionDir.resolve("segments");
		this.clipWorkDir = sessionDir.resolve("work");
		Files.createDirectories(segmentsDir);
		Files.createDirectories(clipWorkDir);
	}

	public static TempWorkspace open(Path root) throws IOException {
		Files.createDirectories(root);
		sweepAbandoned(root);

		Path session = root.resolve("session-" + ProcessHandle.current().pid() + "-" + System.currentTimeMillis());
		Files.createDirectories(session);

		FileChannel channel = FileChannel.open(session.resolve(".lock"),
				StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		FileLock lock;
		try {
			lock = channel.tryLock();
		} catch (IOException | OverlappingFileLockException e) {
			channel.close();
			throw new IOException("Could not lock the Clipify temp directory", e);
		}
		if (lock == null) {
			channel.close();
			throw new IOException("Could not lock the Clipify temp directory");
		}
		return new TempWorkspace(session, channel, lock);
	}

	public Path segmentsDir() {
		return segmentsDir;
	}

	public Path clipWorkDir() {
		return clipWorkDir;
	}

	/** Deletes session directories whose owning process is gone. */
	private static void sweepAbandoned(Path root) {
		List<Path> candidates;
		try (var stream = Files.list(root)) {
			candidates = stream.filter(Files::isDirectory)
					.filter(p -> p.getFileName().toString().startsWith("session-"))
					.toList();
		} catch (IOException e) {
			ClipifyLog.LOGGER.warn("Could not scan {} for abandoned temp data", root, e);
			return;
		}

		int removed = 0;
		for (Path dir : candidates) {
			Path lockFile = dir.resolve(".lock");
			if (!Files.exists(lockFile)) {
				// No lock file at all: nothing owns it.
				if (deleteRecursively(dir)) {
					removed++;
				}
				continue;
			}
			try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
				FileLock probe = channel.tryLock();
				if (probe == null) {
					continue; // Another live instance owns it.
				}
				probe.release();
			} catch (IOException | OverlappingFileLockException e) {
				continue; // Treat "cannot test" as "in use" and leave it alone.
			}
			if (deleteRecursively(dir)) {
				removed++;
			}
		}
		if (removed > 0) {
			ClipifyLog.LOGGER.info("Cleaned up {} abandoned replay-buffer director{} in {}",
					removed, removed == 1 ? "y" : "ies", root);
		}
	}

	private static boolean deleteRecursively(Path dir) {
		try (var stream = Files.walk(dir)) {
			for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
			return true;
		} catch (IOException e) {
			ClipifyLog.LOGGER.debug("Could not fully delete {}", dir, e);
			return false;
		}
	}

	@Override
	public void close() {
		try {
			if (lock.isValid()) {
				lock.release();
			}
		} catch (IOException ignored) {
			// Releasing on shutdown is best-effort.
		}
		try {
			lockChannel.close();
		} catch (IOException ignored) {
			// Same.
		}
		deleteRecursively(sessionDir);
	}
}
