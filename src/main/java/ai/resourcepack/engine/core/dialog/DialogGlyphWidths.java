package ai.resourcepack.engine.core.dialog;

/**
 * How wide the client draws each character of its default font's bitmap
 * sheets — ascii, accented and nonlatin_european — in GUI pixels. Internal.
 *
 * <p><b>GENERATED from Minecraft 1.21.11's assets/minecraft/font/include/default.json
 * and the three sheets it names. Do not edit by hand.</b> Studio carries the
 * same string and checks the two are equal.
 *
 * <h2>What it is for</h2>
 *
 * A Studio dialog can draw a LIVE value in its picture: words the engine fills
 * per player as it opens the dialog. The game draws them in the middle of a
 * body line that must come to exactly its full width, so the pack draws the
 * words and steps back over them with the same words in a font of negative
 * spaces. That only cancels for a character those spaces cover — so
 * {@link DialogPlaceholders} puts a {@code ?} in for any other, which is what
 * the picture does with a character it cannot draw — and only while the words
 * stay inside the line, which is what {@link #advance} is for.
 *
 * <p>The table is runs of consecutive codepoints: a first codepoint in hex, a
 * colon, and one base-36 digit per character, the inked width the game
 * measures. A character's advance is that width times the font's scale, plus
 * one; a space is written as its advance less one so the same rule covers it.
 */
public final class DialogGlyphWidths {

    private DialogGlyphWidths() {
    }

    /** The table, exactly as Studio's generated copy spells it. */
    static final String TABLE =
            "20:313555513335151555555555551145456555555553555555555555555553535525555545515425555555355555531"
            + "36;a1:15575153746537545442561;b9:346777555555595555533336555555555555555555555955555223355555555"
            + "555555555555555555555557665555555555555555555576443344323154555445252535364555555755555555995555"
            + "555555555553545355555555555555555555555367556656667555555656583354355655777775555553635775567555"
            + "555555551351999997999553355555555555555555996655545555555999558555559955555555555544555555555555"
            + "555355555755555555555555555537459955565555567556666666775555556655755754655555553235425555665597"
            + "55565555555565633755555565555555555655456559998789775567;2bb:1;2cc:1;2d9:1;327:2;37e:1;386:71775"
            + ";38c:7;38e:77555555555355555555;3a3:55555553555525555555552455555555655555735557;3d3:96;3db:5;40"
            + "0:5575553357665555555565755555555555557565786755755554655555455555555555555666557555645513576645"
            + "55;462:66;472:556666;48a:666655556554865565556565666688665555555566766655557777375546655665566;4"
            + "d0:5555995555557555555555555555555555555554766555555;502:887766888855665555888899555555888866666"
            + "6996666;531:55665555665465555656555555565555545555;559:22232355556656455652655556565655555556755"
            + "4765566;58a:5;58f:5;5be:5;5c3:1;5c6:3;5d0:554551355155555135555555555;5f0:33324;7c8:5;10a0:57755"
            + "777557657767557555555575555755557;10c7:5;10cd:5;10d0:5555555555755555555555555555555456555555555"
            + "33555;15e1:5;15fa:5;1614:5;16a0:4455554434445335543444555455535351365535453513553443445553345556"
            + "7575555154721555555742555;1d00:5;1d02:9;1d04:55;1d07:5;1d09:155;1d0d:5;1d0f:5;1d14:9;1d18:5;1d1a"
            + ":555;1d20:555;1d6b:9775777777555;1d7a:8;1d80:564652665555566666665626565;1e00:555555555555555555"
            + "555555555555545555555555554433545454525353535555555555555555555555555555555555555555555553535353"
            + "555555555555555555555555555555555555535553445555555555555555555555555555555555555555553331555555"
            + "55555555777777777755557777777777555555557654565555565588999988555555;1f18:889999;1f20:5555555588"
            + "9999883255554469878766555555;1f48:889999;1f50:55555555;1f59:8;1f5b:9;1f5d:9;1f5f:877777777889999"
            + "8955555532555577;1f80:55555555889999885555555588999988777777778899998955555;1fb6:5555885;1fc2:55"
            + "5;1fc6:5588885;1fd0:4333;1fd6:444366;1fe0:5555555555888;1ff2:777;1ff6:7788885;2010:33;2013:68;20"
            + "18:222;201c:444;2020:552;2026:7;2030:78246246;2039:33735;2042:7;2047:97746;204e:31;2051:33;2057:"
            + "8;2070:41;2074:444444333224434444444433322;20a0:55565579977666595757755755675665;2116:97;2122:8;"
            + "2132:5;2139:9;2141:5;2144:5;214b:5;2150:8898888897788886;2189:8;2190:75758;21c4:7;21cf:8;21d2:8;"
            + "21d4:8;21f5:6;2200:745557;2208:55;220b:55;2211:555;2219:567767;2225:3;2227:555;222b:5;222e:5;223"
            + "4:55;2248:6;2254:7;2260:566;2264:55;2282:555555;22a2:5;22a4:55;22a8:5;22bb:555;22c3:5;22c6:3;230"
            + "0:7;2302:7;2318:7;231a:77;2320:74;23cf:5;23e9:66;23ed:776;23f3:73355355771;2460:9999999999999999"
            + "9999;24b6:99999999999999999999999999999999999999999999999999999;2500:8;2502:5;250c:8;2510:5;2514"
            + ":8;2518:5;251c:8;2524:5;252c:8;2534:8;253c:8;2550:87888577888577888577888888888;2580:8;2584:8;25"
            + "88:8;258c:4;2590:8788;25a0:55;25b2:55;25b6:66;25bc:55;25c0:66;25c6:55;25cb:5;25ce:74;25d8:3;25e6"
            + ":3;2600:8877777;2608:7;2610:999;2614:7;261c:9;261e:9;2620:7;262e:9955555555;2639:557;263d:8;2640"
            + ":3;2642:5;2660:55555555;2669:3577335;2680:777777;2690:77;2693:77;2697:7;26a0:95;26a5:5;26c4:6;26"
            + "c8:8;26cf:8;26e8:7;2702:7;2709:7;270e:7;2714:6;2718:6;2744:7;274c:6;2763:57;27d8:5;295d:5;29c8:7"
            + ";2b50:7;2b58:7;2bea:77;2c62:7;2c65:65;2c6d:5;2c6f:5;2c71:7;2c7e:55;2d00:566555655555556655555555"
            + "55555655555555;2e18:5;2e2e:5;2e35:1;2e38:5;2e41:1;2e4b:5;3012:5;3125:5;a680:66559855666655655566"
            + "5586;a726:5576;a730:5599999998989855;a74e:99;a75a:54;a760:55;a779:5;a780:52;a794:56;a7a8:65;a7af"
            + ":55;a7c4:5;a7c6:6;ab50:55;ab63:9;fb00:7568959;fb13:99999;fb1d:1;fb1f:3;fb2b:5;fb2e:55;fb31:5;fb3"
            + "5:3;fb3b:4;fb44:5;fb4a:525;fb4e:5;ff0b:5;fffd:7;10330:555555555155555555555555555;1f30a:8;1f327:"
            + "8;1f356:8;1f3a3:8;1f3f9:8;1f514:7;1f525:6;1f531:8;1f5e1:8;1f6e1:7;1f9ea:7;1fa93:6;1faa3:7";

    private static final java.util.Map<Integer, Integer> WIDTHS = parse(TABLE);

    static java.util.Map<Integer, Integer> parse(String table) {
        java.util.Map<Integer, Integer> out = new java.util.HashMap<>();
        for (String run : table.split(";")) {
            int colon = run.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            int cp = Integer.parseInt(run.substring(0, colon), 16);
            for (int i = colon + 1; i < run.length(); i++) {
                out.put(cp++, Character.digit(run.charAt(i), 36));
            }
        }
        return java.util.Map.copyOf(out);
    }

    /** Whether the client draws this character from a bitmap sheet, so its width is known exactly. */
    public static boolean covers(int codepoint) {
        return WIDTHS.containsKey(codepoint);
    }

    /**
     * How far the character moves the pen: its width times {@code scale}, plus
     * the pixel the renderer adds after every glyph, plus one more when bold.
     * Minus one for a character this table does not cover.
     */
    public static int advance(int codepoint, int scale, boolean bold) {
        Integer width = WIDTHS.get(codepoint);
        return width == null ? -1 : width * Math.max(1, scale) + 1 + (bold ? 1 : 0);
    }
}
