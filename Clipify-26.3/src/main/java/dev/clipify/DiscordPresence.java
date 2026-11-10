package dev.clipify;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Discord Rich Presence — the "Playing Minecraft with Clipify" card on the user's profile, mirroring
 * Medal's. It talks straight to the local Discord client over its IPC socket, so there's no bot, no
 * backend, and no native library: on Windows the socket is the named pipe {@code \\.\pipe\discord-ipc-N},
 * on Linux/macOS a Unix-domain socket under the runtime/temp dir.
 *
 * <p>The bold "Playing <b>Minecraft with Clipify</b>" line is the Discord application's <i>name</i>
 * (set in the developer portal), not something we send — {@link #DETAILS} / {@link #STATE} fill the
 * two lines below it, and {@link #startedAt} drives the elapsed timer.
 *
 * <p>Everything is best-effort and off the main thread: if Discord isn't running the connect simply
 * fails and we retry later; nothing here can break the game.
 */
public final class DiscordPresence {

	// --- Configure once the Discord app + download link exist -------------------------------------
	// APP_ID: the Application ID of a Discord app named exactly "Minecraft with Clipify".
	// LARGE_IMAGE: the Rich Presence art-asset key the Clipify logo is uploaded under.
	// DOWNLOAD_URL: the "Download Clipify" button target (leave empty to hide the button).
	private static final String APP_ID = "1530726939948749061";
	private static final String LARGE_IMAGE = "clipify";
	private static final String DOWNLOAD_URL = "https://modrinth.com/mod/clipify";

	private static final String DETAILS = "Clipping Minecraft";
	private static final String STATE = "with Clipify";
	private static final long RECONNECT_DELAY_MS = 15_000;
	private static final long RE_ASSERT_MS = 15_000;
	private static final int MAX_FRAME = 1 << 20;

	private static DiscordPresence instance;

	private final long startedAt = System.currentTimeMillis();
	private volatile boolean running;
	private Thread thread;
	private RandomAccessFile pipe;   // Windows named pipe
	private SocketChannel channel;   // Unix-domain socket

	private DiscordPresence() {
	}

	/** Starts the presence thread (no-op if disabled, unconfigured, or already running). */
	public static synchronized void start(boolean enabled) {
		if (!enabled || APP_ID.isEmpty() || instance != null) {
			return;
		}
		DiscordPresence p = new DiscordPresence();
		instance = p;
		p.running = true;
		p.thread = new Thread(p::loop, "clipify-discord-rpc");
		p.thread.setDaemon(true);
		p.thread.start();
		Clipify.LOGGER.info("Discord Rich Presence started");
	}

	/** Tears the connection down (safe to call even if never started). */
	public static synchronized void stop() {
		DiscordPresence p = instance;
		instance = null;
		if (p != null) {
			p.running = false;
			// Only signal + interrupt from the caller (the game thread). The presence thread owns the
			// socket and closes it itself in loop()'s finally — closing a pipe from another thread while
			// it's parked in a read can hang the caller, which is what crashed the game on toggle-off.
			if (p.thread != null) {
				p.thread.interrupt();
			}
		}
	}

	private void loop() {
		while (running) {
			try {
				if (connect()) {
					handshake();
					setActivity();
					// Keep the card up: hold the socket open and re-assert every so often. The periodic
					// write also detects Discord quitting (it throws) so we reconnect. We deliberately do
					// NOT sit in a blocking read — a blocking read can't be cancelled cleanly from stop(),
					// and closing the socket from another thread to break it can hang the game thread.
					while (running) {
						Thread.sleep(RE_ASSERT_MS);
						setActivity();
					}
				}
			} catch (InterruptedException stopRequested) {
				Thread.currentThread().interrupt();
				running = false;
			} catch (IOException disconnected) {
				// Discord isn't running or quit — reconnect after a pause.
			} finally {
				disconnect(); // always closed by this (the owning) thread
			}
			if (running) {
				sleep(RECONNECT_DELAY_MS);
			}
		}
	}

	// --- connection ------------------------------------------------------------------------------

	private boolean connect() {
		return System.getProperty("os.name", "").toLowerCase().contains("win") ? connectWindows() : connectUnix();
	}

	private boolean connectWindows() {
		for (int i = 0; i < 10; i++) {
			try {
				pipe = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw");
				return true;
			} catch (IOException notThisOne) {
				pipe = null;
			}
		}
		return false;
	}

	private boolean connectUnix() {
		List<String> bases = new ArrayList<>();
		for (String env : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
			String v = System.getenv(env);
			if (v != null && !v.isEmpty()) {
				bases.add(v);
			}
		}
		bases.add("/tmp");
		// Discord may namespace the socket under a Flatpak/Snap subdir.
		String[] subs = {"", "/app/com.discordapp.Discord", "/snap.discord"};
		for (String base : bases) {
			for (String sub : subs) {
				for (int i = 0; i < 10; i++) {
					Path p = Path.of(base + sub, "discord-ipc-" + i);
					if (!Files.exists(p)) {
						continue;
					}
					try {
						SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
						ch.connect(UnixDomainSocketAddress.of(p));
						channel = ch;
						return true;
					} catch (IOException notThisOne) {
						channel = null;
					}
				}
			}
		}
		return false;
	}

	private void disconnect() {
		try {
			if (pipe != null) {
				pipe.close();
			}
		} catch (IOException ignored) {
			// closing a dead handle
		}
		try {
			if (channel != null) {
				channel.close();
			}
		} catch (IOException ignored) {
			// closing a dead channel
		}
		pipe = null;
		channel = null;
	}

	// --- protocol --------------------------------------------------------------------------------

	private void handshake() throws IOException {
		JsonObject o = new JsonObject();
		o.addProperty("v", 1);
		o.addProperty("client_id", APP_ID);
		write(0, o.toString());
		readFrame(); // consume the READY dispatch
	}

	private void setActivity() throws IOException {
		JsonObject assets = new JsonObject();
		assets.addProperty("large_image", LARGE_IMAGE);
		assets.addProperty("large_text", "Clipify");

		JsonObject timestamps = new JsonObject();
		timestamps.addProperty("start", startedAt);

		JsonObject activity = new JsonObject();
		activity.addProperty("details", DETAILS);
		activity.addProperty("state", STATE);
		activity.add("timestamps", timestamps);
		activity.add("assets", assets);
		if (!DOWNLOAD_URL.isEmpty()) {
			JsonObject button = new JsonObject();
			button.addProperty("label", "Download Clipify");
			button.addProperty("url", DOWNLOAD_URL);
			JsonArray buttons = new JsonArray();
			buttons.add(button);
			activity.add("buttons", buttons);
		}

		JsonObject args = new JsonObject();
		args.addProperty("pid", ProcessHandle.current().pid());
		args.add("activity", activity);

		JsonObject frame = new JsonObject();
		frame.addProperty("cmd", "SET_ACTIVITY");
		frame.add("args", args);
		frame.addProperty("nonce", UUID.randomUUID().toString());
		write(1, frame.toString());
	}

	/** Writes one IPC frame: opcode + little-endian length + UTF-8 JSON. */
	private void write(int opcode, String json) throws IOException {
		byte[] data = json.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buf = ByteBuffer.allocate(8 + data.length).order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(opcode);
		buf.putInt(data.length);
		buf.put(data);
		byte[] arr = buf.array();
		if (pipe != null) {
			pipe.write(arr);
		} else if (channel != null) {
			ByteBuffer out = ByteBuffer.wrap(arr);
			while (out.hasRemaining()) {
				channel.write(out);
			}
		} else {
			throw new IOException("not connected");
		}
	}

	/** Reads and discards one IPC frame; throws on close/EOF (which drives a reconnect). */
	private void readFrame() throws IOException {
		byte[] header = readN(8);
		int len = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(4);
		if (len < 0 || len > MAX_FRAME) {
			throw new IOException("bogus IPC frame length " + len);
		}
		readN(len);
	}

	private byte[] readN(int n) throws IOException {
		byte[] b = new byte[n];
		int off = 0;
		while (off < n) {
			int r;
			if (pipe != null) {
				r = pipe.read(b, off, n - off);
			} else if (channel != null) {
				r = channel.read(ByteBuffer.wrap(b, off, n - off));
			} else {
				throw new IOException("not connected");
			}
			if (r < 0) {
				throw new IOException("Discord IPC closed");
			}
			off += r;
		}
		return b;
	}

	private void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			running = false;
		}
	}
}
