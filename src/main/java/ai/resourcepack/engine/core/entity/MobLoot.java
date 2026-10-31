package ai.resourcepack.engine.core.entity;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;

/**
 * A mob's loot as MythicMobs writes it, and drop tables.
 *
 * <p>A drop line is one of:
 * <pre>
 * - minecraft:leather 1to3 0.5      # item, amount (a range), chance
 * - exp 10to20                      # experience
 * - droptable bosses:rare_loot      # another table's roll (also: table, dt)
 * - command{c="give &lt;trigger.name&gt; diamond"} 1 0.1   # a console command
 * - nothing 1 5                     # in a weighted table: the chance of nothing
 * - {item: zoo:horn, min: 1, max: 2, chance: 0.25}   # RP Engine's own map form
 * </pre>
 *
 * <p>A table in {@code drop-tables/} is MythicMobs' DropTable shape:
 * {@code TotalItems: 2} (exactly that many picks, each by WEIGHT, the third
 * number of a line), or {@code MinItems}/{@code MaxItems} (a count in that
 * range, by weight), or neither (each line rolls its own chance, as a mob's
 * {@code drops:} do), plus {@code Conditions:} that must pass on the mob as
 * it dies, with its killer as the trigger.
 *
 * <p>Free of Bukkit: rolling is arithmetic over a {@link Random} the caller
 * hands in, and conditions are a predicate, so {@code MobLootTest} pins it.
 */
public final class MobLoot {

    /** How deep a table may name tables before the roll stops. */
    static final int MAX_DEPTH = 8;
    /** The most picks one weighted table makes, whatever TotalItems says. */
    static final int MAX_PICKS = 64;

    private MobLoot() {
    }

    /** What a line is. */
    public enum Kind { ITEM, EXP, TABLE, COMMAND, NOTHING }

    /** One drop line, parsed. {@code third} is a chance on a mob and in an unweighted table, a weight in a weighted one. */
    public static final class Line {
        public final Kind kind;
        /** The item, the table's name or the command. */
        public final String what;
        public final int min;
        public final int max;
        public final double third;

        Line(Kind kind, String what, int min, int max, double third) {
            this.kind = kind;
            this.what = what;
            this.min = Math.max(0, Math.min(min, 1_000_000));
            this.max = Math.max(this.min, Math.min(max, 1_000_000));
            this.third = Double.isFinite(third) ? Math.max(0, third) : 1;
        }

        @Override
        public String toString() {
            return kind + " " + what + " " + min + "-" + max + " " + third;
        }
    }

    /** A drop table. {@code total} is -1 when absent, and so are {@code minItems}/{@code maxItems}. */
    public static final class Table {
        public final ContentId id;
        final int total;
        final int minItems;
        final int maxItems;
        final List<String> conditions;
        final List<Line> lines;

        Table(ContentId id, int total, int minItems, int maxItems, List<String> conditions, List<Line> lines) {
            this.id = id;
            this.total = total;
            this.minItems = minItems;
            this.maxItems = maxItems;
            this.conditions = List.copyOf(conditions);
            this.lines = List.copyOf(lines);
        }

        boolean weighted() {
            return total >= 0 || minItems >= 0 || maxItems >= 0;
        }
    }

    /** What a roll came to: stacks to drop, experience, commands to run. */
    public static final class Loot {
        public final List<Map.Entry<String, Integer>> items = new ArrayList<>();
        public int xp;
        public final List<String> commands = new ArrayList<>();
    }

    // --- parsing --------------------------------------------------------

    /** One entry of a {@code drops:} list (a string or a map), or empty with a reason in {@code problems}. */
    public static Optional<Line> line(Object entry, List<String> problems) {
        if (entry == null || entry instanceof List) {
            problems.add("A drop is empty or a list, so it drops nothing.");
            return Optional.empty();
        }
        if (entry instanceof Map) {
            return mapLine(DefinitionNode.of((Map<?, ?>) entry), problems);
        }
        return stringLine(entry.toString().trim(), problems);
    }

    private static Optional<Line> mapLine(DefinitionNode drop, List<String> problems) {
        double third = drop.decimal("chance").orElse(drop.decimal("weight").orElse(1.0));
        Optional<String> table = drop.string("table").or(() -> drop.string("droptable"));
        if (table.isPresent()) {
            int rolls = drop.integer("amount").orElse(1);
            return Optional.of(new Line(Kind.TABLE, table.get().trim(), rolls, rolls, third));
        }
        Optional<String> command = drop.string("command");
        if (command.isPresent()) {
            return Optional.of(new Line(Kind.COMMAND, command.get().trim(), 1, 1, third));
        }
        if (drop.has("exp") || drop.has("xp")) {
            int[] range = range(drop.string(drop.has("exp") ? "exp" : "xp").orElse("0"));
            if (range == null) {
                problems.add("A drop's exp is not a number or a range like 5to10.");
                return Optional.empty();
            }
            return Optional.of(new Line(Kind.EXP, "exp", range[0], range[1], third));
        }
        Optional<String> item = drop.string("item");
        if (item.isEmpty() || item.get().isBlank()) {
            problems.add("A drop has no item:, so it drops nothing.");
            return Optional.empty();
        }
        int min = drop.integer("min").orElse(1);
        int max = drop.integer("max").orElse(min);
        Optional<String> amount = drop.string("amount");
        if (amount.isPresent() && !drop.has("min")) {
            int[] range = range(amount.get());
            if (range != null) {
                min = range[0];
                max = drop.integer("max").orElse(range[1]);
            }
        }
        return Optional.of(new Line(Kind.ITEM, item.get().trim(), min, max, third));
    }

    private static Optional<Line> stringLine(String text, List<String> problems) {
        if (text.isEmpty()) {
            problems.add("A drop is empty.");
            return Optional.empty();
        }
        String head;
        String rest;
        int brace = text.indexOf('{');
        int space = text.indexOf(' ');
        if (brace > 0 && (space < 0 || brace < space)) {
            int close = closing(text, brace);
            if (close < 0) {
                problems.add("A drop's { has no }: " + text);
                return Optional.empty();
            }
            head = text.substring(0, close + 1);
            rest = text.substring(close + 1).trim();
        } else {
            head = space < 0 ? text : text.substring(0, space);
            rest = space < 0 ? "" : text.substring(space + 1).trim();
        }
        String[] more = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        String name = head.toLowerCase(Locale.ROOT);
        String bare = name.contains("{") ? name.substring(0, name.indexOf('{')) : name;
        switch (bare) {
            case "nothing":
            case "none": {
                int[] amount = more.length > 0 ? range(more[0]) : new int[]{1, 1};
                return Optional.of(new Line(Kind.NOTHING, "nothing", amount == null ? 1 : amount[0],
                        amount == null ? 1 : amount[1], third(more, 1, problems, text)));
            }
            case "exp":
            case "experience":
            case "xp": {
                int[] amount = more.length > 0 ? range(more[0]) : null;
                if (amount == null) {
                    problems.add("exp needs an amount, like exp 10to20: " + text);
                    return Optional.empty();
                }
                return Optional.of(new Line(Kind.EXP, "exp", amount[0], amount[1], third(more, 1, problems, text)));
            }
            case "droptable":
            case "table":
            case "dt": {
                if (more.length == 0) {
                    problems.add("droptable needs a table's name: " + text);
                    return Optional.empty();
                }
                int[] rolls = more.length > 1 ? range(more[1]) : new int[]{1, 1};
                return Optional.of(new Line(Kind.TABLE, more[0], rolls == null ? 1 : rolls[0],
                        rolls == null ? 1 : rolls[1], third(more, 2, problems, text)));
            }
            case "command":
            case "cmd":
            case "consolecommand": {
                String command = argument(head, "c", "cmd", "command");
                if (command == null || command.isBlank()) {
                    problems.add("A command drop needs c=\"...\": " + text);
                    return Optional.empty();
                }
                int[] times = more.length > 0 ? range(more[0]) : new int[]{1, 1};
                return Optional.of(new Line(Kind.COMMAND, command, times == null ? 1 : times[0],
                        times == null ? 1 : times[1], third(more, 1, problems, text)));
            }
            default: {
                int[] amount = more.length > 0 ? range(more[0]) : new int[]{1, 1};
                if (amount == null) {
                    problems.add("The amount in \"" + text + "\" is not a number or a range like 1to3.");
                    return Optional.empty();
                }
                return Optional.of(new Line(Kind.ITEM, head, amount[0], amount[1], third(more, 1, problems, text)));
            }
        }
    }

    private static double third(String[] more, int at, List<String> problems, String text) {
        if (more.length <= at) {
            return 1;
        }
        try {
            return Double.parseDouble(more[at]);
        } catch (NumberFormatException e) {
            problems.add("The chance in \"" + text + "\" is not a number; it always drops.");
            return 1;
        }
    }

    private static int closing(String text, int open) {
        boolean quoted = false;
        for (int i = open + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == '}' && !quoted) {
                return i;
            }
        }
        return -1;
    }

    /** {@code c="say hi"} out of {@code command{c="say hi";x=1}}. */
    private static String argument(String head, String... keys) {
        int open = head.indexOf('{');
        String body = head.substring(open + 1, head.length() - 1);
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : body.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (c == ';' && !quoted) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            for (String wanted : keys) {
                if (key.equals(wanted)) {
                    return part.substring(eq + 1).trim();
                }
            }
        }
        return null;
    }

    /** {@code 3}, {@code 1to3} or {@code 1-3}; null for anything else. */
    static int[] range(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        try {
            int to = text.indexOf("to");
            int dash = text.indexOf('-', 1);
            if (to > 0 || dash > 0) {
                int cut = to > 0 ? to : dash;
                int a = (int) Math.round(Double.parseDouble(text.substring(0, cut).trim()));
                int b = (int) Math.round(Double.parseDouble(text.substring(cut + (to > 0 ? 2 : 1)).trim()));
                return new int[]{Math.min(a, b), Math.max(a, b)};
            }
            int value = (int) Math.round(Double.parseDouble(text));
            return new int[]{value, value};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Every table in {@code drop-tables/}, and what was wrong with them. */
    public static Tables tables(LoadReport loaded) {
        Map<ContentId, Table> tables = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded != null) {
            for (ContentDefinition definition : loaded.definitions(ContentKind.DROP_TABLE)) {
                table(definition.id(), definition.body().values(), definition.origin(), diagnostics)
                        .ifPresent(table -> tables.put(table.id, table));
            }
        }
        return new Tables(tables, diagnostics);
    }

    /** One table from MythicMobs' shape; keys are case-insensitive. */
    static Optional<Table> table(ContentId id, Map<String, Object> body, String origin, List<Diagnostic> diagnostics) {
        Map<String, Object> keys = new HashMap<>();
        body.forEach((key, value) -> keys.put(key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", ""), value));
        String where = id.path();
        Object drops = keys.get("drops");
        if (!(drops instanceof List)) {
            diagnostics.add(Diagnostic.warning(origin, where, "A drop table with no Drops: list drops nothing."));
            return Optional.empty();
        }
        List<Line> lines = new ArrayList<>();
        List<?> list = (List<?>) drops;
        for (int i = 0; i < list.size(); i++) {
            List<String> problems = new ArrayList<>();
            Optional<Line> line = line(list.get(i), problems);
            for (String problem : problems) {
                diagnostics.add(Diagnostic.warning(origin, where + ".Drops[" + (i + 1) + "]", problem));
            }
            line.ifPresent(lines::add);
        }
        int total = count(keys.get("totalitems"), origin, where, "TotalItems", diagnostics);
        int min = count(keys.get("minitems"), origin, where, "MinItems", diagnostics);
        int max = count(keys.get("maxitems"), origin, where, "MaxItems", diagnostics);
        if (min >= 0 && max < 0) {
            max = min;
        }
        if (max >= 0 && min < 0) {
            min = 0;
        }
        List<String> conditions = new ArrayList<>();
        Object written = keys.get("conditions");
        if (written instanceof List) {
            for (Object condition : (List<?>) written) {
                if (condition != null) {
                    conditions.add(condition.toString());
                }
            }
        }
        return Optional.of(new Table(id, total, Math.min(min, max), Math.max(min, max), conditions, lines));
    }

    private static int count(Object raw, String origin, String where, String key, List<Diagnostic> diagnostics) {
        if (raw == null) {
            return -1;
        }
        try {
            return Math.max(0, Math.min(MAX_PICKS, (int) Math.round(Double.parseDouble(raw.toString().trim()))));
        } catch (NumberFormatException e) {
            diagnostics.add(Diagnostic.warning(origin, where, key + ": " + raw + " is not a number."));
            return -1;
        }
    }

    // --- rolling --------------------------------------------------------

    /**
     * Rolls a mob's {@code drops:}: each line its own chance.
     *
     * @param tables the loaded tables
     * @param from the mob, whose namespace a bare table name is looked up in first
     * @param passes whether a table's condition passes, given as written
     */
    public static Loot roll(List<Line> lines, Map<ContentId, Table> tables, ContentId from,
                            Predicate<String> passes, Random random) {
        Loot loot = new Loot();
        rollEach(lines, tables, from, passes, random, loot, 0);
        return loot;
    }

    private static void rollEach(List<Line> lines, Map<ContentId, Table> tables, ContentId from,
                                 Predicate<String> passes, Random random, Loot loot, int depth) {
        for (Line line : lines) {
            if (line.third < 1 && random.nextDouble() >= line.third) {
                continue;
            }
            apply(line, tables, from, passes, random, loot, depth);
        }
    }

    private static void apply(Line line, Map<ContentId, Table> tables, ContentId from,
                              Predicate<String> passes, Random random, Loot loot, int depth) {
        int amount = line.min + (line.max > line.min ? random.nextInt(line.max - line.min + 1) : 0);
        switch (line.kind) {
            case ITEM:
                if (amount > 0) {
                    loot.items.add(Map.entry(line.what, amount));
                }
                break;
            case EXP:
                loot.xp += amount;
                break;
            case COMMAND:
                for (int i = 0; i < Math.min(amount, 16); i++) {
                    loot.commands.add(line.what);
                }
                break;
            case TABLE:
                if (depth >= MAX_DEPTH) {
                    break;
                }
                Table table = find(line.what, from, tables);
                if (table != null) {
                    for (int i = 0; i < Math.min(Math.max(1, amount), 16); i++) {
                        rollTable(table, tables, from, passes, random, loot, depth + 1);
                    }
                }
                break;
            default:
                break;
        }
    }

    /** One table's roll, into {@code loot}; nothing when its conditions fail. */
    static void rollTable(Table table, Map<ContentId, Table> tables, ContentId from, Predicate<String> passes,
                          Random random, Loot loot, int depth) {
        for (String condition : table.conditions) {
            if (!passes.test(condition)) {
                return;
            }
        }
        if (!table.weighted()) {
            rollEach(table.lines, tables, from, passes, random, loot, depth);
            return;
        }
        int picks = table.total >= 0 ? table.total
                : table.minItems + (table.maxItems > table.minItems
                ? random.nextInt(table.maxItems - table.minItems + 1) : 0);
        double sum = 0;
        for (Line line : table.lines) {
            sum += line.third;
        }
        if (sum <= 0) {
            return;
        }
        for (int i = 0; i < Math.min(picks, MAX_PICKS); i++) {
            double r = random.nextDouble() * sum;
            Line picked = table.lines.get(table.lines.size() - 1);
            for (Line line : table.lines) {
                r -= line.third;
                if (r < 0) {
                    picked = line;
                    break;
                }
            }
            apply(picked, tables, from, passes, random, loot, depth);
        }
    }

    /** A table by name: {@code pack:name}, else the mob's own pack's, else any pack's. */
    static Table find(String name, ContentId from, Map<ContentId, Table> tables) {
        String written = name.trim().toLowerCase(Locale.ROOT);
        if (written.indexOf(':') > 0) {
            return ContentId.parse(written).map(tables::get).orElse(null);
        }
        if (from != null) {
            Optional<ContentId> own = ContentId.of(from.namespace(), written);
            if (own.isPresent() && tables.containsKey(own.get())) {
                return tables.get(own.get());
            }
        }
        return tables.values().stream().filter(t -> t.id.path().equals(written))
                .min((a, b) -> a.id.compareTo(b.id)).orElse(null);
    }

    /** The loaded tables and their warnings. */
    public static final class Tables {
        private final Map<ContentId, Table> tables;
        private final List<Diagnostic> diagnostics;

        Tables(Map<ContentId, Table> tables, List<Diagnostic> diagnostics) {
            this.tables = Map.copyOf(tables);
            this.diagnostics = List.copyOf(diagnostics);
        }

        public Map<ContentId, Table> tables() {
            return tables;
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
