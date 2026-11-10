package dev.clipify.encode;

import dev.clipify.ClipifyLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Remembers which clips already have a share link, so "Copy link" is instant the second time instead
 * of re-uploading. Keyed by absolute file path and persisted to a small properties file, so links
 * survive restarts.
 */
public final class ClipLinks {

	private final Path store;
	private final Properties props = new Properties();

	public ClipLinks(Path store) {
		this.store = store;
		load();
	}

	private void load() {
		if (!Files.isRegularFile(store)) {
			return;
		}
		try (InputStream in = Files.newInputStream(store)) {
			props.load(in);
		} catch (IOException e) {
			ClipifyLog.LOGGER.warn("Could not read the link cache", e);
		}
	}

	private static String key(Path file) {
		return file.toAbsolutePath().normalize().toString();
	}

	public synchronized String get(Path file) {
		return file == null ? null : props.getProperty(key(file));
	}

	public synchronized boolean has(Path file) {
		return get(file) != null;
	}

	public synchronized void put(Path file, String url) {
		if (file == null || url == null || url.isBlank()) {
			return;
		}
		props.setProperty(key(file), url);
		save();
	}

	public synchronized void remove(Path file) {
		if (file != null && props.remove(key(file)) != null) {
			save();
		}
	}

	private void save() {
		try {
			if (store.getParent() != null) {
				Files.createDirectories(store.getParent());
			}
			try (OutputStream out = Files.newOutputStream(store)) {
				props.store(out, "Clipify share links");
			}
		} catch (IOException e) {
			ClipifyLog.LOGGER.warn("Could not save the link cache", e);
		}
	}
}
