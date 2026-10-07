package dev.ultracraft;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;

/**
 * V1's real arm (ULTRAKILL's Feedbacker, as ULTRAKILL shows it in first person) for Minecraft's hands. ULTRAKILL writes
 * it to its own temp once it's running (ultracraft_arm.*: its meshes in the view's space, their textures, where its hand is
 * and the view's field of view); a copy is kept in the config folder. Until then V1Arm draws a stand-in.
 *
 * <p>The Feedbacker is V1's left arm; for Minecraft's right main hand it's turned round to the right side (a turn, not a
 * mirror, since Unity's space is left-handed and Minecraft's right-handed). It's sized so it looks as big as in
 * ULTRAKILL, with its hand on the held item's grip.
 */
final class V1ArmMesh {
	private static final String BIN = "ultracraft_arm.bin", TXT = "ultracraft_arm.txt";
	/** How far in front of the eye Minecraft holds an item (blocks), with V1's hand lifting it into view. */
	private static final float ITEM_DEPTH = 0.72f;

	private record Part(float[] pos, float[] nrm, float[] uv, int[] idx, Identifier texture) {}

	private static final List<Part> PARTS = new ArrayList<>();
	private static float fov = 90f, hx, hy, hz = 1f, extent = 0.13f;
	private static long nextCheck, tmpStamp, loadedStamp;
	private static boolean loaded;

	private V1ArmMesh() {}

	/** ULTRAKILL's arm is here to draw (looked for every few seconds until it is). */
	static boolean ready() {
		long now = System.currentTimeMillis();
		if (now > nextCheck) {
			nextCheck = now + (loaded ? 10000 : 3000);
			try {
				refresh();
			} catch (Exception e) {
				org.slf4j.LoggerFactory.getLogger("ultracraft").warn("V1 arm: {}", e.toString());
			}
		}
		return loaded;
	}

	private static void refresh() throws Exception {
		Path tmp = UkPaths.shared(), cfg = FabricLoader.getInstance().getConfigDir();
		Path tmpBin = tmp.resolve(BIN);
		if (Files.exists(tmpBin) && Files.exists(tmp.resolve(TXT))) {
			long stamp = Files.getLastModifiedTime(tmpBin).toMillis();
			if (stamp != tmpStamp) {
				tmpStamp = stamp;
				try (var files = Files.list(tmp)) {
					for (Path f : (Iterable<Path>) files.filter(f -> f.getFileName().toString().startsWith("ultracraft_arm"))::iterator) {
						Files.copy(f, cfg.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
		}
		Path bin = cfg.resolve(BIN), txt = cfg.resolve(TXT);
		if (!Files.exists(bin) || !Files.exists(txt)) return;
		long stamp = Files.getLastModifiedTime(bin).toMillis();
		if (loaded && stamp == loadedStamp) return;
		String[] t = Files.readString(txt).trim().split("\\s+");
		fov = Float.parseFloat(t[0]);
		hx = Float.parseFloat(t[1]);
		hy = Float.parseFloat(t[2]);
		hz = Float.parseFloat(t[3]);
		ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(bin)).order(ByteOrder.LITTLE_ENDIAN);
		int parts = b.getInt();
		List<Part> list = new ArrayList<>();
		var textures = Minecraft.getInstance().getTextureManager();
		List<Identifier> texIds = new ArrayList<>();
		for (int k = 0; k < parts; k++) {
			int nv = b.getInt(), ni = b.getInt(), tex = b.getInt();
			float[] p = new float[nv * 3], n = new float[nv * 3], uv = new float[nv * 2];
			int[] ix = new int[ni];
			for (int i = 0; i < p.length; i++) p[i] = b.getFloat();
			for (int i = 0; i < n.length; i++) n[i] = b.getFloat();
			for (int i = 0; i < uv.length; i++) uv[i] = b.getFloat();
			for (int i = 0; i < ni; i++) ix[i] = b.getInt();
			while (texIds.size() <= tex) {
				int i = texIds.size();
				Identifier id = Identifier.fromNamespaceAndPath("ultracraft", "dynamic/v1_arm_" + i);
				Path png = cfg.resolve("ultracraft_arm_" + i + ".png");
				if (Files.exists(png)) {
					try (InputStream in = Files.newInputStream(png)) {
						textures.register(id, new DynamicTexture(() -> "ultracraft V1 arm", NativeImage.read(in)));
					}
				}
				texIds.add(id);
			}
			list.add(new Part(p, n, uv, ix, texIds.get(Math.max(0, tex))));
		}
		// its longest side
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (Part part : list) for (int i = 0; i < part.pos.length; i++) { lo[i % 3] = Math.min(lo[i % 3], part.pos[i]); hi[i % 3] = Math.max(hi[i % 3], part.pos[i]); }
		extent = Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2]));
		PARTS.clear();
		PARTS.addAll(list);
		loaded = !PARTS.isEmpty();
		loadedStamp = stamp;
		org.slf4j.LoggerFactory.getLogger("ultracraft").info("V1 arm loaded: {} parts, hand ({}, {}, {}), fov {}", parts, hx, hy, hz, fov);
	}

	/** The arm with its hand at the pose's origin (the held item's grip); x right, y up, z toward the eye. */
	static void render(PoseStack poseStack, SubmitNodeCollector collector, int light, HumanoidArm arm) {
		// as big on screen as ULTRAKILL shows it: its hand is hz in front of ULTRAKILL's eye with ULTRAKILL's field of
		// view, the item ITEM_DEPTH in front of Minecraft's with the hand's 70 degrees
		// the arm about half a block long: as big as Steve's would look, and clear of the middle of the view when it swings
		float s = 0.5f / Math.max(0.01f, extent);
		// right hand: turned round from ULTRAKILL's left arm (x and z flip); left hand: z flips only
		float fx = arm == HumanoidArm.RIGHT ? -1f : 1f;
		poseStack.pushPose();
		poseStack.scale(s, s, s);
		for (Part part : PARTS) {
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(part.texture), (pose, vc) -> {
				int[] ix = part.idx;
				for (int t = 0; t + 2 < ix.length; t += 3) {
					vertex(vc, pose, part, ix[t], fx, light);
					vertex(vc, pose, part, ix[t + 1], fx, light);
					vertex(vc, pose, part, ix[t + 2], fx, light);
					vertex(vc, pose, part, ix[t + 2], fx, light);
				}
			});
		}
		poseStack.popPose();
	}

	private static void vertex(com.mojang.blaze3d.vertex.VertexConsumer vc, PoseStack.Pose pose, Part part, int i, float fx, int light) {
		float x = (part.pos[i * 3] - hx) * fx, y = part.pos[i * 3 + 1] - hy, z = -(part.pos[i * 3 + 2] - hz);
		vc.addVertex(pose, x, y, z).setColor(-1).setUv(part.uv[i * 2], 1f - part.uv[i * 2 + 1]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
			.setNormal(pose, part.nrm[i * 3] * fx, part.nrm[i * 3 + 1], -part.nrm[i * 3 + 2]);
	}
}
