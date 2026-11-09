package dev.ultracraft;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;

/** TCP link to the UltraBridge plugin running inside ULTRAKILL (127.0.0.1:27110). */
public final class UkLink {
	public static final ConcurrentLinkedQueue<String> INBOX = new ConcurrentLinkedQueue<>();
	private static volatile BufferedWriter out;
	public static volatile boolean connected;

	/** Latest V1 pose in Minecraft coordinates. */
	public static final class Pose {
		public double ex, ey, ez, fx, fy, fz, vx, vy, vz;
		public float yaw, pitch, roll, fov;
		public boolean onGround, sliding;
		public long time;
	}

	public static volatile Pose pose;

	/** How red ULTRAKILL's hurt overlay is right now, 0..255: it fades on its own side, and is mirrored here. */
	public static volatile int hurtFlash;
	public static volatile int hp = 100;
	/** V1's full health (200 on Harmless). */
	public static volatile int maxHp = 100;
	public static volatile boolean dead;
	public static volatile boolean ready;
	/** While V1 isn't ready yet: the ULTRAKILL level it's on (shown with "loading V1", so a hang says where). */
	public static volatile String loadingScene = "";

	private UkLink() {}

	public static void start() {
		Thread t = new Thread(UkLink::loop, "ultracraft-uklink");
		t.setDaemon(true);
		t.start();
	}

	private static void loop() {
		while (true) {
			try (Socket s = new Socket()) {
				s.connect(new InetSocketAddress("127.0.0.1", 27110 + UltracraftConfig.instance() - 1), 1000);
				s.setTcpNoDelay(true);
				out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
				connected = true;
				INBOX.add("CONNECTED");
				BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
				String line;
				while ((line = in.readLine()) != null) {
					if (line.startsWith("P ")) {
						parsePose(line);
					} else if (line.startsWith("CLOCK ")) {
						UkFrame.clockReply(line);
					} else if (line.startsWith("HP ")) {
						String[] a = line.split(" ");
						hp = Integer.parseInt(a[1]);
						dead = a[2].equals("1");
						if (a.length > 3) maxHp = Integer.parseInt(a[3]);
					} else if (line.startsWith("HURT ")) {
						// V1 took a hit: ULTRAKILL paints red over its own screen, but that pass is the mask side of
						// the shared frame and never reaches the colour Minecraft draws. The alpha comes on its own
						// and Ultracraft.renderV1 paints it over Minecraft's HUD instead
						hurtFlash = Integer.parseInt(line.substring(5).trim());
					} else if (line.startsWith("LOADING ")) {
						loadingScene = line.substring(8);
					} else {
						if (line.equals("READY")) ready = true;
						INBOX.add(line);
					}
				}
			} catch (Exception e) {
				// ULTRAKILL not running yet; retry
			}
			if (connected) INBOX.add("DISCONNECTED");
			connected = false;
			ready = false;
			loadingScene = "";
			out = null;
			pose = null;
			// a new link starts clean: lines held for the old one would be read as this one's first words
			pending.setLength(0);
			try {
				Thread.sleep(1000);
			} catch (InterruptedException ignored) {
				return;
			}
		}
	}

	private static void parsePose(String line) {
		String[] a = line.split(" ");
		Pose p = new Pose();
		p.ex = Double.parseDouble(a[1]);
		p.ey = Double.parseDouble(a[2]);
		p.ez = Double.parseDouble(a[3]);
		p.yaw = Float.parseFloat(a[4]);
		p.pitch = Float.parseFloat(a[5]);
		p.roll = Float.parseFloat(a[6]);
		p.fov = Float.parseFloat(a[7]);
		p.fx = Double.parseDouble(a[8]);
		p.fy = Double.parseDouble(a[9]);
		p.fz = Double.parseDouble(a[10]);
		p.vx = Double.parseDouble(a[11]);
		p.vy = Double.parseDouble(a[12]);
		p.vz = Double.parseDouble(a[13]);
		p.onGround = a[14].equals("1");
		p.sliding = a[15].equals("1");
		p.time = System.nanoTime();
		pose = p;
	}

	/**
	 * Lines waiting to go out. Every send() used to write and flush on its own, and a tick has a dozen of them: a
	 * dozen system calls and a dozen wakeups of the other side for what is really one batch of state. They are now
	 * collected and written together, at most one flush every few milliseconds - shorter than any frame, so nothing
	 * waits noticeably, and a tick's worth of lines leaves in a single write.
	 */
	private static final StringBuilder pending = new StringBuilder();
	private static long lastFlushNs;

	/** A clock ping is measured as a round trip, so it has to leave now rather than with the batch. */
	private static final long FLUSH_EVERY_NS = 4_000_000L;

	public static synchronized void send(String line) {
		BufferedWriter w = out;
		if (w == null) return;
		pending.append(line).append('\n');
		long now = System.nanoTime();
		if (line.startsWith("CLOCK ") || now - lastFlushNs >= FLUSH_EVERY_NS) flushLocked(w, now);
	}

	/** Whatever has been collected, out now: the link going away, or a caller that has to be sure. */
	public static synchronized void flush() {
		BufferedWriter w = out;
		if (w != null) flushLocked(w, System.nanoTime());
	}

	private static void flushLocked(BufferedWriter w, long now) {
		lastFlushNs = now;
		if (pending.length() == 0) return;
		try {
			w.append(pending);
			w.flush();
		} catch (Exception e) {
			out = null;
		}
		pending.setLength(0);
	}
}
