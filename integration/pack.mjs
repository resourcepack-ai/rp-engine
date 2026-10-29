// The content pack every integration run puts on its server: the Paper
// harness (run.mjs) and the Folia session (folia/run.mjs) alike.

/**
 * An animated Blockbench project, so the animation events have something to
 * play. Written out rather than checked in as a binary: a fixture you can read
 * is a fixture you can fix.
 */
export const WINDMILL = "{\n  \"meta\": {\n    \"format_version\": \"4.5\",\n    \"model_format\": \"free\",\n    \"box_uv\": false\n  },\n  \"name\": \"windmill\",\n  \"resolution\": {\n    \"width\": 16,\n    \"height\": 16\n  },\n  \"elements\": [\n    {\n      \"name\": \"blade\",\n      \"type\": \"cube\",\n      \"uuid\": \"10000000-0000-0000-0000-000000000001\",\n      \"from\": [\n        6,\n        0,\n        6\n      ],\n      \"to\": [\n        10,\n        12,\n        10\n      ],\n      \"origin\": [\n        8,\n        0,\n        8\n      ],\n      \"faces\": {\n        \"north\": {\n          \"uv\": [\n            0,\n            0,\n            16,\n            16\n          ],\n          \"texture\": 0\n        },\n        \"east\": {\n          \"uv\": [\n            0,\n            0,\n            16,\n            16\n          ],\n          \"texture\": 0\n        },\n        \"south\": {\n          \"uv\": [\n            0,\n            0,\n            16,\n            16\n          ],\n          \"texture\": 0\n        },\n        \"west\": {\n          \"uv\": [\n            0,\n            0,\n            16,\n            16\n          ],\n          \"texture\": 0\n        },\n        \"up\": {\n          \"uv\": [\n            0,\n            0,\n            16,\n            16\n          ],\n          \"texture\": 0\n        },\n        \"down\": {\n          \"uv\": [\n            0,\n            0,\n            16,\n            16\n          ],\n          \"texture\": 0\n        }\n      }\n    }\n  ],\n  \"outliner\": [\n    {\n      \"name\": \"b_blades\",\n      \"uuid\": \"20000000-0000-0000-0000-000000000001\",\n      \"origin\": [\n        8,\n        0,\n        8\n      ],\n      \"children\": [\n        \"10000000-0000-0000-0000-000000000001\"\n      ]\n    }\n  ],\n  \"textures\": [\n    {\n      \"name\": \"windmill\",\n      \"id\": \"0\",\n      \"source\": \"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==\"\n    }\n  ],\n  \"animations\": [\n    {\n      \"uuid\": \"30000000-0000-0000-0000-000000000001\",\n      \"name\": \"spin\",\n      \"loop\": \"loop\",\n      \"length\": 2.0,\n      \"animators\": {\n        \"20000000-0000-0000-0000-000000000001\": {\n          \"name\": \"b_blades\",\n          \"type\": \"bone\",\n          \"keyframes\": [\n            {\n              \"channel\": \"rotation\",\n              \"time\": 0.0,\n              \"data_points\": [\n                {\n                  \"x\": 0,\n                  \"y\": 0,\n                  \"z\": 0\n                }\n              ],\n              \"interpolation\": \"linear\"\n            },\n            {\n              \"channel\": \"rotation\",\n              \"time\": 2.0,\n              \"data_points\": [\n                {\n                  \"x\": 0,\n                  \"y\": 360,\n                  \"z\": 0\n                }\n              ],\n              \"interpolation\": \"linear\"\n            }\n          ]\n        }\n      }\n    },\n    {\n      \"uuid\": \"30000000-0000-0000-0000-000000000002\",\n      \"name\": \"open\",\n      \"loop\": \"hold\",\n      \"length\": 1.0,\n      \"animators\": {\n        \"20000000-0000-0000-0000-000000000001\": {\n          \"name\": \"b_blades\",\n          \"type\": \"bone\",\n          \"keyframes\": [\n            {\n              \"channel\": \"rotation\",\n              \"time\": 0.0,\n              \"data_points\": [\n                {\n                  \"x\": 0,\n                  \"y\": 0,\n                  \"z\": 0\n                }\n              ],\n              \"interpolation\": \"linear\"\n            },\n            {\n              \"channel\": \"rotation\",\n              \"time\": 1.0,\n              \"data_points\": [\n                {\n                  \"x\": 0,\n                  \"y\": 0,\n                  \"z\": 90\n                }\n              ],\n              \"interpolation\": \"linear\"\n            }\n          ]\n        }\n      }\n    },\n    {\n      \"uuid\": \"30000000-0000-0000-0000-000000000003\",\n      \"name\": \"pop\",\n      \"loop\": \"once\",\n      \"length\": 0.5,\n      \"animators\": {\n        \"20000000-0000-0000-0000-000000000001\": {\n          \"name\": \"b_blades\",\n          \"type\": \"bone\",\n          \"keyframes\": [\n            {\n              \"channel\": \"position\",\n              \"time\": 0.0,\n              \"data_points\": [\n                {\n                  \"x\": 0,\n                  \"y\": 0,\n                  \"z\": 0\n                }\n              ],\n              \"interpolation\": \"linear\"\n            },\n            {\n              \"channel\": \"position\",\n              \"time\": 0.5,\n              \"data_points\": [\n                {\n                  \"x\": 0,\n                  \"y\": 2,\n                  \"z\": 0\n                }\n              ],\n              \"interpolation\": \"linear\"\n            }\n          ]\n        }\n      }\n    }\n  ]\n}";

export const PACK = {
  "pack.yml": "name: Integration Test Pack\nauthor: harness\nversion: 1.0.0\n",
  "liquids/liquids.yml": `acid:
  base: water
  color: "#3FBF4A"
  effect: POISON
  amplifier: 1
  damage: 1.0
  tags: [dangerous]

blood:
  base: water
  color: RED
  damage: 0.5

magma:
  base: lava
  color: 0xFF6A00
  fireproof: true

plain:
  base: water
  effect: SPEED

wrong_colour:
  base: water
  color: ochre

hash_unquoted:
  base: water
  color: 3FBF4A
`,
  "items/buckets.yml": `acid_bucket:
  material: BUCKET
  name: "Acid Bucket"
  liquid: testpack:acid

magma_bucket:
  material: BUCKET
  liquid: testpack:magma

ghost_bucket:
  material: BUCKET
  liquid: testpack:not_a_liquid

broken_bucket:
  material: BUCKET
  liquid: "not an id"
`,
  "assets/models/windmill.bbmodel": WINDMILL,
  "items/models.yml": `windmill:
  material: PAPER
  name: "Test Windmill"
  model: windmill
  place:
    facing: cardinal
`,
  "blocks/blocks.yml": `ruby_ore:
  base: note_block
  model: chair
  hardness: 3.0
  tool: pickaxe
  drop: testpack:ruby
  sound: stone

inert_block:
  base: mushroom_stem
  hardness: 1.0
`,
  "entities/mobs.yml": `sentry:
  type: ZOMBIE
  name: "Sentry"
  health: 10
  tags: [test]
`,
};
