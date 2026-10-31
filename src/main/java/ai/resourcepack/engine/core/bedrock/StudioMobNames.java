package ai.resourcepack.engine.core.bedrock;

/**
 * What Studio's Bedrock pack calls the entity a pushed mob is drawn as, and
 * its animations. Studio's pack builder writes these names and
 * {@link BedrockMobs} asks for them by name, so the two ends have to agree;
 * {@code StudioMobBedrockTest} holds them to a fixture Studio writes from its
 * own builder. Kept free of Geyser so that test can load it.
 */
final class StudioMobNames {

    /** Studio's slot pool: GeyserBridge's {@code SLOT_POOL}, Studio's {@code BEDROCK_SLOT_POOL}. */
    static final int POOL = 200;

    private StudioMobNames() {
    }

    /** The entity Studio's pack draws a mob wearing the model in slot {@code slot} as. */
    static String mobIdentifier(int slot) {
        return "rpai:mob_" + slot;
    }

    /** Studio's name for one of a model's animations. */
    static String animationId(String modelId, String animation) {
        return "animation.rpai." + BedrockGeometry.sanitize(modelId) + "." + BedrockGeometry.sanitize(animation);
    }

    /** Studio's empty animation a worn model is put back to rest with. */
    static String restAnimation(String modelId) {
        return "animation.rpai." + BedrockGeometry.sanitize(modelId) + ".rest";
    }
}
