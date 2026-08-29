package ai.resourcepack.engine.core.content;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.toml.TomlParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A definition file written in TOML rather than YAML.
 *
 * <p>The same content, spelled differently. TOML is what a lot of people
 * writing configuration reach for now, and refusing it costs an author a
 * conversion step for no reason of ours — nothing about a definition needs
 * YAML specifically, and the loader has never cared: it wants a map.
 *
 * <p>So this turns a TOML document into exactly the same nested
 * {@code Map<String, Object>} SnakeYAML produces, and everything downstream —
 * every parser, every diagnostic, every test — carries on knowing nothing
 * about which one it came from.
 *
 * <h2>What that means for an author</h2>
 *
 * <p>A table is a definition:
 *
 * <pre>
 * [ruby]
 * material = "DIAMOND"
 * name = "&amp;cRuby"
 *
 * [chair.place]
 * seat = 0.5
 * </pre>
 *
 * <p>An ID with a slash in it needs quoting — {@code ["weapons/sword"]} —
 * because a bare dot or slash in a TOML key means something to TOML. That is
 * the one thing that catches people out, and it is why the error message for a
 * bad table name says so.
 */
final class Toml {

    private Toml() {
    }

    /**
     * Reads a TOML document as the loader's map.
     *
     * @throws ParsingException if it is not TOML, with the parser's own
     *                          message, which names the line
     */
    static Map<String, Object> read(Reader source) {
        Config config = new TomlParser().parse(source);
        return plain(config);
    }

    /**
     * night-config's own types, turned into plain ones.
     *
     * <p>Deliberately a copy rather than a wrapper: a {@code Config} is a live
     * view over the parser's storage, and everything downstream of here treats
     * a definition as an immutable map it can hold on to.
     */
    private static Map<String, Object> plain(Config config) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Config.Entry entry : config.entrySet()) {
            out.put(entry.getKey(), value(entry.getValue()));
        }
        return out;
    }

    private static Object value(Object raw) {
        if (raw instanceof Config) {
            return plain((Config) raw);
        }
        if (raw instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object each : (List<?>) raw) {
                out.add(value(each));
            }
            return out;
        }
        // A TOML date or time arrives as a java.time type. Nothing in a
        // definition is one, and toString is what a parser expecting a string
        // would have got from YAML anyway.
        if (raw instanceof java.time.temporal.TemporalAccessor) {
            return raw.toString();
        }
        return raw;
    }
}
