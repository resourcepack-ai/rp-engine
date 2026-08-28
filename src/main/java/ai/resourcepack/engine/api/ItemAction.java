package ai.resourcepack.engine.api;

import java.util.Locale;
import java.util.Optional;

/**
 * One thing an item does when it is used.
 *
 * <p>The engine has always said what HAPPENED — {@link ai.resourcepack.engine.api.event.ItemUseEvent}
 * carries the id, the stack and the click — and left what to do about it to a
 * plugin, on the argument that a wand that casts is a decision about a
 * particular server. That argument is still true and it is still the reason
 * this list is short. What it missed is that most servers running a content
 * plugin have nobody to write that plugin, so "you get an event" means "you
 * get nothing" for the majority of the people this is sold to.
 *
 * <p>So: a small, closed set of verbs that cover what an item is usually FOR,
 * and an event for everything else. Nothing here is scripting — there is no
 * branching, no state, no expression — because the moment this grows an
 * {@code if} it has become a language, and a bad one.
 */
public final class ItemAction {

    /** What an item can be doing when its actions run. */
    public enum Trigger {

        /** Right-clicked, on a block or in the air. */
        RIGHT_CLICK,

        /** Left-clicked, on a block or in the air. */
        LEFT_CLICK,

        /** Used to hit an entity. */
        ATTACK,

        /** Dropped. */
        DROP,

        /** Eaten or drunk, for an item whose material is food or a potion. */
        CONSUME,

        /** Used to break a block. */
        BLOCK_BREAK,

        /** Shot, for an item whose material is a bow or a crossbow. */
        SHOOT,

        /** Run out of durability and broken. */
        BREAK,

        /** Picked up off the ground. */
        PICKUP;

        /** The name an author writes, or empty if it is not one of these. */
        public static Optional<Trigger> parse(String written) {
            if (written == null) {
                return Optional.empty();
            }
            String name = written.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            for (Trigger trigger : values()) {
                if (trigger.name().equals(name)) {
                    return Optional.of(trigger);
                }
            }
            return Optional.empty();
        }

        /** How it is written in a content folder. */
        public String written() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The verbs. One key each, and the value is the whole argument. */
    public enum Kind {

        /** Sends the user a line of chat. */
        MESSAGE,

        /** Sends everybody a line of chat. */
        BROADCAST,

        /** Shows the user a line above their hotbar. */
        ACTIONBAR,

        /** Runs a command as the console. */
        CONSOLE,

        /** Runs a command as the user, with the user's own permissions. */
        RUN,

        /** Plays one of this server's sounds, or a vanilla one. */
        SOUND,

        /** Gives the user a potion effect: {@code TYPE seconds [amplifier]}. */
        EFFECT,

        /** Takes this many off the stack. */
        TAKE,

        /** Gives the user an item: {@code namespace:id [amount]}. */
        GIVE,

        /** Cancels the vanilla use of the stack. */
        CANCEL,

        /**
         * Stops here unless this many seconds have passed since last time.
         *
         * <p>A step rather than a property of the trigger, so an author can
         * put a message before it and have the refusal say something.
         */
        COOLDOWN,

        /** Stops here unless the user has this permission. */
        PERMISSION;

        /** The name an author writes, or empty if it is not one of these. */
        public static Optional<Kind> parse(String written) {
            if (written == null) {
                return Optional.empty();
            }
            String name = written.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            for (Kind kind : values()) {
                if (kind.name().equals(name)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }

        /** How it is written in a content folder. */
        public String written() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Kind kind;
    private final String argument;

    private ItemAction(Kind kind, String argument) {
        this.kind = kind;
        this.argument = argument;
    }

    /** One step. The argument is the raw text; the runner reads it. */
    public static ItemAction of(Kind kind, String argument) {
        return new ItemAction(kind, argument == null ? "" : argument.trim());
    }

    public Kind kind() {
        return kind;
    }

    /** The whole value the author wrote, untouched apart from trimming. */
    public String argument() {
        return argument;
    }

    /** The argument as a number, for the steps that take one. */
    public Optional<Double> number() {
        try {
            return Optional.of(Double.parseDouble(argument));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The argument split on whitespace, for the steps that take several. */
    public String[] words() {
        return argument.isEmpty() ? new String[0] : argument.split("\\s+");
    }

    @Override
    public String toString() {
        return kind.written() + (argument.isEmpty() ? "" : ": " + argument);
    }
}
