package ai.resourcepack.engine.core.pack.shader;

import java.util.Map;
import java.util.Set;

/**
 * GENERATED from the game's own shader include files (misode/mcmeta). Do not edit.
 *
 * <p>What each of the game's shader include files declares, per pack format,
 * and which files it imports in turn: the names a {@code #moj_import} brings.
 * Names only, never the files. See {@link GlslScan}.
 */
final class VanillaIncludes {

    private VanillaIncludes() {
    }

    /** One include on one format: what it declares, and what it imports. */
    record Include(Set<String> names, Set<String> imports) {
    }

    /** Pack format, newest release of it, to file name, to what that file declares. */
    static final Map<Integer, Map<String, Include>> BY_FORMAT = Map.ofEntries(
            // 1.19.4
            Map.entry(13, Map.ofEntries(
                    Map.entry("fog.glsl", new Include(Set.of("fog_distance", "linear_fog", "linear_fog_fade"), Set.of())),
                    Map.entry("light.glsl", new Include(Set.of("minecraft_mix_light", "minecraft_sample_lightmap"), Set.of())),
                    Map.entry("matrix.glsl", new Include(Set.of("mat2_rotate_z"), Set.of())),
                    Map.entry("projection.glsl", new Include(Set.of("projection_from_position"), Set.of()))
            )),
            // 1.20.1
            Map.entry(15, Map.ofEntries(
                    Map.entry("fog.glsl", new Include(Set.of("fog_distance", "linear_fog", "linear_fog_fade"), Set.of())),
                    Map.entry("light.glsl", new Include(Set.of("minecraft_mix_light", "minecraft_sample_lightmap"), Set.of())),
                    Map.entry("matrix.glsl", new Include(Set.of("mat2_rotate_z"), Set.of())),
                    Map.entry("projection.glsl", new Include(Set.of("projection_from_position"), Set.of()))
            )),
            // 1.20.2
            Map.entry(18, Map.ofEntries(
                    Map.entry("fog.glsl", new Include(Set.of("fog_distance", "linear_fog", "linear_fog_fade"), Set.of())),
                    Map.entry("light.glsl", new Include(Set.of("minecraft_mix_light", "minecraft_sample_lightmap"), Set.of())),
                    Map.entry("matrix.glsl", new Include(Set.of("mat2_rotate_z"), Set.of())),
                    Map.entry("projection.glsl", new Include(Set.of("projection_from_position"), Set.of()))
            )),
            // 1.20.4
            Map.entry(22, Map.ofEntries(
                    Map.entry("fog.glsl", new Include(Set.of("fog_distance", "linear_fog", "linear_fog_fade"), Set.of())),
                    Map.entry("light.glsl", new Include(Set.of("minecraft_mix_light", "minecraft_sample_lightmap"), Set.of())),
                    Map.entry("matrix.glsl", new Include(Set.of("mat2_rotate_z"), Set.of())),
                    Map.entry("projection.glsl", new Include(Set.of("projection_from_position"), Set.of()))
            )),
            // 1.20.6
            Map.entry(32, Map.ofEntries(
                    Map.entry("fog.glsl", new Include(Set.of("fog_distance", "linear_fog", "linear_fog_fade"), Set.of())),
                    Map.entry("light.glsl", new Include(Set.of("minecraft_mix_light", "minecraft_sample_lightmap"), Set.of())),
                    Map.entry("matrix.glsl", new Include(Set.of("mat2_rotate_z"), Set.of())),
                    Map.entry("projection.glsl", new Include(Set.of("projection_from_position"), Set.of()))
            )),
            // 1.21.1
            Map.entry(34, Map.ofEntries(
                    Map.entry("fog.glsl", new Include(Set.of("fog_distance", "linear_fog", "linear_fog_fade"), Set.of())),
                    Map.entry("light.glsl", new Include(Set.of("minecraft_mix_light", "minecraft_sample_lightmap"), Set.of())),
                    Map.entry("matrix.glsl", new Include(Set.of("mat2_rotate_z"), Set.of())),
                    Map.entry("projection.glsl", new Include(Set.of("projection_from_position"), Set.of()))
            )),
            // 1.21.3
            Map.entry(42, Map.ofEntries(
                    Map.entry("fog.glsl", new Include(Set.of("fog_distance", "linear_fog", "linear_fog_fade"), Set.of())),
                    Map.entry("light.glsl", new Include(Set.of("minecraft_mix_light", "minecraft_sample_lightmap"), Set.of())),
                    Map.entry("matrix.glsl", new Include(Set.of("mat2_rotate_z"), Set.of())),
                    Map.entry("projection.glsl", new Include(Set.of("projection_from_position"), Set.of()))
            )),
            // 1.21.4
            Map.entry(46, Map.ofEntries(
                    Map.entry("fog.glsl", new Include(Set.of("fog_distance", "linear_fog", "linear_fog_fade"), Set.of())),
                    Map.entry("light.glsl", new Include(Set.of("minecraft_mix_light", "minecraft_sample_lightmap"), Set.of())),
                    Map.entry("matrix.glsl", new Include(Set.of("mat2_rotate_z"), Set.of())),
                    Map.entry("projection.glsl", new Include(Set.of("projection_from_position"), Set.of()))
            )),
            // 1.21.5
            Map.entry(55, Map.ofEntries(
                    Map.entry("fog.glsl", new Include(Set.of("fog_distance", "linear_fog", "linear_fog_fade"), Set.of())),
                    Map.entry("light.glsl", new Include(Set.of("minecraft_mix_light", "minecraft_sample_lightmap"), Set.of())),
                    Map.entry("matrix.glsl", new Include(Set.of("mat2_rotate_z"), Set.of())),
                    Map.entry("projection.glsl", new Include(Set.of("projection_from_position"), Set.of()))
            )),
            // 1.21.6
            Map.entry(63, Map.ofEntries(
                    Map.entry("dynamictransforms.glsl", new Include(Set.of("ColorModulator", "DynamicTransforms", "LineWidth", "ModelOffset", "ModelViewMat", "TextureMat"), Set.of())),
                    Map.entry("fog.glsl", new Include(Set.of("Fog", "FogCloudsEnd", "FogColor", "FogEnvironmentalEnd", "FogEnvironmentalStart", "FogRenderDistanceEnd", "FogRenderDistanceStart", "FogSkyEnd", "apply_fog", "fog_cylindrical_distance", "fog_spherical_distance", "linear_fog_value", "total_fog_value"), Set.of())),
                    Map.entry("globals.glsl", new Include(Set.of("GameTime", "GlintAlpha", "Globals", "MenuBlurRadius", "ScreenSize"), Set.of())),
                    Map.entry("light.glsl", new Include(Set.of("Light0_Direction", "Light1_Direction", "Lighting", "minecraft_mix_light"), Set.of())),
                    Map.entry("matrix.glsl", new Include(Set.of("mat2_rotate_z"), Set.of())),
                    Map.entry("projection.glsl", new Include(Set.of("ProjMat", "Projection", "projection_from_position"), Set.of()))
            )),
            // 1.21.8
            Map.entry(64, Map.ofEntries(
                    Map.entry("dynamictransforms.glsl", new Include(Set.of("ColorModulator", "DynamicTransforms", "LineWidth", "ModelOffset", "ModelViewMat", "TextureMat"), Set.of())),
                    Map.entry("fog.glsl", new Include(Set.of("Fog", "FogCloudsEnd", "FogColor", "FogEnvironmentalEnd", "FogEnvironmentalStart", "FogRenderDistanceEnd", "FogRenderDistanceStart", "FogSkyEnd", "apply_fog", "fog_cylindrical_distance", "fog_spherical_distance", "linear_fog_value", "total_fog_value"), Set.of())),
                    Map.entry("globals.glsl", new Include(Set.of("GameTime", "GlintAlpha", "Globals", "MenuBlurRadius", "ScreenSize"), Set.of())),
                    Map.entry("light.glsl", new Include(Set.of("Light0_Direction", "Light1_Direction", "Lighting", "minecraft_mix_light"), Set.of())),
                    Map.entry("matrix.glsl", new Include(Set.of("mat2_rotate_z"), Set.of())),
                    Map.entry("projection.glsl", new Include(Set.of("ProjMat", "Projection", "projection_from_position"), Set.of()))
            )),
            // 1.21.10
            Map.entry(69, Map.ofEntries(
                    Map.entry("dynamictransforms.glsl", new Include(Set.of("ColorModulator", "DynamicTransforms", "LineWidth", "ModelOffset", "ModelViewMat", "TextureMat"), Set.of())),
                    Map.entry("fog.glsl", new Include(Set.of("Fog", "FogCloudsEnd", "FogColor", "FogEnvironmentalEnd", "FogEnvironmentalStart", "FogRenderDistanceEnd", "FogRenderDistanceStart", "FogSkyEnd", "apply_fog", "fog_cylindrical_distance", "fog_spherical_distance", "linear_fog_value", "total_fog_value"), Set.of())),
                    Map.entry("globals.glsl", new Include(Set.of("GameTime", "GlintAlpha", "Globals", "MenuBlurRadius", "ScreenSize"), Set.of())),
                    Map.entry("light.glsl", new Include(Set.of("Light0_Direction", "Light1_Direction", "Lighting", "minecraft_compute_light", "minecraft_mix_light", "minecraft_mix_light_separate"), Set.of())),
                    Map.entry("matrix.glsl", new Include(Set.of("mat2_rotate_z"), Set.of())),
                    Map.entry("projection.glsl", new Include(Set.of("ProjMat", "Projection", "projection_from_position"), Set.of()))
            )),
            // 1.21.11
            Map.entry(75, Map.ofEntries(
                    Map.entry("animation_sprite.glsl", new Include(Set.of("MipMapLevel", "ProjectionMatrix", "SpriteAnimationInfo", "SpriteMatrix", "UPadding", "VPadding"), Set.of())),
                    Map.entry("chunksection.glsl", new Include(Set.of("ChunkPosition", "ChunkSection", "ChunkVisibility", "ModelViewMat", "TextureSize"), Set.of())),
                    Map.entry("dynamictransforms.glsl", new Include(Set.of("ColorModulator", "DynamicTransforms", "ModelOffset", "ModelViewMat", "TextureMat"), Set.of())),
                    Map.entry("fog.glsl", new Include(Set.of("Fog", "FogCloudsEnd", "FogColor", "FogEnvironmentalEnd", "FogEnvironmentalStart", "FogRenderDistanceEnd", "FogRenderDistanceStart", "FogSkyEnd", "apply_fog", "fog_cylindrical_distance", "fog_spherical_distance", "linear_fog_value", "total_fog_value"), Set.of())),
                    Map.entry("globals.glsl", new Include(Set.of("CameraBlockPos", "CameraOffset", "GameTime", "GlintAlpha", "Globals", "MenuBlurRadius", "ScreenSize", "UseRgss"), Set.of())),
                    Map.entry("light.glsl", new Include(Set.of("Light0_Direction", "Light1_Direction", "Lighting", "minecraft_compute_light", "minecraft_mix_light", "minecraft_mix_light_separate"), Set.of())),
                    Map.entry("matrix.glsl", new Include(Set.of("mat2_rotate_z"), Set.of())),
                    Map.entry("projection.glsl", new Include(Set.of("ProjMat", "Projection", "projection_from_position"), Set.of()))
            )),
            // 26.1
            Map.entry(84, Map.ofEntries(
                    Map.entry("animation_sprite.glsl", new Include(Set.of("MipMapLevel", "ProjectionMatrix", "SpriteAnimationInfo", "SpriteMatrix", "UPadding", "VPadding"), Set.of())),
                    Map.entry("chunksection.glsl", new Include(Set.of("ChunkPosition", "ChunkSection", "ChunkVisibility", "ModelViewMat", "TextureSize"), Set.of())),
                    Map.entry("dynamictransforms.glsl", new Include(Set.of("ColorModulator", "DynamicTransforms", "ModelOffset", "ModelViewMat", "TextureMat"), Set.of())),
                    Map.entry("fog.glsl", new Include(Set.of("Fog", "FogCloudsEnd", "FogColor", "FogEnvironmentalEnd", "FogEnvironmentalStart", "FogRenderDistanceEnd", "FogRenderDistanceStart", "FogSkyEnd", "apply_fog", "fog_cylindrical_distance", "fog_spherical_distance", "linear_fog_value", "total_fog_value"), Set.of())),
                    Map.entry("globals.glsl", new Include(Set.of("CameraBlockPos", "CameraOffset", "GameTime", "GlintAlpha", "Globals", "MenuBlurRadius", "ScreenSize", "UseRgss"), Set.of())),
                    Map.entry("light.glsl", new Include(Set.of("Light0_Direction", "Light1_Direction", "Lighting", "minecraft_compute_light", "minecraft_mix_light", "minecraft_mix_light_separate"), Set.of())),
                    Map.entry("matrix.glsl", new Include(Set.of("mat2_rotate_z"), Set.of())),
                    Map.entry("projection.glsl", new Include(Set.of("ProjMat", "Projection", "projection_from_position"), Set.of())),
                    Map.entry("sample_lightmap.glsl", new Include(Set.of("sample_lightmap"), Set.of()))
            )),
            // 26.2
            Map.entry(88, Map.ofEntries(
                    Map.entry("animation_sprite.glsl", new Include(Set.of("MipMapLevel", "ProjectionMatrix", "SpriteAnimationInfo", "SpriteMatrix", "UPadding", "VPadding"), Set.of())),
                    Map.entry("chunksection.glsl", new Include(Set.of("ChunkPosition", "ChunkSection", "ChunkVisibility", "ModelViewMat", "TextureSize"), Set.of())),
                    Map.entry("dynamictransforms.glsl", new Include(Set.of("ColorModulator", "DynamicTransforms", "ModelOffset", "ModelViewMat", "TextureMat"), Set.of())),
                    Map.entry("fog.glsl", new Include(Set.of("Fog", "FogCloudsEnd", "FogColor", "FogEnvironmentalEnd", "FogEnvironmentalStart", "FogRenderDistanceEnd", "FogRenderDistanceStart", "FogSkyEnd", "apply_fog", "fog_cylindrical_distance", "fog_spherical_distance", "linear_fog_value", "total_fog_value"), Set.of())),
                    Map.entry("globals.glsl", new Include(Set.of("CameraBlockPos", "CameraOffset", "GameTime", "GlintAlpha", "Globals", "MenuBlurRadius", "ScreenSize", "UseRgss"), Set.of())),
                    Map.entry("light.glsl", new Include(Set.of("Light0_Direction", "Light1_Direction", "Lighting", "minecraft_compute_light", "minecraft_mix_light", "minecraft_mix_light_separate"), Set.of())),
                    Map.entry("matrix.glsl", new Include(Set.of("mat2_rotate_z"), Set.of())),
                    Map.entry("projection.glsl", new Include(Set.of("ProjMat", "Projection", "projection_from_position"), Set.of())),
                    Map.entry("sample_lightmap.glsl", new Include(Set.of("sample_lightmap"), Set.of()))
            )),
            // 26.3
            Map.entry(97, Map.ofEntries(
                    Map.entry("animation_sprite.glsl", new Include(Set.of("MipMapLevel", "ProjectionMatrix", "SpriteAnimationInfo", "SpriteMatrix", "UPadding", "VPadding"), Set.of())),
                    Map.entry("chunksection.glsl", new Include(Set.of("ChunkPosition", "ChunkSection", "ChunkVisibility"), Set.of())),
                    Map.entry("dynamictransforms.glsl", new Include(Set.of("ColorModulator", "DynamicTransforms", "ModelOffset", "ModelViewMat", "TextureMat"), Set.of())),
                    Map.entry("fog.glsl", new Include(Set.of("Fog", "FogCloudsEnd", "FogColor", "FogEnvironmentalEnd", "FogEnvironmentalStart", "FogRenderDistanceEnd", "FogRenderDistanceStart", "FogSkyEnd", "apply_fog", "fog_cylindrical_distance", "fog_spherical_distance", "linear_fog_value", "total_fog_value"), Set.of())),
                    Map.entry("globals.glsl", new Include(Set.of("CameraBlockPos", "CameraOffset", "GameTime", "GlintAlpha", "Globals", "MenuBlurRadius", "ScreenSize", "UseRgss"), Set.of())),
                    Map.entry("light.glsl", new Include(Set.of("Light0_Direction", "Light1_Direction", "Lighting", "minecraft_compute_light", "minecraft_mix_light", "minecraft_mix_light_separate"), Set.of())),
                    Map.entry("matrix.glsl", new Include(Set.of("mat2_rotate_z"), Set.of())),
                    Map.entry("projection.glsl", new Include(Set.of("ProjMat", "Projection", "projection_from_position"), Set.of())),
                    Map.entry("sample_lightmap.glsl", new Include(Set.of("sample_lightmap"), Set.of())),
                    Map.entry("terrainglobals.glsl", new Include(Set.of("ModelViewMat", "TerrainUniform", "TextureSize"), Set.of())),
                    Map.entry("texture_sampling.glsl", new Include(Set.of("sampleNearest", "sampleRGSS"), Set.of()))
            ))
    );
}
