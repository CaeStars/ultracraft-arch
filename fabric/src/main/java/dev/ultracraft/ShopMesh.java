package dev.ultracraft;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * ULTRAKILL's shop terminal for Minecraft to draw: ULTRAKILL writes it out once it has loaded its shop (the mesh in
 * blocks, standing on the middle of its 2 x 1 floor with its screen to the north; its texture and the glow of its
 * screen and lights) to its temp (the prefix's under Proton, see UkPaths), and a copy is kept in the config folder.
 * Nothing of ULTRAKILL's ships with the mod:
 * until ULTRAKILL has run once, the shop is a plain metal cabinet of the same size.
 */
final class ShopMesh {
	private static final Identifier FALLBACK = Identifier.fromNamespaceAndPath("ultracraft", "textures/block/uk_shop.png");
	private static final Identifier DYNAMIC = Identifier.fromNamespaceAndPath("ultracraft", "dynamic/shop_terminal2");
	private static final Identifier DYNAMIC_GLOW = Identifier.fromNamespaceAndPath("ultracraft", "dynamic/shop_terminal2_glow");
	private static final String BIN = "ultracraft_shop2.bin", PNG = "ultracraft_shop2.png", GLOW = "ultracraft_shop2_glow.png", TXT = "ultracraft_shop2.txt";
	private static float[] pos, nrm, uv;
	private static int[] idx;
	private static Identifier texture = FALLBACK;
	private static @Nullable Identifier glow;
	private static float su = 1f, sv = 1f, ou, ov;
	private static long nextCheck, tmpStamp, loadedStamp;
	private static boolean haveUk;

	private ShopMesh() {}

	/** Something to draw: ULTRAKILL's terminal once it has written it out (checked every few seconds), a box till then. */
	static boolean load() {
		long now = System.currentTimeMillis();
		if (now > nextCheck) {
			nextCheck = now + 5000;
			try {
				refresh();
			} catch (Exception e) {
				org.slf4j.LoggerFactory.getLogger("ultracraft").warn("shop looks: {}", e.toString());
			}
		}
		if (pos == null) box();
		return true;
	}

	static Identifier texture() {
		return texture;
	}

	/** The glow of its screen and lights (drawn on top, full bright), if ULTRAKILL's terminal has one. */
	static @Nullable Identifier glow() {
		return glow;
	}

	private static void refresh() throws Exception {
		Path tmp = UkPaths.shared(), cfg = FabricLoader.getInstance().getConfigDir();
		Path tmpBin = tmp.resolve(BIN);
		// ULTRAKILL writes them to its temp each time it loads its shop; the copy here outlives a cleaned-up temp
		if (Files.exists(tmpBin) && Files.exists(tmp.resolve(PNG))) {
			long stamp = Files.getLastModifiedTime(tmpBin).toMillis();
			if (stamp != tmpStamp) {
				tmpStamp = stamp;
				for (String f : new String[] {BIN, PNG, GLOW, TXT}) {
					if (Files.exists(tmp.resolve(f))) Files.copy(tmp.resolve(f), cfg.resolve(f), StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		Path bin = cfg.resolve(BIN), png = cfg.resolve(PNG);
		if (!Files.exists(bin) || !Files.exists(png)) return;
		long stamp = Files.getLastModifiedTime(bin).toMillis();
		if (haveUk && stamp == loadedStamp) return;
		ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(bin)).order(ByteOrder.LITTLE_ENDIAN);
		int nv = b.getInt(), ni = b.getInt();
		float[] p = new float[nv * 3], n = new float[nv * 3], t = new float[nv * 2];
		int[] ix = new int[ni];
		for (int i = 0; i < p.length; i++) p[i] = b.getFloat();
		for (int i = 0; i < n.length; i++) n[i] = b.getFloat();
		for (int i = 0; i < t.length; i++) t[i] = b.getFloat();
		for (int i = 0; i < ni; i++) ix[i] = b.getInt();
		var textures = Minecraft.getInstance().getTextureManager();
		try (InputStream in = Files.newInputStream(png)) {
			textures.register(DYNAMIC, new DynamicTexture(() -> "ultracraft shop terminal", NativeImage.read(in)));
		}
		Path glowPng = cfg.resolve(GLOW);
		if (Files.exists(glowPng)) {
			try (InputStream in = Files.newInputStream(glowPng)) {
				textures.register(DYNAMIC_GLOW, new DynamicTexture(() -> "ultracraft shop terminal glow", glowing(NativeImage.read(in))));
			}
			glow = DYNAMIC_GLOW;
		}
		Path txt = cfg.resolve(TXT);
		if (Files.exists(txt)) {
			String[] a = Files.readString(txt).trim().split("\\s+");
			su = Float.parseFloat(a[0]);
			sv = Float.parseFloat(a[1]);
			ou = Float.parseFloat(a[2]);
			ov = Float.parseFloat(a[3]);
		}
		pos = p;
		nrm = n;
		uv = t;
		idx = ix;
		texture = DYNAMIC;
		haveUk = true;
		loadedStamp = stamp;
	}

	/**
	 * ULTRAKILL's glow map is opaque, black where nothing glows; Minecraft's glow pass (eyes) blends by alpha. So each
	 * pixel's brightness becomes its alpha (and its colour is brought up to full): what glows shows, the rest doesn't.
	 */
	private static NativeImage glowing(NativeImage img) {
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				int c = img.getPixel(x, y);
				int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255, m = Math.max(r, Math.max(g, b));
				if (m == 0) {
					img.setPixel(x, y, 0);
					continue;
				}
				img.setPixel(x, y, (m << 24) | (r * 255 / m << 16) | (g * 255 / m << 8) | b * 255 / m);
			}
		}
		return img;
	}

	/** Until ULTRAKILL has run: a cabinet the terminal's size, 2 wide, 3 high, 0.9 deep. */
	private static void box() {
		float x0 = -1f, x1 = 1f, y0 = 0f, y1 = 3f, z0 = -0.45f, z1 = 0.45f;
		float[][] faces = {
			// four corners (bottom left, top left, top right, bottom right seen from outside), then the normal
			{x1, y0, z0, x1, y1, z0, x0, y1, z0, x0, y0, z0, 0, 0, -1},
			{x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, 0, 0, 1},
			{x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, -1, 0, 0},
			{x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0, 1, 0, 0},
			{x0, y1, z1, x0, y1, z0, x1, y1, z0, x1, y1, z1, 0, 1, 0},
			{x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, 0, -1, 0}};
		pos = new float[6 * 4 * 3];
		nrm = new float[6 * 4 * 3];
		uv = new float[6 * 4 * 2];
		idx = new int[6 * 6];
		// Unity-style texture coordinates (v up), as vertex() expects
		float[][] corners = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
		for (int f = 0; f < 6; f++) {
			for (int c = 0; c < 4; c++) {
				int v = f * 4 + c;
				System.arraycopy(faces[f], c * 3, pos, v * 3, 3);
				System.arraycopy(faces[f], 12, nrm, v * 3, 3);
				uv[v * 2] = corners[c][0];
				uv[v * 2 + 1] = corners[c][1];
			}
			int[] t = {0, 2, 1, 0, 3, 2};
			for (int k = 0; k < 6; k++) idx[f * 6 + k] = f * 4 + t[k];
		}
		su = sv = 1f;
		ou = ov = 0f;
	}

	/** Every triangle as a quad with its last corner twice (the entity render types draw quads). */
	static void emit(PoseStack.Pose pose, VertexConsumer vc, int light) {
		for (int t = 0; t + 2 < idx.length; t += 3) {
			vertex(pose, vc, light, idx[t]);
			vertex(pose, vc, light, idx[t + 1]);
			vertex(pose, vc, light, idx[t + 2]);
			vertex(pose, vc, light, idx[t + 2]);
		}
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer vc, int light, int i) {
		// Unity's texture coordinates (v up, the material's tiling) to Minecraft's (v down)
		float u = uv[i * 2] * su + ou, v = 1f - (uv[i * 2 + 1] * sv + ov);
		vc.addVertex(pose, pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]).setColor(-1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
			.setNormal(pose, nrm[i * 3], nrm[i * 3 + 1], nrm[i * 3 + 2]);
	}
}
