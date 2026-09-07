package ai.resourcepack.engine.core.emote;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The player skeleton an emote rig is made of, as block-model geometry.
 *
 * <p><strong>A hand port of Studio's emote skeleton, and the two have to
 * agree to the pixel.</strong> Studio bakes exactly this geometry into a
 * pushed pack, and the animator poses bones by the pivots in the manifest
 * table below; an emote authored in Studio's editor and played on a rig baked
 * here has to land its hands where the editor drew them. Nothing checks the
 * agreement — the other end is a TypeScript file this jar cannot see — so the
 * numbers are written out plainly rather than derived cleverly, and every
 * one of them is Mojang's: the box sizes are the vanilla player model's, the
 * sheet offsets are the 64x64 skin layout's, and the joints sit where the
 * vanilla model bends.
 *
 * <p>Rig space puts the feet at y=0, faces -z, and is centred on x=z=0; a
 * block model's element bounds are -16..32 and an item display renders its
 * 0-16 space centred on the entity, so the whole body only fits with the
 * origin a block up — {@link #RIG_ORIGIN_PX}. That single offset is the one
 * thing here that is not a body measurement, and getting it wrong drops the
 * entire model rather than one cube: the game refuses to bake a model with
 * an element out of bounds at all.
 *
 * <p>Two skeletons are baked because both are played. The whole-limb six are
 * what every emote was authored against before elbows and knees existed and
 * are still baked under the names the animator has always used; the jointed
 * ten are baked beside them under {@code __jointed__} names, and an emote
 * that never moves a forearm plays identically on either. The arms are baked
 * at both widths because a skin is wide (4px arms) or slim (3px) and the
 * wrong one reads as a broken arm; which a player actually is comes off their
 * live profile at spawn time ({@link SkinModel}), not off the pixels.
 *
 * <p>Pure: no Bukkit, no files. {@link RigBaker} turns this into pack files.
 */
final class RigGeometry {

    /** How far above the feet the rig's displays sit, in px. See the class note. */
    static final int RIG_ORIGIN_PX = 16;

    /** Every skin is 64 by 64; a legacy 64x32 sheet is a Studio import concern, not ours. */
    static final int SKIN_SIZE = 64;

    static final String WIDE = "wide";
    static final String SLIM = "slim";

    /** The texture reference every bone element samples. */
    static final String SKIN_TEXTURE_REF = "skin";

    /** Whole-limb bones, in model order. The first six of {@link #ALL_BONES}. */
    static final List<String> WHOLE_BONES = List.of(
            "head", "body", "rightArm", "leftArm", "rightLeg", "leftLeg");

    /** Every bone of the jointed skeleton, parents before children. */
    static final List<String> ALL_BONES = List.of(
            "head", "body", "rightArm", "leftArm", "rightLeg", "leftLeg",
            "rightForearm", "leftForearm", "rightShin", "leftShin");

    /** The bones whose geometry the arm width changes. Everything else is baked once. */
    static final List<String> VARIANT_BONES = List.of(
            "rightArm", "leftArm", "rightForearm", "leftForearm");

    /** The hat layer stands further off the head than the other overlays do. */
    private static final double HAT_INFLATE = 0.5;
    private static final double OVERLAY_INFLATE = 0.25;

    private RigGeometry() {
    }

    /** A box on the skin sheet: its net origin and its size. */
    static final class SkinBox {
        final int u;
        final int v;
        final int w;
        final int h;
        final int d;

        SkinBox(int u, int v, int w, int h, int d) {
            this.u = u;
            this.v = v;
            this.w = w;
            this.h = h;
            this.d = d;
        }
    }

    /** One rectangle of a box's unwrapped net, in sheet px. */
    static final class FaceRect {
        final String face;
        final int x;
        final int y;
        final int w;
        final int h;

        FaceRect(String face, int x, int y, int w, int h) {
            this.face = face;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /** One bone: what it is called, where it hinges, what it draws. */
    static final class Bone {
        final String key;
        final String parent;
        final double[] pivot;
        final String basePart;
        final String overlayPart;
        final double[] from;
        final double[] to;
        /** Rows of the sheet box drawn, or null for the whole box. */
        final int[] rows;

        Bone(String key, String parent, double[] pivot, String basePart, String overlayPart,
             double[] from, double[] to, int[] rows) {
            this.key = key;
            this.parent = parent;
            this.pivot = pivot;
            this.basePart = basePart;
            this.overlayPart = overlayPart;
            this.from = from;
            this.to = to;
            this.rows = rows;
        }
    }

    static int armWidth(String variant) {
        return SLIM.equals(variant) ? 3 : 4;
    }

    /**
     * Which pixels of the sheet belong to which part, for an arm width.
     *
     * <p>A box's pixels are not a rectangle: the game unwraps a w by h by d
     * box into a net whose top band has empty corners. {@link #faceRects}
     * is the one definition of where the six faces actually are.
     */
    static Map<String, SkinBox> skinParts(String variant) {
        int aw = armWidth(variant);
        return Map.ofEntries(
                Map.entry("head", new SkinBox(0, 0, 8, 8, 8)),
                Map.entry("hat", new SkinBox(32, 0, 8, 8, 8)),
                Map.entry("body", new SkinBox(16, 16, 8, 12, 4)),
                Map.entry("jacket", new SkinBox(16, 32, 8, 12, 4)),
                Map.entry("rightArm", new SkinBox(40, 16, aw, 12, 4)),
                Map.entry("rightSleeve", new SkinBox(40, 32, aw, 12, 4)),
                Map.entry("leftArm", new SkinBox(32, 48, aw, 12, 4)),
                Map.entry("leftSleeve", new SkinBox(48, 48, aw, 12, 4)),
                Map.entry("rightLeg", new SkinBox(0, 16, 4, 12, 4)),
                Map.entry("rightPants", new SkinBox(0, 32, 4, 12, 4)),
                Map.entry("leftLeg", new SkinBox(16, 48, 4, 12, 4)),
                Map.entry("leftPants", new SkinBox(0, 48, 4, 12, 4)));
    }

    static List<FaceRect> faceRects(SkinBox box) {
        return List.of(
                new FaceRect("top", box.u + box.d, box.v, box.w, box.d),
                new FaceRect("bottom", box.u + box.d + box.w, box.v, box.w, box.d),
                new FaceRect("right", box.u, box.v + box.d, box.d, box.h),
                new FaceRect("front", box.u + box.d, box.v + box.d, box.w, box.h),
                new FaceRect("left", box.u + box.d + box.w, box.v + box.d, box.d, box.h),
                new FaceRect("back", box.u + box.d + box.w + box.d, box.v + box.d, box.w, box.h));
    }

    /**
     * The rig faces -z, and Minecraft's -z is north: the character's front
     * is the north face and their own right (+x) is east. Backwards mirrors
     * the whole body and still renders, which is the silent kind of wrong.
     */
    static String faceOf(String rigFace) {
        switch (rigFace) {
            case "top": return "up";
            case "bottom": return "down";
            case "right": return "east";
            case "front": return "north";
            case "left": return "west";
            default: return "south";
        }
    }

    /**
     * A face's {@code uv}, in the orientation the game samples it: upright
     * everywhere, both axes reversed on the top, the x axis alone reversed on
     * the bottom. Mojang's unwrap really does mirror a box's underside
     * relative to its top; that asymmetry is theirs.
     */
    static double[] faceUv(String face, int x, int y, int w, int h) {
        double x1 = x * 16.0 / SKIN_SIZE;
        double y1 = y * 16.0 / SKIN_SIZE;
        double x2 = (x + w) * 16.0 / SKIN_SIZE;
        double y2 = (y + h) * 16.0 / SKIN_SIZE;
        if (face.equals("top")) {
            return new double[] {x2, y2, x1, y1};
        }
        if (face.equals("bottom")) {
            return new double[] {x2, y1, x1, y2};
        }
        return new double[] {x1, y1, x2, y2};
    }

    /**
     * The bones, for an arm width and a skeleton.
     *
     * <p>A jointed limb is one sheet box drawn by two bones: rows 0-6 of the
     * upper arm, 6-12 of the forearm, the joint pivot sitting on the cut at
     * the same x/z the shoulder uses, so a hinge turns the lower half about
     * exactly the edge the upper half ends on.
     */
    static List<Bone> bones(String variant, boolean jointed) {
        int aw = armWidth(variant);
        Bone head = new Bone("head", null, new double[] {0, 24, 0}, "head", "hat",
                new double[] {-4, 24, -4}, new double[] {4, 32, 4}, null);
        Bone body = new Bone("body", null, new double[] {0, 24, 0}, "body", "jacket",
                new double[] {-4, 12, -2}, new double[] {4, 24, 2}, null);
        List<Bone> out = new ArrayList<>();
        out.add(head);
        out.add(body);
        if (!jointed) {
            out.add(new Bone("rightArm", null, new double[] {4, 22, 0}, "rightArm", "rightSleeve",
                    new double[] {4, 12, -2}, new double[] {4 + aw, 24, 2}, null));
            out.add(new Bone("leftArm", null, new double[] {-4, 22, 0}, "leftArm", "leftSleeve",
                    new double[] {-(4 + aw), 12, -2}, new double[] {-4, 24, 2}, null));
            out.add(new Bone("rightLeg", null, new double[] {0, 12, 0}, "rightLeg", "rightPants",
                    new double[] {0, 0, -2}, new double[] {4, 12, 2}, null));
            out.add(new Bone("leftLeg", null, new double[] {0, 12, 0}, "leftLeg", "leftPants",
                    new double[] {-4, 0, -2}, new double[] {0, 12, 2}, null));
            return out;
        }
        out.add(new Bone("rightArm", null, new double[] {4, 22, 0}, "rightArm", "rightSleeve",
                new double[] {4, 18, -2}, new double[] {4 + aw, 24, 2}, new int[] {0, 6}));
        out.add(new Bone("leftArm", null, new double[] {-4, 22, 0}, "leftArm", "leftSleeve",
                new double[] {-(4 + aw), 18, -2}, new double[] {-4, 24, 2}, new int[] {0, 6}));
        out.add(new Bone("rightLeg", null, new double[] {0, 12, 0}, "rightLeg", "rightPants",
                new double[] {0, 6, -2}, new double[] {4, 12, 2}, new int[] {0, 6}));
        out.add(new Bone("leftLeg", null, new double[] {0, 12, 0}, "leftLeg", "leftPants",
                new double[] {-4, 6, -2}, new double[] {0, 12, 2}, new int[] {0, 6}));
        out.add(new Bone("rightForearm", "rightArm", new double[] {4, 18, 0}, "rightArm", "rightSleeve",
                new double[] {4, 12, -2}, new double[] {4 + aw, 18, 2}, new int[] {6, 12}));
        out.add(new Bone("leftForearm", "leftArm", new double[] {-4, 18, 0}, "leftArm", "leftSleeve",
                new double[] {-(4 + aw), 12, -2}, new double[] {-4, 18, 2}, new int[] {6, 12}));
        out.add(new Bone("rightShin", "rightLeg", new double[] {0, 6, 0}, "rightLeg", "rightPants",
                new double[] {0, 0, -2}, new double[] {4, 6, 2}, new int[] {6, 12}));
        out.add(new Bone("leftShin", "leftLeg", new double[] {0, 6, 0}, "leftLeg", "leftPants",
                new double[] {-4, 0, -2}, new double[] {0, 6, 2}, new int[] {6, 12}));
        return out;
    }

    /**
     * Rig px to block-model space. The offset is vertical only: rig space is
     * already centred on x=z=0, so those just move to the middle of the
     * block, while y additionally drops by {@link #RIG_ORIGIN_PX}.
     */
    static double[] toModelPoint(double x, double y, double z) {
        return new double[] {x + 8, y + 8 - RIG_ORIGIN_PX, z + 8};
    }

    /**
     * The faces of one bone's box, or of a band of its rows.
     *
     * <p>With rows, the four sides are the same cells cropped to those rows,
     * and the top and bottom are the box's own only where the band reaches
     * the box's own end. The other end is a cut the sheet has no pixels for —
     * there is no underside of an upper arm anywhere in a skin — so it
     * samples a one-row strip of the front face at the cut, stretched across:
     * a bent elbow shows flesh where the boxes part rather than a hole.
     */
    static JsonObject faces(SkinBox part, int[] rows) {
        JsonObject faces = new JsonObject();
        List<FaceRect> rects = faceRects(part);
        FaceRect front = rects.get(3);
        for (FaceRect rect : rects) {
            int x = rect.x;
            int y = rect.y;
            int w = rect.w;
            int h = rect.h;
            if (rows != null) {
                int r0 = rows[0];
                int r1 = rows[1];
                if (rect.face.equals("top") && r0 > 0) {
                    x = front.x;
                    y = front.y + r0;
                    w = front.w;
                    h = 1;
                } else if (rect.face.equals("bottom") && r1 < part.h) {
                    x = front.x;
                    y = front.y + r1 - 1;
                    w = front.w;
                    h = 1;
                } else if (!rect.face.equals("top") && !rect.face.equals("bottom")) {
                    y = rect.y + r0;
                    h = r1 - r0;
                }
            }
            JsonObject face = new JsonObject();
            face.add("uv", array(faceUv(rect.face, x, y, w, h)));
            face.addProperty("texture", "#" + SKIN_TEXTURE_REF);
            faces.add(faceOf(rect.face), face);
        }
        return faces;
    }

    /**
     * One bone's two elements — its skin box and the overlay inflated by
     * Mojang's per-layer amount — in block-model space.
     *
     * <p>An overlay does not grow across a cut: the sleeve of an upper arm
     * ends exactly at the elbow and the forearm's starts exactly there, so at
     * rest the two shells meet edge to edge rather than overlapping by half a
     * pixel and flickering.
     */
    static JsonArray elements(Bone bone, String variant) {
        Map<String, SkinBox> parts = skinParts(variant);
        JsonArray elements = new JsonArray();
        double inflate = bone.overlayPart.equals("hat") ? HAT_INFLATE : OVERLAY_INFLATE;
        String[] keys = {bone.basePart, bone.overlayPart};
        double[] grows = {0, inflate};
        for (int i = 0; i < 2; i++) {
            SkinBox part = parts.get(keys[i]);
            if (part == null) {
                continue;
            }
            double grow = grows[i];
            boolean cutBelow = bone.rows != null && bone.rows[1] < part.h;
            boolean cutAbove = bone.rows != null && bone.rows[0] > 0;
            JsonObject element = new JsonObject();
            element.addProperty("name", bone.key + (grow > 0 ? "_overlay" : ""));
            element.add("from", array(toModelPoint(
                    bone.from[0] - grow, bone.from[1] - (cutBelow ? 0 : grow), bone.from[2] - grow)));
            element.add("to", array(toModelPoint(
                    bone.to[0] + grow, bone.to[1] + (cutAbove ? 0 : grow), bone.to[2] + grow)));
            element.add("faces", faces(part, bone.rows));
            elements.add(element);
        }
        return elements;
    }

    /**
     * The model file for one bone.
     *
     * <p>Ambient occlusion off because this is a body, not masonry — AO
     * darkens the inner faces of the arms against the torso and reads as
     * grime; vanilla draws players through the entity pipeline, which has
     * none. Cutout because the overlay layer is transparent almost everywhere
     * and the default render ignores alpha, which would put an opaque box
     * round every head.
     */
    static JsonObject boneModel(String textureRef, JsonArray elements) {
        JsonObject model = new JsonObject();
        model.addProperty("parent", "block/block");
        model.addProperty("ambientocclusion", false);
        model.addProperty("render_type", "cutout");
        JsonObject textures = new JsonObject();
        textures.addProperty(SKIN_TEXTURE_REF, textureRef);
        model.add("textures", textures);
        model.add("elements", elements);
        return model;
    }

    /**
     * The name of one bone's model, and so of the item that wears it — the
     * other half of {@link EmoteStore#boneItemId}. {@code <prefix>__rightarm},
     * {@code <prefix>__slim__rightarm}, {@code <prefix>__jointed__head},
     * {@code <prefix>__jointed__wide__rightforearm}.
     */
    static String itemName(String prefix, String bone, String variant, boolean jointed) {
        StringBuilder name = new StringBuilder(prefix);
        if (jointed) {
            name.append("__jointed");
        }
        if (variant != null) {
            name.append("__").append(variant);
        }
        return name.append("__").append(bone.toLowerCase(Locale.ROOT)).toString();
    }

    /** The whole-limb bone table for the manifest: key and pivot, variant-independent. */
    static List<EmoteStore.Bone> manifestBones() {
        List<EmoteStore.Bone> out = new ArrayList<>();
        for (Bone bone : bones(WIDE, false)) {
            out.add(manifestBone(bone));
        }
        return out;
    }

    /** The jointed bone table for the manifest, parents before children. */
    static List<EmoteStore.Bone> manifestJointedBones() {
        List<EmoteStore.Bone> out = new ArrayList<>();
        for (Bone bone : bones(WIDE, true)) {
            out.add(manifestBone(bone));
        }
        return out;
    }

    /** The joint the root turns about: the hip, mid-way up the rig. */
    static float[] rootPivot() {
        double[] pivot = toModelPoint(0, 12, 0);
        return new float[] {(float) pivot[0], (float) pivot[1], (float) pivot[2]};
    }

    private static EmoteStore.Bone manifestBone(Bone bone) {
        EmoteStore.Bone out = new EmoteStore.Bone();
        out.key = bone.key;
        out.parent = bone.parent;
        double[] pivot = toModelPoint(bone.pivot[0], bone.pivot[1], bone.pivot[2]);
        out.pivot = new float[] {(float) pivot[0], (float) pivot[1], (float) pivot[2]};
        return out;
    }

    private static JsonArray array(double[] values) {
        JsonArray array = new JsonArray();
        for (double value : values) {
            // Whole numbers as integers, so the JSON reads as the model
            // format is usually written and diffs against Studio's output.
            if (value == Math.rint(value)) {
                array.add((int) value);
            } else {
                array.add(value);
            }
        }
        return array;
    }
}
