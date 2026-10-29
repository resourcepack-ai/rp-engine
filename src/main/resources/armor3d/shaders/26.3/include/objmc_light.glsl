//objmc
//https://github.com/Godlander/objmc

// 26.3's half of the lighting: the face colour only. The overlay, the
// lightmap, glint and fog follow in entity.fsh's calculateFinalColor, in
// vanilla's order, so they are not applied here as the 26.1 port does.

//default lighting
if (isCustom == 0) {
    color *= faceVertexColor * ColorModulator;
}
//custom lighting
else if (noshadow == 0) {
    //normal from position derivatives
    vec3 normal = normalize(cross(dFdx(Pos), dFdy(Pos)));

    //entity lighting
    //flip normal z for gui (zx flip in vertex shader)
    if (isGUI == 1) normal.z *= -1;
#if defined(PER_FACE_LIGHTING) || !defined(NO_CARDINAL_LIGHTING)
    color *= minecraft_mix_light(Light0_Direction, Light1_Direction, normal, overlayColor);
#else
    // NO_CARDINAL_LIGHTING pipelines have no Lighting UBO: skip the
    // directional relight, keep the overlay tint.
    color *= overlayColor;
#endif
}
