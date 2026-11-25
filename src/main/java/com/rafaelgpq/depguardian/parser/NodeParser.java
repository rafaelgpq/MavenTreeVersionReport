package com.rafaelgpq.depguardian.parser;


import com.rafaelgpq.depguardian.model.DependencyModel.*;
import static com.rafaelgpq.depguardian.model.DependencyModel.KNOWN_SCOPES;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the node text of one dependency-tree line (the part after the tree marker).
 *
 * <p>Supported shapes, as printed by Maven Dependency Plugin 3.x with {@code -Dverbose=true}:
 * <pre>
 * group:artifact:type:version:scope
 * group:artifact:type:classifier:version:scope
 * group:artifact:type:version:scope (version managed from 1.0)
 * (group:artifact:type:version:scope - omitted for conflict with 2.0)
 * (group:artifact:type:version:scope - version managed from 1.0; omitted for duplicate)
 * </pre>
 * A coordinate with four fields has no scope; five fields are read as {@code version:scope} only when the last
 * field is a known scope name; six fields are {@code classifier:version:scope}. Anything else is reported as
 * ambiguous instead of being guessed.
 */
final class NodeParser {

    /** @param unrecognizedOmission true when the node is omitted for a reason this parser does not know */
    record Node(ArtifactIdentity identity, String version, String scope, NodeStatus status, String selectedVersion,
                String managedFrom, List<String> annotations, boolean unrecognizedOmission) {}

    /** A node text that cannot be turned into a {@link Node}; {@code code()} is the diagnostic code to report. */
    static final class NodeFormatException extends Exception {
        private final String code;

        NodeFormatException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private static final String CONFLICT_PREFIX = "omitted for conflict with ";
    private static final String MANAGED_PREFIX = "version managed from ";

    private NodeParser() {}

    static Node parse(String nodeText) throws NodeFormatException {
        String text = nodeText.strip();
        if (text.isEmpty()) throw new NodeFormatException("MALFORMED_NODE", "empty dependency node");
        if (text.startsWith("(")) return parseOmitted(text);
        return parseSelected(text);
    }

    private static Node parseSelected(String text) throws NodeFormatException {
        int space = firstWhitespace(text);
        String coordinate = space < 0 ? text : text.substring(0, space);
        String rest = space < 0 ? "" : text.substring(space).strip();
        List<String> reasons = new ArrayList<>();
        if (!rest.isEmpty()) {
            if (!rest.startsWith("(") || !rest.endsWith(")")) {
                throw new NodeFormatException("MALFORMED_NODE",
                        "unexpected text after coordinate '" + coordinate + "': " + rest);
            }
            reasons.addAll(splitReasons(rest.substring(1, rest.length() - 1).replace(") (", "; ")));
        }
        return build(coordinate, reasons, false);
    }

    private static Node parseOmitted(String text) throws NodeFormatException {
        if (!text.endsWith(")")) {
            throw new NodeFormatException("MALFORMED_NODE", "unterminated parenthesized node: " + text);
        }
        String inner = text.substring(1, text.length() - 1);
        int dash = inner.indexOf(" - ");
        if (dash < 0) {
            throw new NodeFormatException("MALFORMED_NODE",
                    "parenthesized node without ' - reason' annotation: " + text);
        }
        return build(inner.substring(0, dash).strip(), splitReasons(inner.substring(dash + 3)), true);
    }

    private static List<String> splitReasons(String annotation) {
        List<String> reasons = new ArrayList<>();
        for (String reason : annotation.split(";")) {
            String trimmed = reason.strip();
            if (!trimmed.isEmpty()) reasons.add(trimmed);
        }
        return reasons;
    }

    private static Node build(String coordinate, List<String> reasons, boolean parenthesized)
            throws NodeFormatException {
        String[] fields = coordinate.split(":", -1);
        for (String field : fields) {
            if (field.isEmpty() || hasForbiddenCharacter(field)) {
                throw new NodeFormatException("MALFORMED_NODE", "invalid coordinate '" + coordinate + "'");
            }
        }
        String classifier = "";
        String version;
        String scope = null;
        switch (fields.length) {
            case 4 -> version = fields[3];
            case 5 -> {
                if (!KNOWN_SCOPES.contains(fields[4])) {
                    throw new NodeFormatException("AMBIGUOUS_COORDINATE", "coordinate '" + coordinate
                            + "' has five fields and its last field is not a known scope; it could be"
                            + " type:version:scope-with-unknown-scope or type:classifier:version");
                }
                version = fields[3];
                scope = fields[4];
            }
            case 6 -> {
                if (!KNOWN_SCOPES.contains(fields[5])) {
                    throw new NodeFormatException("AMBIGUOUS_COORDINATE", "coordinate '" + coordinate
                            + "' has six fields and its last field is not a known scope");
                }
                classifier = fields[3];
                version = fields[4];
                scope = fields[5];
            }
            default -> throw new NodeFormatException("MALFORMED_NODE", "coordinate '" + coordinate + "' has "
                    + fields.length + " fields; expected 4 to 6");
        }

        NodeStatus status = parenthesized ? NodeStatus.OMITTED_OTHER : NodeStatus.SELECTED;
        String selectedVersion = null;
        String managedFrom = null;
        boolean unrecognizedOmission = parenthesized;
        for (String reason : reasons) {
            if (reason.startsWith(CONFLICT_PREFIX)) {
                status = NodeStatus.OMITTED_FOR_CONFLICT;
                selectedVersion = reason.substring(CONFLICT_PREFIX.length()).strip();
                unrecognizedOmission = false;
            } else if (reason.equals("omitted for duplicate")) {
                status = NodeStatus.OMITTED_FOR_DUPLICATE;
                unrecognizedOmission = false;
            } else if (reason.equals("omitted for cycle")) {
                status = NodeStatus.OMITTED_FOR_CYCLE;
                unrecognizedOmission = false;
            } else if (reason.startsWith("omitted")) {
                status = NodeStatus.OMITTED_OTHER;
            } else if (reason.startsWith(MANAGED_PREFIX)) {
                managedFrom = reason.substring(MANAGED_PREFIX.length()).strip();
            }
        }
        return new Node(new ArtifactIdentity(fields[0], fields[1], fields[2], classifier), version, scope, status,
                selectedVersion, managedFrom, reasons, unrecognizedOmission);
    }

    private static boolean hasForbiddenCharacter(String field) {
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (Character.isWhitespace(c) || c == '(' || c == ')') return true;
        }
        return false;
    }

    private static int firstWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) return i;
        }
        return -1;
    }
}
