package ai.resourcepack.engine.core.vfx;

import ai.resourcepack.engine.api.VfxInfo;

/** One effect the server can play: what the API says about it, and the cleaned recipe it plays. */
public record VfxEntry(VfxInfo info, VfxRecipe recipe) {
}
