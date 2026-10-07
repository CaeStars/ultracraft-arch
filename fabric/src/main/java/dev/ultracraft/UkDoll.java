package dev.ultracraft;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

/**
 * V1 in the inventory's little player window: ULTRAKILL films its real V1 body (the platformer V1) into
 * %TEMP%/ultracraft_doll.bin (the temp ULTRAKILL writes to: inside its Proton prefix on Linux, see UkPaths) while the
 * inventory asks for it, turned toward the cursor like Minecraft's paper doll
 * (the head further than the body), at exactly the box's size in screen pixels so it's shown 1:1.
 */
public final class UkDoll {
	private static final int HEADER = 16, MAX_W = 1024, MAX_H = 1536;
	private static final Identifier ID = Identifier.fromNamespaceAndPath("ultracraft", "v1_doll");
	private static MappedByteBuffer map;
	private static DynamicTexture texture;
	private static int texW, texH;
	private static int lastSeq = -1;
	private static boolean shown;
	private static long lastAsk;
	private static byte[] row = new byte[0];

	private UkDoll() {}

	private static boolean open() {
		if (map != null) return true;
		Path p = UkPaths.shared().resolve("ultracraft_doll" + UltracraftConfig.instanceSuffix() + ".bin");
		try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "r"); FileChannel ch = f.getChannel()) {
			long need = HEADER + (long) MAX_W * MAX_H * 4;
			if (ch.size() < need) return false;
			map = ch.map(FileChannel.MapMode.READ_ONLY, 0, need);
			map.order(ByteOrder.LITTLE_ENDIAN);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Draw V1 into the paper doll's box (x1,y1)-(x2,y2). Returns false (Steve is drawn as usual) until ULTRAKILL has
	 * sent a picture.
	 */
	public static boolean draw(GuiGraphics g, int x1, int y1, int x2, int y2, float mouseX, float mouseY) {
		// the same turn Minecraft gives its doll: body toward the cursor, the head up or down to it
		float cx = (x1 + x2) / 2.0f, cy = (y1 + y2) / 2.0f;
		float yaw = (float) Math.atan((cx - mouseX) / 40.0f) * 20.0f;
		float pitch = (float) Math.atan((cy - mouseY) / 40.0f) * 20.0f;
		double scale = Minecraft.getInstance().getWindow().getGuiScale();
		int pw = (int) Math.round((x2 - x1) * scale), ph = (int) Math.round((y2 - y1) * scale);
		long now = System.currentTimeMillis();
		if (now - lastAsk > 80) {
			lastAsk = now;
			UkLink.send(String.format(Locale.ROOT, "DOLL %.1f %.1f %d %d", yaw, pitch, pw, ph));
		}
		if (!open()) return false;
		int seq = map.getInt(0), w = map.getInt(4), h = map.getInt(8);
		if (w <= 0 || h <= 0 || w > MAX_W || h > MAX_H) return shown;
		if (texture == null || w != texW || h != texH) {
			var textures = Minecraft.getInstance().getTextureManager();
			if (texture != null) textures.release(ID);
			texture = new DynamicTexture(() -> "ultracraft v1 doll", w, h, true);
			textures.register(ID, texture);
			texW = w;
			texH = h;
			lastSeq = -1;
			shown = false;
		}
		if (seq != lastSeq && seq > 0) {
			lastSeq = seq;
			copyFrame(w, h);
			texture.upload();
			shown = true;
		}
		if (!shown) return false;
		// fit the portrait in the box, feet on its floor
		float s = Math.min((x2 - x1) / (float) w, (y2 - y1) / (float) h);
		int dw = Math.round(w * s), dh = Math.round(h * s);
		int x = (x1 + x2 - dw) / 2, y = y2 - dh;
		g.blit(RenderPipelines.GUI_TEXTURED, ID, x, y, 0f, 0f, dw, dh, w, h, w, h);
		return true;
	}

	/**
	 * Unity's rows come bottom-up. Edges arrive averaged with the empty background (premultiplied), so they're divided
	 * back out; glow ULTRAKILL drew without coverage (alpha 0) shows as much as it shines.
	 */
	private static void copyFrame(int w, int h) {
		if (row.length != w * 4) row = new byte[w * 4];
		long dst = texture.getPixels().getPointer();
		ByteBuffer out = MemoryUtil.memByteBuffer(dst, w * h * 4);
		for (int y = 0; y < h; y++) {
			map.get(HEADER + (h - 1 - y) * w * 4, row, 0, row.length);
			for (int i = 0; i < row.length; i += 4) {
				int r = row[i] & 255, gr = row[i + 1] & 255, b = row[i + 2] & 255, a = row[i + 3] & 255;
				if (a == 0) {
					a = Math.max(r, Math.max(gr, b));
					if (a == 0) continue;
				}
				if (a < 255) {
					row[i] = (byte) Math.min(255, r * 255 / a);
					row[i + 1] = (byte) Math.min(255, gr * 255 / a);
					row[i + 2] = (byte) Math.min(255, b * 255 / a);
					row[i + 3] = (byte) a;
				}
			}
			out.put(y * w * 4, row, 0, row.length);
		}
	}
}
