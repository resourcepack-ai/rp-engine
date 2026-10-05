package ai.resourcepack.engine.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a content pack said a custom block is.
 *
 * <h2>What a custom block actually is</h2>
 *
 * <p>Minecraft has no way to add a block, so a custom block is <strong>a real
 * vanilla block in a state nothing else uses, wearing your model</strong>. The
 * state is what identifies it: a note block has sixteen instruments,
 * twenty-five notes and a powered flag, which is eight hundred combinations
 * that a resource pack can point at eight hundred different models.
 *
 * <p>That is the same trick ItemsAdder, Oraxen and every other plugin doing
 * this uses, because it is the only one there is.
 *
 * <h2>The cost, stated plainly</h2>
 *
 * <ul>
 *   <li><strong>The pool is finite.</strong> Eight hundred blocks per server,
 *       shared with every other plugin doing the same thing. A
 *       {@link ModelInfo placed model} has no pool and no limit, which is why
 *       it is still the right answer for furniture.</li>
 *   <li><strong>The mapping has to be kept.</strong> A block in somebody's
 *       world is a note block in state 412, and if the file saying which id
 *       that was is lost, every one of them becomes a different block. It is
 *       written to {@code blocks.json}, append-only, and never reordered.</li>
 *   <li><strong>Vanilla note blocks are hijacked.</strong> A server with
 *       custom blocks has note blocks that do not play notes, because the pack
 *       has repainted them and the engine stops the game changing their
 *       state.</li>
 * </ul>
 *
 * <p>None of that is a reason not to have the feature — a server that wants
 * ores, machines and blocks you mine needs real blocks, and a display entity
 * cannot be mined. It is a reason to say so out loud.
 */
public final class BlockInfo {

    /** Which vanilla block a custom one is made of. */
    public enum Base {

        /**
         * The note block. Eight hundred states, and the industry standard.
         *
         * <p>Its instrument is normally recomputed from whatever is beneath
         * it, so the engine has to stop the game updating one of ours.
         */
        NOTE_BLOCK,

        /**
         * A mushroom stem. Sixty-four states from six face booleans, and
         * nothing in vanilla ever changes them.
         *
         * <p>Fewer states, but no behaviour to suppress at all — the right
         * choice for a block that should be as inert as possible.
         */
        MUSHROOM_STEM,

        /**
         * A tripwire, cut: string with no collision, which is what a plant, a
         * flower or a pebble wants - you walk through it, and it breaks at a
         * touch. The same base Nexo's and Oraxen's string blocks and
         * ItemsAdder's wire blocks use.
         *
         * <p>Only its <em>disarmed</em> states are used, which ordinary string
         * is never left in, so tripwire traps keep their look. And its
         * connections to the string beside it change whenever a neighbour
         * does, so on a server that lets them it holds just two plants. Paper's
         * {@code block-updates.disable-tripwire-updates} - which those plugins
         * ask for too - freezes them, and then it holds thirty-two.
         */
        TRIPWIRE
    }

    /**
     * What shape a custom block is.
     *
     * <p>A {@link #CUBE} is a note block or a mushroom stem in a spare state,
     * as above. Every other shape is <strong>a whole vanilla block type taken
     * over</strong>: a custom stair IS a stair underneath, of a kind of stair
     * almost nobody builds with, so the game itself does everything a stair
     * does - turning to face you, joining its neighbours into corners,
     * stacking into a double slab, opening and closing - and the resource
     * pack repaints that one kind of stair everywhere.
     *
     * <p>That is the only honest way to have a stair. A note block is a full
     * cube to the game whatever it is painted as, so a "stair" made of one is a
     * cube you cannot walk up; and the shape logic of a stair, a slab or a door
     * is server code no state trick reaches.
     *
     * <p>The kinds taken are the waxed copper ones, the same ones Oraxen and
     * CraftEngine packs take, because waxed copper never changes on its own
     * and is rarely built with; slabs first take the petrified oak slab, which
     * no survival player can obtain at all. Each kind is a pool of four (five
     * for slabs), and doors, trapdoors, grates and bulbs need 1.21. See
     * {@code BlockStates} for the pools and FORMAT.md for what it costs.
     */
    public enum Shape {
        /** A full cube in a spare state of a note block or a mushroom stem. */
        CUBE,
        /** A stair: faces, joins its neighbours into corners, flips upside down. */
        STAIRS,
        /** A slab: top, bottom, and two of them stacked into one block. */
        SLAB,
        /** A door, two blocks tall, that opens by hand and by redstone. */
        DOOR,
        /** A trapdoor. */
        TRAPDOOR,
        /**
         * A full block you can see through: solid, but drawn with holes, which
         * is what leaves, glass and grates want and a note block cannot be.
         */
        GRATE,
        /**
         * A full block that gives off light when switched on by redstone. The
         * level is the base block's own: 15, 12, 8 or 4 depending on which of
         * the four is handed out.
         */
        BULB;

        /**
         * The parts a block of this shape is drawn with, which the game picks
         * between by state: a stair's straight run and its two corners, a
         * door's eight halves. The first is the one an item shows.
         */
        public List<String> roles() {
            switch (this) {
                case STAIRS:
                    return List.of("straight", "inner", "outer");
                case SLAB:
                    return List.of("bottom", "top", "double");
                case DOOR:
                    return List.of("bottom_left", "bottom_left_open", "bottom_right", "bottom_right_open",
                            "top_left", "top_left_open", "top_right", "top_right_open");
                case TRAPDOOR:
                    return List.of("bottom", "top", "open");
                case BULB:
                    return List.of("off", "on");
                default:
                    return List.of("block");
            }
        }
    }

    /**
     * Something a custom cube can be other than itself: which way it faces,
     * whether it is open, how ripe it is.
     *
     * <p>Every combination of values is its own spare state, drawn its own way
     * - so a block facing four ways costs four states of the pool, and one
     * that also opens costs eight. That is the price of a property; the pool is
     * the same pool.
     */
    public static final class Property {

        /** How a property's value is decided. */
        public enum Kind {
            /** North, east, south or west: turned to face the player who placed it. */
            FACING,
            /** The four, plus up and down: turned to face where the player was looking from. */
            FACING_ALL,
            /** x, y or z: the axis of the face it was placed against, as a log is. */
            AXIS,
            /** A list of values; placed as the first, changed by clicking, growing or actions. */
            VALUES,
            /**
             * A list of values, one picked at random when it is placed: the
             * way a flower bed or a cobbled path looks hand-laid rather than
             * stamped.
             */
            RANDOM
        }

        private final String name;
        private final Kind kind;
        private final List<String> values;

        private Property(String name, Kind kind, List<String> values) {
            this.name = name;
            this.kind = kind;
            this.values = values;
        }

        /** Engine internal; a property of one of the kinds that decide their own values. */
        public static Property of(String name, Kind kind) {
            switch (kind) {
                case FACING:
                    return new Property(name, kind, List.of("north", "east", "south", "west"));
                case FACING_ALL:
                    return new Property(name, kind, List.of("north", "east", "south", "west", "up", "down"));
                case AXIS:
                    return new Property(name, kind, List.of("y", "x", "z"));
                default:
                    throw new IllegalArgumentException("A list of values needs values(): " + kind);
            }
        }

        /** Engine internal; a property whose value is picked at random when placed. */
        public static Property random(String name, List<String> values) {
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("A property needs at least one value: " + name);
            }
            return new Property(name, Kind.RANDOM, List.copyOf(values));
        }

        /** Engine internal; a property with values of its own, the first being the one placed. */
        public static Property values(String name, List<String> values) {
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("A property needs at least one value: " + name);
            }
            return new Property(name, Kind.VALUES, List.copyOf(values));
        }

        public String name() {
            return name;
        }

        public Kind kind() {
            return kind;
        }

        /** Its values, the placed one first. */
        public List<String> values() {
            return values;
        }

        @Override
        public String toString() {
            return name + values;
        }
    }

    /**
     * A block that grows: a crop, a sapling, a melon stem.
     *
     * <p>Growing is one property stepping through its values, last value and
     * done. A note block, a mushroom stem and a tripwire do not tick on their
     * own, so the engine does it: every placed block that can still grow is
     * remembered in its chunk, and checked about once a second while that
     * chunk is loaded.
     */
    public static final class Growth {

        private final String property;
        private final int seconds;
        private final int light;
        private final boolean boneMeal;

        private Growth(String property, int seconds, int light, boolean boneMeal) {
            this.property = property;
            this.seconds = seconds;
            this.light = light;
            this.boneMeal = boneMeal;
        }

        /** Engine internal. */
        public static Growth of(String property, int seconds, int light, boolean boneMeal) {
            return new Growth(property, Math.max(1, seconds), Math.max(0, Math.min(15, light)), boneMeal);
        }

        /** The property that steps. */
        public String property() {
            return property;
        }

        /** How long a step takes on average, in seconds. */
        public int seconds() {
            return seconds;
        }

        /** The least light it grows in. */
        public int light() {
            return light;
        }

        /** Whether bone meal moves it a step. */
        public boolean boneMeal() {
            return boneMeal;
        }
    }

    /**
     * How one state is drawn: a model, turned the way a blockstate file turns
     * one.
     *
     * <p>{@link #state()} is the state it is for, written as a blockstate file
     * writes it ({@code facing=east,open=true}); it may name only some of the
     * properties, and then it is for every state that agrees with what it does
     * name. The most specific match wins, and an empty one matches everything.
     */
    public static final class Appearance {

        private final String state;
        private final String model;
        private final int x;
        private final int y;
        private final boolean uvlock;

        private Appearance(String state, String model, int x, int y, boolean uvlock) {
            this.state = state;
            this.model = model;
            this.x = x;
            this.y = y;
            this.uvlock = uvlock;
        }

        /** Engine internal. */
        public static Appearance of(String state, String model, int x, int y, boolean uvlock) {
            return new Appearance(state == null ? "" : state, model == null ? "" : model,
                    Math.floorMod(x, 360), Math.floorMod(y, 360), uvlock);
        }

        /** The state it draws, or empty for every state. */
        public String state() {
            return state;
        }

        /** The model, written as a block's {@link BlockInfo#model()} is; empty for the block's own. */
        public String model() {
            return model;
        }

        /** Degrees the model is turned about x, a multiple of 90. */
        public int x() {
            return x;
        }

        /** Degrees the model is turned about y, a multiple of 90. */
        public int y() {
            return y;
        }

        /** Whether the textures stay put while the model turns. */
        public boolean uvlock() {
            return uvlock;
        }

        /** How many of its pairs are in {@code other}, or -1 if any is not. */
        public int matches(String other) {
            if (state.isEmpty()) {
                return 0;
            }
            java.util.Set<String> pairs = new java.util.HashSet<>(List.of(other.split(",")));
            int count = 0;
            for (String pair : state.split(",")) {
                if (!pairs.contains(pair)) {
                    return -1;
                }
                count++;
            }
            return count;
        }

        @Override
        public String toString() {
            return (state.isEmpty() ? "*" : state) + " -> " + model
                    + (x != 0 ? " x" + x : "") + (y != 0 ? " y" + y : "");
        }
    }

    private final ContentId id;
    private final Base base;
    private final String model;
    private final float hardness;
    private final String tool;
    private final ContentId drop;
    private final String sound;
    private final String name;
    private final List<String> lore;
    private final Map<ItemAction.Trigger, List<ItemAction>> actions;

    // Shapes and states. Not constructor arguments, for the same reason as
    // ModelInfo's later fields: the factory above is public, and a block with
    // none of this is exactly the block it always was.
    private Shape shape = Shape.CUBE;
    private List<Property> properties = List.of();
    private List<Appearance> appearances = List.of();
    private String cycle;
    private String itemTexture = "";
    private Map<String, String> roleModels = Map.of();
    private String takes;
    private Growth growth;
    private Behaviour behaviour = Behaviour.NONE;
    private StorageSpec storage;

    /**
     * The small things a block does that vanilla blocks do: fall like sand,
     * shrug off explosions, lose its bark to an axe, switch to another block
     * when clicked.
     *
     * @param strip      what an axe turns it into, or null
     * @param stripDrop  what stripping it also drops, or null
     * @param clickInto  what a right-click turns it into, or null
     * @param falls      whether it falls when nothing holds it up
     * @param blastProof whether explosions leave it standing
     */
    public record Behaviour(ContentId strip, ContentId stripDrop, ContentId clickInto, boolean falls,
                            boolean blastProof) {
        public static final Behaviour NONE = new Behaviour(null, null, null, false, false);

        public Optional<ContentId> stripInto() {
            return Optional.ofNullable(strip);
        }

        public Optional<ContentId> clicksInto() {
            return Optional.ofNullable(clickInto);
        }
    }

    private BlockInfo(ContentId id, Base base, String model, float hardness,
                      String tool, ContentId drop, String sound, String name, List<String> lore,
                      Map<ItemAction.Trigger, List<ItemAction>> actions) {
        this.id = id;
        this.base = base;
        this.model = model;
        this.hardness = hardness;
        this.tool = tool;
        this.drop = drop;
        this.sound = sound;
        this.name = name;
        this.lore = lore;
        this.actions = actions;
    }

    /** Engine internal; built by the block loader. */
    public static BlockInfo of(ContentId id, Base base, String model, float hardness,
                               String tool, ContentId drop, String sound) {
        return new BlockInfo(
                Objects.requireNonNull(id, "id"),
                base == null ? Base.NOTE_BLOCK : base,
                model == null ? "" : model,
                hardness,
                tool == null ? "" : tool,
                drop,
                sound == null ? "" : sound,
                null,
                List.of(),
                Map.of());
    }

    /** Engine internal; the same block with the name and lore its item is given. */
    public BlockInfo withItemText(String name, List<String> lore) {
        return carry(new BlockInfo(id, base, model, hardness, tool, drop, sound, name,
                lore == null ? List.of() : List.copyOf(lore), actions));
    }

    /** Engine internal; the same block with the actions its item carries. */
    public BlockInfo withActions(Map<ItemAction.Trigger, List<ItemAction>> actions) {
        return carry(new BlockInfo(id, base, model, hardness, tool, drop, sound, name, lore,
                actions == null ? Map.of() : Map.copyOf(actions)));
    }

    /** This block's shape and states, onto {@code to}. */
    private BlockInfo carry(BlockInfo to) {
        to.shape = shape;
        to.properties = properties;
        to.appearances = appearances;
        to.cycle = cycle;
        to.itemTexture = itemTexture;
        to.roleModels = roleModels;
        to.takes = takes;
        to.growth = growth;
        to.behaviour = behaviour;
        to.storage = storage;
        return to;
    }

    private BlockInfo copy() {
        return carry(new BlockInfo(id, base, model, hardness, tool, drop, sound, name, lore, actions));
    }

    /**
     * Engine internal; the same block as another shape, drawn with a model
     * for each of that shape's {@link Shape#roles() roles}.
     *
     * @param takes the vanilla block it asked for by name, or null to be handed one
     */
    public BlockInfo withShape(Shape shape, Map<String, String> roleModels, String takes) {
        BlockInfo changed = copy();
        changed.shape = shape == null ? Shape.CUBE : shape;
        changed.roleModels = roleModels == null ? Map.of() : Map.copyOf(roleModels);
        changed.takes = takes;
        return changed;
    }

    /**
     * Engine internal; the same block with properties, how each state is
     * drawn, and which property a click turns over (or null for none).
     *
     * <p>A shape other than a cube has the base block's own properties and
     * takes only the appearances: they are keyed by that block's states.
     */
    public BlockInfo withStates(List<Property> properties, List<Appearance> appearances, String cycle) {
        BlockInfo changed = copy();
        changed.properties = properties == null ? List.of() : List.copyOf(properties);
        changed.appearances = appearances == null ? List.of() : List.copyOf(appearances);
        changed.cycle = cycle;
        return changed;
    }

    /** Engine internal; the same block, behaving. */
    public BlockInfo withBehaviour(Behaviour behaviour) {
        BlockInfo changed = copy();
        changed.behaviour = behaviour == null ? Behaviour.NONE : behaviour;
        return changed;
    }

    /** Engine internal; the same block, holding things. */
    public BlockInfo withStorage(StorageSpec storage) {
        BlockInfo changed = copy();
        changed.storage = storage;
        return changed;
    }

    /**
     * The container a right-click opens, if it is one: the same
     * {@code storage:} a placed model takes, kept in the chunk's own
     * persistent data under the block's position.
     */
    public Optional<StorageSpec> storage() {
        return Optional.ofNullable(storage);
    }

    /** What it does beyond being placed, clicked and mined. */
    public Behaviour behaviour() {
        return behaviour;
    }

    /** Engine internal; the same block, growing. */
    public BlockInfo withGrowth(Growth growth) {
        BlockInfo changed = copy();
        changed.growth = growth;
        return changed;
    }

    /** How it grows, if it does. */
    public Optional<Growth> growth() {
        return Optional.ofNullable(growth);
    }

    /**
     * Whether a block in {@code state} has a step left to grow: its growing
     * property is not yet at its last value.
     */
    public boolean canGrow(String state) {
        if (growth == null) {
            return false;
        }
        for (Property property : properties) {
            if (property.name().equals(growth.property())) {
                String value = valueIn(state, property.name()).orElse(property.values().get(0));
                return property.values().indexOf(value) < property.values().size() - 1;
            }
        }
        return false;
    }

    /** {@code state} one growth step on, or itself when it is done. */
    public String grown(String state) {
        return canGrow(state) ? next(state, growth.property()) : state;
    }

    /** Engine internal; the same block, its item drawn as a flat picture rather than as the block. */
    public BlockInfo withItemTexture(String texture) {
        BlockInfo changed = copy();
        changed.itemTexture = texture == null ? "" : texture;
        return changed;
    }

    /** Its shape. */
    public Shape shape() {
        return shape;
    }

    /** Its properties, in the order its states are written. Empty for a block with one state. */
    public List<Property> properties() {
        return properties;
    }

    /** How its states are drawn; empty when every state is its {@link #model()}, unturned. */
    public List<Appearance> appearances() {
        return appearances;
    }

    /** The property a right-click turns to its next value, if any. */
    public Optional<String> cycle() {
        return Optional.ofNullable(cycle);
    }

    /** The flat picture its item shows instead of the block, if any. */
    public Optional<String> itemTexture() {
        return itemTexture.isEmpty() ? Optional.empty() : Optional.of(itemTexture);
    }

    /** For a shape other than a cube, the model of each part it is drawn with, by role. */
    public Map<String, String> roleModels() {
        return roleModels;
    }

    /** For a shape other than a cube, the vanilla block it asked for by name, if it did. */
    public Optional<String> takes() {
        return Optional.ofNullable(takes);
    }

    /**
     * Every state it can be in, as a blockstate file writes one, the placed
     * one first. One empty state for a block with no properties.
     */
    public List<String> states() {
        List<String> states = new java.util.ArrayList<>();
        states.add("");
        if (shape != Shape.CUBE) {
            return List.copyOf(states);
        }
        for (Property property : properties) {
            List<String> next = new java.util.ArrayList<>();
            for (String partial : states) {
                for (String value : property.values()) {
                    next.add((partial.isEmpty() ? "" : partial + ",") + property.name() + "=" + value);
                }
            }
            states = next;
        }
        return List.copyOf(states);
    }

    /** The state it is placed in before placement decides anything: every first value. */
    public String defaultState() {
        return states().get(0);
    }

    /**
     * How {@code state} is drawn: the most specific appearance that matches
     * it, the later of two equally specific ones (so a model named for a
     * direction beats the turn {@code rotate:} gives it), or the block's own
     * model unturned.
     */
    public Appearance appearanceOf(String state) {
        Appearance best = null;
        int bestScore = -1;
        for (Appearance appearance : appearances) {
            int score = appearance.matches(state);
            if (score >= 0 && score >= bestScore) {
                best = appearance;
                bestScore = score;
            }
        }
        if (best == null) {
            return Appearance.of(state, model, 0, 0, false);
        }
        return best.model().isEmpty()
                ? Appearance.of(best.state(), model, best.x(), best.y(), best.uvlock())
                : best;
    }

    /**
     * {@code state} with {@code property} set to {@code value}, keeping the
     * rest. A value the property does not have changes nothing.
     */
    public String with(String state, String property, String value) {
        StringBuilder out = new StringBuilder();
        for (Property known : properties) {
            String current = valueIn(state, known.name()).orElse(known.values().get(0));
            String chosen = known.name().equals(property) && known.values().contains(value) ? value : current;
            out.append(out.length() == 0 ? "" : ",").append(known.name()).append('=').append(chosen);
        }
        return out.toString();
    }

    /** {@code state} with {@code property} turned to its next value, wrapping round. */
    public String next(String state, String property) {
        for (Property known : properties) {
            if (known.name().equals(property)) {
                String current = valueIn(state, property).orElse(known.values().get(0));
                int at = known.values().indexOf(current);
                return with(state, property, known.values().get((at + 1) % known.values().size()));
            }
        }
        return state;
    }

    /** The value {@code property} has in {@code state}. */
    public static Optional<String> valueIn(String state, String property) {
        if (state == null || state.isEmpty()) {
            return Optional.empty();
        }
        for (String pair : state.split(",")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(property)) {
                return Optional.of(pair.substring(equals + 1));
            }
        }
        return Optional.empty();
    }

    /**
     * What it does, by trigger: the actions of the item that places it, which
     * include the block itself being put down, right-clicked and mined.
     */
    public Map<ItemAction.Trigger, List<ItemAction>> actions() {
        return actions;
    }

    /**
     * The display name of the item that places it, or empty for the game's
     * own name for the base block.
     */
    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    /** The lore of the item that places it. */
    public List<String> lore() {
        return lore;
    }

    /** Its id, which is also the id of the item that places it. */
    public ContentId id() {
        return id;
    }

    /** Which vanilla block it is made of. */
    public Base base() {
        return base;
    }

    /** The model under {@code assets/models/}, without the extension. */
    public String model() {
        return model;
    }

    /**
     * How long it takes to break, in the same units as vanilla hardness —
     * stone is 1.5, dirt 0.5.
     *
     * <p>Zero means instant, which is what a plant or a decoration wants.
     */
    public float hardness() {
        return hardness;
    }

    /**
     * What has to be held to get the drop: {@code pickaxe}, {@code axe},
     * {@code shovel}, {@code hoe}, or empty for anything.
     */
    public Optional<String> tool() {
        return tool.isEmpty() ? Optional.empty() : Optional.of(tool);
    }

    /** What breaking it gives back, or empty for the block itself. */
    public Optional<ContentId> drop() {
        return Optional.ofNullable(drop);
    }

    /**
     * A sound played over the base block's own when this is placed or broken.
     *
     * <p>Deliberately <em>over</em> rather than instead of. A block's sound
     * group belongs to its type, not its state, so a custom block is a note
     * block and thuds like wood however it is painted — the game plays that
     * sound on the client and there is nothing server-side to suppress. What
     * can be done is play something louder on top, which is what a stone
     * "clack" over a wooden thud actually amounts to.
     *
     * <p>A sound id of yours, or a vanilla key like
     * {@code minecraft:block.stone.place}.
     */
    public Optional<String> sound() {
        return sound.isEmpty() ? Optional.empty() : Optional.of(sound);
    }

    @Override
    public String toString() {
        return id + " (" + (shape == Shape.CUBE ? base : shape) + ")";
    }
}
