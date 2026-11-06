package ai.resourcepack.engine.core.transition;

import ai.resourcepack.engine.api.TransitionInfo;

/**
 * One transition the server can play: what the API says about it, the character
 * its title shows, and the colours that make the pack's text shader play each
 * half of it ({@code #rrggbb}).
 */
public record TransitionEntry(TransitionInfo info, String glyph, String inColor, String outColor, String autoColor) {
}
