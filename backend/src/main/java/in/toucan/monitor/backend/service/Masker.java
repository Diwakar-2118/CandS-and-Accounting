package in.toucan.monitor.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Masks sensitive values before they are stored or shown: card numbers/PAN, card BIN (shown with
 * last-4 elsewhere it would rebuild most of a PAN), bank account numbers and secrets. Applied at
 * ingestion, so the database never holds the clear values.
 */
public final class Masker {

    private static final Pattern CARD = Pattern.compile(
            "(?i)^(pan|maskedpan|cardnumber|cardno|cardnum|cardlast4|last4|cardlastfour|cardlast4digits)$");
    private static final Pattern BIN = Pattern.compile("(?i)^(card)?bin$");
    private static final Pattern ACCOUNT = Pattern.compile(
            "(?i).*(accountnumber|accountno|acctno|accno|debitaccount|creditaccount|beneficiaryaccount|bankaccount).*");
    private static final Pattern SECRET = Pattern.compile("(?i).*(password|secret|private_?key|token|credential).*");

    private Masker() {
    }

    public static JsonNode mask(JsonNode node) {
        if (node == null) return null;
        JsonNode copy = node.deepCopy();
        walk(copy);
        return copy;
    }

    private static void walk(JsonNode n) {
        if (n.isObject()) {
            ObjectNode o = (ObjectNode) n;
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            o.fields().forEachRemaining(entries::add);
            for (Map.Entry<String, JsonNode> e : entries) {
                String k = e.getKey();
                JsonNode v = e.getValue();
                if (v.isValueNode() && !v.isNull()) {
                    String s = v.asText();
                    if (SECRET.matcher(k).matches()) o.set(k, TextNode.valueOf("***"));
                    else if (BIN.matcher(k).matches()) o.set(k, TextNode.valueOf(keepFirst(s, 2)));
                    else if (CARD.matcher(k).matches() || ACCOUNT.matcher(k).matches()) {
                        o.set(k, TextNode.valueOf(keepLast(s, 4)));
                    }
                } else {
                    walk(v);
                }
            }
        } else if (n.isArray()) {
            n.forEach(Masker::walk);
        }
    }

    static String keepLast(String s, int n) {
        if (s == null || s.isBlank() || s.toLowerCase().contains("decryption failed")) return s;
        String digits = s.replaceAll("\\s", "");
        if (digits.length() <= n) return "*".repeat(digits.length());
        return "*".repeat(Math.min(8, digits.length() - n)) + digits.substring(digits.length() - n);
    }

    static String keepFirst(String s, int n) {
        if (s == null || s.length() <= n) return s == null ? null : "*".repeat(s.length());
        return s.substring(0, n) + "*".repeat(s.length() - n);
    }
}
