package dev.ultracraft;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.BlitRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

/**
 * ULTRAKILL's frames from the file UltraBridge maps (%TEMP%/ultracraft_frame2.bin, which under Proton is the temp
 * inside the game's prefix: UkPaths finds it). Each frame is the final colour
 * image plus V1's mask (alpha of its 3D render target, one byte per pixel), read back by ULTRAKILL's GPU straight into
 * a slot of the file. Both are uploaded straight from the mapped file into two textures and combined on the GPU by
 * the v1_composite shader while drawing.
 */
public final class UkFrame {
	private static final int HEADER = 64;
	private static final long MAX_PIXELS = 3840L * 2160L;
	private static final int SLOTS = 4;
	private static final long SLOT_BYTES = MAX_PIXELS * 8L;

	private static RenderPipeline pipeline(String name, boolean rgbaMask) {
		RenderPipeline.Builder b = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("ultracraft", "pipeline/" + name))
			.withVertexShader(Identifier.fromNamespaceAndPath("ultracraft", "core/v1_composite"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("ultracraft", "core/v1_composite"))
			.withSampler("Sampler1");
		// RGBA mask: premultiplied, so ULTRAKILL's additive light (tracers, flashes) adds onto Minecraft
		if (rgbaMask) b = b.withShaderDefine("MASK_RGBA").withBlend(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA);
		return b.build();
	}

	/** RGB from Sampler0; coverage (and, for an RGBA mask, added light) from Sampler1; flipped by the UVs. */
	public static final RenderPipeline COMPOSITE = pipeline("v1_composite", false);
	public static final RenderPipeline COMPOSITE_RGBA_MASK = pipeline("v1_composite_rgba_mask", true);

	private static MappedByteBuffer map;
	private static GpuTexture color, mask;
	private static GpuTextureView colorView, maskView;
	private static int texW, texH, texMaskBpp;
	private static int lastSeq = -1;
	public static long frames;
	/**
	 * The view the shown frame was drawn from (eye x y z, yaw, pitch, roll, fov), or null. Minecraft's camera uses it
	 * so its world and ULTRAKILL's layer always line up, even mid-turn.
	 */
	public static volatile float[] framePose;

	private UkFrame() {}

	private static boolean open() {
		if (map != null) return true;
		Path p = UkPaths.shared().resolve("ultracraft_frame2" + UltracraftConfig.instanceSuffix() + ".bin");
		try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "r"); FileChannel ch = f.getChannel()) {
			long need = HEADER + SLOTS * SLOT_BYTES;
			if (ch.size() < need) return false;
			map = ch.map(FileChannel.MapMode.READ_ONLY, 0, need);
			map.order(ByteOrder.LITTLE_ENDIAN);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private static void makeTextures(int w, int h, int maskBpp) {
		if (color != null) {
			colorView.close();
			color.close();
			maskView.close();
			mask.close();
		}
		GpuDevice device = RenderSystem.getDevice();
		int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
		color = device.createTexture(() -> "ultracraft v1 colour", usage, TextureFormat.RGBA8, w, h, 1, 1);
		mask = device.createTexture(() -> "ultracraft v1 mask", usage, maskBpp == 1 ? TextureFormat.RED8 : TextureFormat.RGBA8, w, h, 1, 1);
		colorView = device.createTextureView(color);
		maskView = device.createTextureView(mask);
		texW = w;
		texH = h;
		texMaskBpp = maskBpp;
		lastSeq = -1;
	}

	// the camera took a frame for this render frame: the overlay shows that one, not a newer one
	private static boolean taken;

	/** The camera's call, before the world is drawn: take the newest frame and use the view it was drawn from. */
	/** When the newest frame arrived. */
	public static long lastFrameAt;

	/** ULTRAKILL is sending frames (one in the last quarter second). */
	public static boolean fresh() {
		return System.currentTimeMillis() - lastFrameAt < 250;
	}

	public static boolean updateForCamera() {
		taken = true;
		return update();
	}

	/**
	 * The overlay's call, after the world is drawn: the frame the camera took, so ULTRAKILL's layer matches the view
	 * Minecraft's world was just drawn from (a newer frame, landed mid-render, would slide out of line while turning).
	 */
	public static boolean updateForOverlay() {
		if (taken) {
			taken = false;
			return color != null;
		}
		return update();
	}

	/** Upload the newest frame if there is one. Returns false if there is nothing to show. */
	public static boolean update() {
		if (!open()) return false;
		int seq = map.getInt(0);
		int w = map.getInt(4);
		int h = map.getInt(8);
		int slot = map.getInt(16);
		int maskBpp = map.getInt(20);
		int version = map.getInt(24);
		if (version != 2 || w <= 0 || h <= 0 || (long) w * h > MAX_PIXELS || slot < 0 || slot >= SLOTS || (maskBpp != 1 && maskBpp != 4)) return false;
		if (color == null || w != texW || h != texH || maskBpp != texMaskBpp) makeTextures(w, h, maskBpp);
		if (seq != lastSeq) {
			countRate(seq);
			lastSeq = seq;
			// how long ago ULTRAKILL drew it (its clock, from ours and the offset the pings found)
			int drawnAt = map.getInt(28);
			long offset = clockOffsetUs;
			if (drawnAt != 0 && offset != Long.MIN_VALUE) {
				ageSumUs += (int) (System.nanoTime() / 1000L + offset) - drawnAt;
				ageCount++;
			}
			lastFrameAt = System.currentTimeMillis();
			int base = (int) (HEADER + slot * SLOT_BYTES);
			var enc = RenderSystem.getDevice().createCommandEncoder();
			// straight from the mapped file to the GPU: no copy on our side
			enc.writeToTexture(color, map.slice(base, w * h * 4), NativeImage.Format.RGBA, 0, 0, 0, 0, w, h);
			enc.writeToTexture(mask, map.slice(base + (int) (MAX_PIXELS * 4), w * h * maskBpp),
				maskBpp == 1 ? NativeImage.Format.LUMINANCE : NativeImage.Format.RGBA, 0, 0, 0, 0, w, h);
			frames++;
			if (map.getInt(60) == 1) {
				float[] p = new float[7];
				for (int i = 0; i < 7; i++) p[i] = map.getFloat(32 + i * 4);
				framePose = p;
			} else {
				framePose = null;
			}
		}
		return true;
	}

	// ------------------------------------------------------------ frame lock

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("ultracraft");
	/** This frame waited for ULTRAKILL instead of Minecraft's own frame limit. */
	public static boolean locked;
	private static long lockFrames, lockWaitNs, lockTimeouts, lockStatsAt;
	/** How old ULTRAKILL's frames were when they came in (summed, counted), for the stats line. */
	private static long ageSumUs, ageCount;
	/** ULTRAKILL's clock (microseconds) minus ours, from CLOCK pings; MIN_VALUE until known. */
	private static volatile long clockOffsetUs = Long.MIN_VALUE;
	private static long nextClockPing;

	/** Every few seconds: our clock to ULTRAKILL (CLOCK sent), which answers at once with its own. */
	private static void pingClock() {
		long now = System.nanoTime() / 1000L;
		if (now < nextClockPing) return;
		nextClockPing = now + 5_000_000L;
		UkLink.send("CLOCK " + now);
	}

	/** CLOCK sent ukNow (on the link's thread, as it arrives): the offset, if the round trip was quick. */
	static void clockReply(String line) {
		String[] a = line.split(" ");
		if (a.length < 3) return;
		long sent = Long.parseLong(a[1]), uk = Long.parseLong(a[2]), back = System.nanoTime() / 1000L;
		if (back - sent > 2000) return;
		long offset = uk - (sent + back) / 2;
		long was = clockOffsetUs;
		clockOffsetUs = was == Long.MIN_VALUE ? offset : (was * 3 + offset) / 4;
	}

	/**
	 * After Minecraft shows a frame: wait for ULTRAKILL's next one before starting the next, instead of Minecraft's own
	 * frame limit. Minecraft draws its world from the view of ULTRAKILL's frame, so a Minecraft frame without a new one
	 * is the same picture again, and one that lands while Minecraft is busy skips one: at two frame rates side by side
	 * that's a regular hitch. In lock step every ULTRAKILL frame is shown once, as soon as it's there.
	 */
	/**
	 * Lock step only while ULTRAKILL keeps up. Missing its frame over and over (a slower computer, or Minecraft capped
	 * faster than ULTRAKILL can go) turned every other frame into a wait that timed out: uneven frames, a stutter worse
	 * than a steady lower rate. Then Minecraft stops waiting for a few seconds and draws ULTRAKILL's latest frame.
	 */
	private static int recentFrames, recentLate;
	private static long freeRunUntil;

	// how many frames a second ULTRAKILL is really drawing (frames it numbered, over the time they took to come)
	private static double ukRate = 60.0;
	private static int rateSeq = -1;
	private static long rateAt;

	private static void countRate(int seq) {
		long now = System.nanoTime();
		int n = seq - rateSeq;
		if (rateSeq >= 0 && n > 0 && n < 200 && now > rateAt) {
			double inst = n / ((now - rateAt) / 1e9);
			ukRate += (Math.min(inst, 1000.0) - ukRate) * 0.05;
		}
		rateSeq = seq;
		rateAt = now;
	}

	/**
	 * Minecraft's frame limit while it isn't waiting for ULTRAKILL (it can't keep up): not much past ULTRAKILL's own
	 * rate. Every Minecraft frame shows ULTRAKILL's latest, so frames beyond that are the same picture drawn again, and on
	 * a graphics card both games share they take the time ULTRAKILL needed (an unlimited Minecraft ran at 900 while
	 * ULTRAKILL dropped to 30).
	 */
	/** Debug: ULTRAKILL's frame rate as Minecraft gets its frames, and whether Minecraft is waiting for them. */
	public static String rateInfo() {
		return String.format(java.util.Locale.ROOT, "ukRate=%.0f waiting=%b", ukRate, UltracraftConfig.lockStep && System.nanoTime() >= freeRunUntil);
	}

	public static int freeRunLimit(int limit) {
		if (!UltracraftConfig.lockStep || System.nanoTime() >= freeRunUntil || !(Ultracraft.active || Ultracraft.steveDrawn) || color == null) return limit;
		int cap = Math.max(30, (int) Math.ceil(ukRate * 1.25));
		return limit >= 260 ? cap : Math.min(limit, cap);
	}

	public static void waitForNext() {
		locked = false;
		if (color == null || map == null || !fresh() || !(Ultracraft.active || Ultracraft.steveDrawn)) return;
		if (!UltracraftConfig.lockStep || System.nanoTime() < freeRunUntil) {
			Ultracraft.sendInputEarly();
			return;
		}
		locked = true;
		pingClock();
		long start = System.nanoTime();
		long deadline = start + (long) (1.5e9 / Math.max(30, UltracraftConfig.ukFpsNow()));
		long nextPoll = start + 1_000_000L;
		int seen = lastSeq;
		while (true) {
			java.lang.invoke.VarHandle.acquireFence();
			if (map.getInt(0) != seen) break;
			long now = System.nanoTime();
			if (now >= deadline) {
				lockTimeouts++;
				recentLate++;
				break;
			}
			// keep the window answering while waiting (the wait is a few milliseconds at most)
			if (now >= nextPoll) {
				org.lwjgl.glfw.GLFW.glfwPollEvents();
				nextPoll = now + 1_000_000L;
			}
			Thread.onSpinWait();
		}
		// the input for ULTRAKILL's next frame, while it's between frames
		Ultracraft.sendInputEarly();
		long end = System.nanoTime();
		if (++recentFrames >= 60) {
			// more than one frame in five late: ULTRAKILL can't keep up right now
			if (recentLate > 12) {
				freeRunUntil = end + 4_000_000_000L;
				LOG.info("[frames] ULTRAKILL missed {} of the last 60 frames: not waiting for it for a few seconds", recentLate);
			}
			recentFrames = recentLate = 0;
		}
		lockFrames++;
		lockWaitNs += end - start;
		if (lockStatsAt == 0) lockStatsAt = end;
		if (end - lockStatsAt > 30_000_000_000L) {
			double secs = (end - lockStatsAt) / 1e9;
			LOG.info(String.format(java.util.Locale.ROOT, "[frames] %.0f fps in lock step with ULTRAKILL (cap %d), waited %.1f ms a frame, %d late, frames %.1f ms old%s",
				lockFrames / secs, UltracraftConfig.ukFpsNow(), lockWaitNs / 1e6 / lockFrames, lockTimeouts, ageCount > 0 ? ageSumUs / 1000.0 / ageCount : -1.0,
				UltracraftConfig.lowLatency ? " (low latency)" : ""));
			lockFrames = lockWaitNs = lockTimeouts = 0;
			ageSumUs = ageCount = 0;
			lockStatsAt = end;
		}
	}

	/** Draw the composited V1 layer over the whole GUI area (scaled up from ULTRAKILL's render size). */
	public static void draw(GuiGraphics ctx) {
		if (color == null) return;
		int w = ctx.guiWidth(), h = ctx.guiHeight();
		GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		TextureSetup ts = TextureSetup.doubleTexture(colorView, sampler, maskView, sampler);
		RenderPipeline pipe = texMaskBpp == 1 ? COMPOSITE : COMPOSITE_RGBA_MASK;
		// rows arrive bottom-up from Unity: v runs 1 -> 0 to flip on the GPU
		ctx.guiRenderState.submitGuiElement(new BlitRenderState(pipe, ts, new Matrix3x2f(ctx.pose()), 0, 0, w, h, 0f, 1f, 1f, 0f, -1, null));
	}
}
