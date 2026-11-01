package ai.resourcepack.engine.core.stinger;

import ai.resourcepack.engine.api.StingerInfo;

/**
 * One stinger the server can play: what the API says about it, the character
 * its title shows, and the colours that make the pack's text shader play each
 * half of it ({@code #rrggbb}).
 */
public record StingerEntry(StingerInfo info, String glyph, String inColor, String outColor, String autoColor) {
}
