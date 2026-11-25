package com.leenax.studio.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.approvals.Review;
import com.leenax.studio.approvals.Review.Check;
import com.leenax.studio.approvals.Review.Context;
import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.common.ApiException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A topic's QA report as an approval stage. Leenax makes it from the QA results; nobody edits
 * it. It can be approved only when everything it covers is approved and was checked by QA in
 * its current form. Open critical issues block it too (see QaStore, and V13 in the database).
 */
@Component
public class QaReportRules implements Review.ContentHandler {

    @Override
    public Kind kind() {
        return Kind.QA;
    }

    @Override
    public JsonNode normalise(JsonNode raw, UUID dependsOn) {
        if (raw == null || !raw.path("topicId").isTextual() || !raw.path("items").isArray() || !raw.path("parts").isObject()) {
            throw ApiException.badRequest("Not a QA report");
        }
        return raw;
    }

    @Override
    public List<Check> checks(JsonNode content, Context ctx) {
        List<Check> out = new ArrayList<>();
        JsonNode items = content.path("items");
        if (items.isEmpty()) {
            out.add(Check.fail("covers", "The report covers nothing: write notes and questions for the topic first"));
            return out;
        }
        List<UUID> approvedNotes = ctx.approvedOfKind().apply(Kind.NOTES), approvedBanks = ctx.approvedOfKind().apply(Kind.MCQ);
        List<String> notApproved = new ArrayList<>(), unchecked = new ArrayList<>();
        for (JsonNode i : items) {
            String label = i.path("label").asText() + " version " + i.path("versionNo").asInt();
            UUID v = uuid(i.path("versionId").asText());
            List<UUID> approved = "MCQ".equals(i.path("kind").asText()) ? approvedBanks : approvedNotes;
            if (v == null || !approved.contains(v)) notApproved.add(label);
            if (i.path("run").isMissingNode() || i.path("run").isNull() || !i.path("run").path("current").asBoolean(false)) unchecked.add(label);
        }
        out.add(notApproved.isEmpty() ? Check.pass("approved", "Covers only approved notes and questions")
                : Check.fail("approved", "Not approved (or replaced since): " + String.join(", ", notApproved) + ". Approve them, then make the report again."));
        out.add(unchecked.isEmpty() ? Check.pass("checked", "QA checked each of them as they are now")
                : Check.fail("checked", "Not checked by QA as they are now: " + String.join(", ", unchecked) + ". Run QA, then make the report again."));
        String headline = content.path("headline").asText();
        if ("NEEDS_ATTENTION".equals(headline)) out.add(Check.warn("major", "Major issues are still open; the reviewer decides whether they matter"));
        return out;
    }

    @Override
    public List<String> diff(JsonNode before, JsonNode after) {
        String a = before.path("headline").asText(), b = after.path("headline").asText();
        return a.equals(b) ? List.of("Made again") : List.of("Headline " + a.toLowerCase().replace('_', ' ') + " → " + b.toLowerCase().replace('_', ' '));
    }

    @Override
    public boolean editableByHand() {
        return false;
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }
}
