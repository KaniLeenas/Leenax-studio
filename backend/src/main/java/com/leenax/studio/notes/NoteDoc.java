package com.leenax.studio.notes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leenax.studio.common.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The notes editor's document (TipTap JSON), held to an allowlist: only the blocks and
 * marks the editor offers, only their known attributes, and only web or mail links.
 * Anything else is refused, so stored notes can always be shown and exported safely.
 */
public final class NoteDoc {

    private NoteDoc() {
    }

    static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_NODES = 25_000;
    static final int MAX_DEPTH = 30;
    static final int MAX_TEXT = 400_000;

    /** Block and inline node types, with the attributes each may carry. */
    static final Map<String, Set<String>> NODES = Map.ofEntries(
            Map.entry("doc", Set.of()), Map.entry("paragraph", Set.of()), Map.entry("heading", Set.of("level")),
            Map.entry("text", Set.of()), Map.entry("bulletList", Set.of()), Map.entry("orderedList", Set.of("start")),
            Map.entry("listItem", Set.of()), Map.entry("blockquote", Set.of()), Map.entry("codeBlock", Set.of("language")),
            Map.entry("horizontalRule", Set.of()), Map.entry("hardBreak", Set.of()),
            Map.entry("table", Set.of()), Map.entry("tableRow", Set.of()),
            Map.entry("tableHeader", Set.of("colspan", "rowspan", "colwidth")), Map.entry("tableCell", Set.of("colspan", "rowspan", "colwidth")),
            Map.entry("mediaImage", Set.of("assetId", "alt")), Map.entry("mathBlock", Set.of("latex")),
            Map.entry("mathInline", Set.of("latex")), Map.entry("mermaid", Set.of("code")));

    static final Set<String> MARKS = Set.of("bold", "italic", "underline", "strike", "code", "link", "subscript", "superscript");
    static final Pattern LANGUAGE = Pattern.compile("[a-z0-9+#._-]{1,30}");
    static final Pattern UUID_RE = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static final Pattern SAFE_LINK = Pattern.compile("(?i)(https?://|mailto:)[^\\s\"<>]{1,2000}");

    // ---- validation --------------------------------------------------------------------------------

    /** A clean copy of the document, or 400 naming what isn't allowed. */
    public static JsonNode clean(JsonNode doc) {
        if (doc == null || !doc.isObject() || !"doc".equals(doc.path("type").asText())) {
            throw ApiException.badRequest("The notes must be an editor document");
        }
        int[] counts = {0, 0};
        return node(doc, 0, counts);
    }

    private static ObjectNode node(JsonNode n, int depth, int[] counts) {
        if (++counts[0] > MAX_NODES) throw ApiException.badRequest("The notes are too long: split them into smaller topics");
        if (depth > MAX_DEPTH) throw ApiException.badRequest("The notes are nested too deeply");
        String type = n.path("type").asText("");
        Set<String> allowed = NODES.get(type);
        if (allowed == null) throw ApiException.badRequest("Notes can't contain “" + type + "” blocks");
        ObjectNode out = JSON.createObjectNode();
        out.put("type", type);
        if (type.equals("text")) {
            String text = nfc(n.path("text").asText(""));
            if (text.isEmpty()) throw ApiException.badRequest("Empty text in the notes");
            counts[1] += text.length();
            if (counts[1] > MAX_TEXT) throw ApiException.badRequest("The notes are too long: split them into smaller topics");
            out.put("text", text);
            ArrayNode marks = marks(n.path("marks"));
            if (!marks.isEmpty()) out.set("marks", marks);
            return out;
        }
        ObjectNode attrs = attrs(type, n.path("attrs"), allowed);
        if (!attrs.isEmpty()) out.set("attrs", attrs);
        JsonNode content = n.path("content");
        if (content.isArray() && !content.isEmpty()) {
            ArrayNode kids = out.putArray("content");
            for (JsonNode k : content) kids.add(node(k, depth + 1, counts));
        }
        return out;
    }

    private static ObjectNode attrs(String type, JsonNode in, Set<String> allowed) {
        ObjectNode out = JSON.createObjectNode();
        if (!in.isObject()) return out;
        for (String key : allowed) {
            JsonNode v = in.get(key);
            if (v == null || v.isNull()) continue;
            switch (key) {
                case "level" -> {
                    int l = v.asInt(2);
                    out.put(key, Math.max(1, Math.min(4, l)));
                }
                case "start" -> out.put(key, Math.max(1, Math.min(10_000, v.asInt(1))));
                case "colspan", "rowspan" -> out.put(key, Math.max(1, Math.min(20, v.asInt(1))));
                case "colwidth" -> {
                    if (v.isArray()) {
                        ArrayNode w = out.putArray(key);
                        for (JsonNode x : v) w.add(Math.max(20, Math.min(2000, x.asInt(100))));
                    }
                }
                case "language" -> {
                    String lang = v.asText("").strip().toLowerCase(java.util.Locale.ROOT);
                    if (LANGUAGE.matcher(lang).matches()) out.put(key, lang);
                }
                case "assetId" -> {
                    String id = v.asText("");
                    if (!UUID_RE.matcher(id).matches()) throw ApiException.badRequest("An image in the notes isn't from the media library");
                    out.put(key, id.toLowerCase(java.util.Locale.ROOT));
                }
                case "alt" -> out.put(key, limit(nfc(v.asText("")), 300, "An image description"));
                case "latex" -> out.put(key, limit(v.asText("").strip(), type.equals("mathBlock") ? 5000 : 1000, "An equation"));
                case "code" -> out.put(key, limit(v.asText(""), 20_000, "A diagram"));
                default -> {
                }
            }
        }
        if (type.equals("mediaImage") && !out.has("assetId")) throw ApiException.badRequest("An image in the notes has no file");
        return out;
    }

    private static ArrayNode marks(JsonNode in) {
        ArrayNode out = JSON.createArrayNode();
        if (!in.isArray()) return out;
        for (JsonNode m : in) {
            String type = m.path("type").asText("");
            if (!MARKS.contains(type)) throw ApiException.badRequest("Notes can't use “" + type + "” formatting");
            ObjectNode mark = JSON.createObjectNode();
            mark.put("type", type);
            if (type.equals("link")) {
                String href = m.path("attrs").path("href").asText("").strip();
                if (!SAFE_LINK.matcher(href).matches()) throw ApiException.badRequest("Links in notes must be web (https) or email addresses");
                mark.putObject("attrs").put("href", href);
            }
            out.add(mark);
        }
        return out;
    }

    private static String limit(String s, int max, String what) {
        if (s.length() > max) throw ApiException.badRequest(what + " is longer than " + max + " characters");
        return s;
    }

    static String nfc(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    // ---- reading -----------------------------------------------------------------------------------

    /** The notes as plain text (for prompts and checks): equations as $...$, diagrams as [diagram]. */
    public static String plainText(JsonNode doc) {
        StringBuilder sb = new StringBuilder();
        text(doc, sb);
        return sb.toString().replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static void text(JsonNode n, StringBuilder sb) {
        String type = n.path("type").asText("");
        switch (type) {
            case "text" -> sb.append(n.path("text").asText(""));
            case "hardBreak" -> sb.append('\n');
            case "mathInline" -> sb.append('$').append(n.path("attrs").path("latex").asText("")).append('$');
            case "mathBlock" -> sb.append("\n$$").append(n.path("attrs").path("latex").asText("")).append("$$\n");
            case "mermaid" -> sb.append("\n[diagram]\n");
            case "mediaImage" -> sb.append("\n[image: ").append(n.path("attrs").path("alt").asText("")).append("]\n");
            case "tableRow" -> {
                // one line per row: "cell | cell |"
                for (JsonNode cell : n.path("content")) {
                    StringBuilder c = new StringBuilder();
                    text(cell, c);
                    sb.append(c.toString().strip().replaceAll("\\s*\\n\\s*", " ")).append(" | ");
                }
                sb.append('\n');
            }
            default -> {
                for (JsonNode k : n.path("content")) text(k, sb);
                if (type.matches("paragraph|heading|listItem|codeBlock|blockquote")) sb.append('\n');
            }
        }
    }

    /** Headings in order, for change lists. */
    public static List<String> headings(JsonNode doc) {
        List<String> out = new ArrayList<>();
        walk(doc, n -> {
            if ("heading".equals(n.path("type").asText())) {
                StringBuilder sb = new StringBuilder();
                text(n, sb);
                out.add(sb.toString().strip());
            }
        });
        return out;
    }

    /** Equations and diagrams in the notes: kind (MATH or MERMAID) and their source. */
    public record Figure(String kind, String source) {
        public String hash() {
            return sha256(source.strip());
        }
    }

    public static List<Figure> figures(JsonNode doc) {
        List<Figure> out = new ArrayList<>();
        walk(doc, n -> {
            switch (n.path("type").asText()) {
                case "mathBlock", "mathInline" -> out.add(new Figure("MATH", n.path("attrs").path("latex").asText("")));
                case "mermaid" -> out.add(new Figure("MERMAID", n.path("attrs").path("code").asText("")));
                default -> {
                }
            }
        });
        return out.stream().filter(f -> !f.source().isBlank()).distinct().toList();
    }

    public static List<String> imageIds(JsonNode doc) {
        List<String> out = new ArrayList<>();
        walk(doc, n -> {
            if ("mediaImage".equals(n.path("type").asText())) out.add(n.path("attrs").path("assetId").asText());
        });
        return out.stream().distinct().toList();
    }

    public static void walk(JsonNode n, java.util.function.Consumer<JsonNode> f) {
        f.accept(n);
        for (JsonNode k : n.path("content")) walk(k, f);
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- from the AI's answer ----------------------------------------------------------------------

    /**
     * Builds an editor document from the AI's sections. Each section has a heading and
     * blocks: paragraph, subheading, bullets, numbered, table, code, equation, diagram, quote.
     * Inline **bold**, *italic*, `code` and $equation$ become marks and equations.
     */
    public static ObjectNode fromSections(String title, JsonNode sections) {
        ObjectNode doc = JSON.createObjectNode();
        doc.put("type", "doc");
        ArrayNode content = doc.putArray("content");
        if (title != null && !title.isBlank()) content.add(heading(1, title));
        for (JsonNode s : sections) {
            String h = s.path("heading").asText("").strip();
            if (!h.isEmpty()) content.add(heading(2, h));
            for (JsonNode b : s.path("blocks")) {
                ObjectNode block = block(b);
                if (block != null) content.add(block);
            }
        }
        return doc;
    }

    private static ObjectNode block(JsonNode b) {
        String type = b.path("type").asText("");
        return switch (type) {
            case "paragraph" -> para(b.path("text").asText(""));
            case "subheading" -> heading(3, b.path("text").asText(""));
            case "quote" -> wrap("blockquote", para(b.path("text").asText("")));
            case "bullets", "numbered" -> {
                ObjectNode list = JSON.createObjectNode();
                list.put("type", type.equals("bullets") ? "bulletList" : "orderedList");
                ArrayNode items = list.putArray("content");
                for (JsonNode i : b.path("items")) {
                    ObjectNode p = para(i.asText(""));
                    if (p != null) items.add(wrap("listItem", p));
                }
                yield items.isEmpty() ? null : list;
            }
            case "table" -> table(b.path("header"), b.path("rows"));
            case "code" -> {
                String code = b.path("code").asText("");
                if (code.isBlank()) yield null;
                ObjectNode c = JSON.createObjectNode();
                c.put("type", "codeBlock");
                String lang = b.path("language").asText("").toLowerCase(java.util.Locale.ROOT);
                if (LANGUAGE.matcher(lang).matches()) c.putObject("attrs").put("language", lang);
                c.putArray("content").add(textNode(code, null));
                yield c;
            }
            case "equation" -> leaf("mathBlock", "latex", b.path("latex").asText("").strip());
            case "diagram" -> leaf("mermaid", "code", b.path("mermaid").asText("").strip());
            default -> null;
        };
    }

    private static ObjectNode leaf(String type, String attr, String value) {
        if (value.isEmpty()) return null;
        ObjectNode n = JSON.createObjectNode();
        n.put("type", type);
        n.putObject("attrs").put(attr, value);
        return n;
    }

    private static ObjectNode table(JsonNode header, JsonNode rows) {
        if (!rows.isArray() || rows.isEmpty()) return null;
        ObjectNode t = JSON.createObjectNode();
        t.put("type", "table");
        ArrayNode trs = t.putArray("content");
        if (header.isArray() && !header.isEmpty()) trs.add(row(header, "tableHeader"));
        for (JsonNode r : rows) if (r.isArray() && !r.isEmpty()) trs.add(row(r, "tableCell"));
        return t;
    }

    private static ObjectNode row(JsonNode cells, String cellType) {
        ObjectNode tr = JSON.createObjectNode();
        tr.put("type", "tableRow");
        ArrayNode tds = tr.putArray("content");
        for (JsonNode c : cells) {
            ObjectNode cell = JSON.createObjectNode();
            cell.put("type", cellType);
            ObjectNode p = para(c.asText(""));
            cell.putArray("content").add(p != null ? p : emptyPara());
            tds.add(cell);
        }
        return tr;
    }

    private static ObjectNode heading(int level, String text) {
        ObjectNode h = JSON.createObjectNode();
        h.put("type", "heading");
        h.putObject("attrs").put("level", level);
        h.putArray("content").add(textNode(text.strip(), null));
        return h;
    }

    private static ObjectNode wrap(String type, ObjectNode child) {
        if (child == null) return null;
        ObjectNode n = JSON.createObjectNode();
        n.put("type", type);
        n.putArray("content").add(child);
        return n;
    }

    private static ObjectNode emptyPara() {
        ObjectNode p = JSON.createObjectNode();
        p.put("type", "paragraph");
        return p;
    }

    static final Pattern INLINE = Pattern.compile("\\*\\*([^*\\n]+)\\*\\*|`([^`\\n]+)`|\\$([^$\\n]+)\\$|(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])");

    /** A paragraph from text with **bold**, *italic*, `code` and $math$. */
    static ObjectNode para(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) return null;
        ObjectNode p = JSON.createObjectNode();
        p.put("type", "paragraph");
        ArrayNode content = p.putArray("content");
        Matcher m = INLINE.matcher(t);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) content.add(textNode(t.substring(at, m.start()), null));
            if (m.group(1) != null) content.add(textNode(m.group(1), "bold"));
            else if (m.group(2) != null) content.add(textNode(m.group(2), "code"));
            else if (m.group(3) != null) {
                ObjectNode math = leaf("mathInline", "latex", m.group(3).strip());
                if (math != null) content.add(math);
            }
            else content.add(textNode(m.group(4), "italic"));
            at = m.end();
        }
        if (at < t.length()) content.add(textNode(t.substring(at), null));
        return p;
    }

    private static ObjectNode textNode(String text, String mark) {
        ObjectNode n = JSON.createObjectNode();
        n.put("type", "text");
        n.put("text", text);
        if (mark != null) n.putArray("marks").addObject().put("type", mark);
        return n;
    }
}
