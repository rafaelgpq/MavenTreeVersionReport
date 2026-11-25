package com.rafaelgpq.depguardian.parser;

import com.rafaelgpq.depguardian.model.DependencyModel.*;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streaming parser for Maven Dependency Plugin text output ({@code -Dtokens=standard}, normally
 * {@code -Dverbose=true}), either plain ({@code outputFile}) or as console log lines with a {@code [INFO] } prefix.
 *
 * <p>Structure comes first: every tree line, including those later excluded by scope, updates the parent stack.
 * Depth is the number of three-character indentation groups ({@code "|  "} or {@code "   "}) before the
 * {@code "+- "} or {@code "\- "} marker. Module roots are unindented {@code group:artifact:packaging:version}
 * lines; each starts a new module. Optional decorated headers ({@code ---< group:artifact >---}) are validated
 * against the following root.
 */
public final class DependencyTreeParser {

    // Terminal colour/control sequences that Maven emits on a console; removed before any matching.
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    // "[INFO] " style prefix of console logs. ERROR lines are surfaced because the captured tree may be incomplete.
    private static final Pattern LOG_PREFIX = Pattern.compile("^\\[(INFO|WARNING|ERROR|DEBUG|TRACE)](?: |$)");
    // Decorated reactor header "-----< group:artifact >-----"; cross-checked against the root line that follows.
    private static final Pattern HEADER =
            Pattern.compile("^-{2,}\\s*<\\s*([^\\s:<>]+):([^\\s:<>]+)\\s*>\\s*-{2,}$");
    // Unindented module root group:artifact:packaging:version (the version may not contain spaces, colons or parens).
    private static final Pattern ROOT = Pattern.compile(
            "^([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-]+):([^\\s:()]+)$");
    // Root-shaped text with too many or too few segments: reported as MALFORMED_ROOT rather than ignored.
    private static final Pattern ROOT_LOOKALIKE = Pattern.compile("^[A-Za-z0-9_.\\-]+(?::[^\\s:]+){3,}$");
    // Group 1 = indentation made of "|  " or "   " triples (its length / 3 + 1 is the depth), 2 = marker (+ or \), 3 = node.
    private static final Pattern CHILD = Pattern.compile("^((?:\\| {2}| {3})*)([+\\\\])- (.*)$");
    // Looks like a branch but has a bad marker/indent (e.g. "-- " or "+-" without a space): MALFORMED_TREE_LINE.
    private static final Pattern MARKER_LOOKALIKE = Pattern.compile("^[| ]*(?:[+\\\\]-|-- ).*");
    /**
     * A line that starts (after any tree-drawing characters and an optional opening parenthesis) with a
     * {@code group:artifact:type[:classifier]:version} shape but matched none of the supported line formats, for
     * example a single-dash branch marker. Treating it as noise would hide a dependency and could turn a real
     * conflict into a trustworthy-looking "no conflict" result.
     */
    private static final Pattern DEPENDENCY_LOOKALIKE =
            Pattern.compile("^[\\s|+\\\\\\-]*\\(?[A-Za-z0-9_.\\-]+(?::[A-Za-z0-9_.\\-${}]+){3,}.*");
    // Unicode box-drawing tree (-Dtokens=extended): deliberately unsupported so it fails loudly instead of being skipped.
    private static final Pattern EXTENDED_TOKENS = Pattern.compile("^[ \u2502\u251C\u2514\u2500]*[\u2502\u251C\u2514][\u2500 ].*");
    // Marker (+, \ or -) followed by group:artifact but too few segments for DEPENDENCY_LOOKALIKE, e.g. a truncated
    // "- org.example:s:jar". Dependency-shaped content must never be downgraded to prose.
    private static final Pattern TRUNCATED_ENTRY =
            Pattern.compile("^[\\s|]*[+\\\\\\-]+\\s*\\(?[A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-${}]+.*");
    // Allowlist of known Maven console text that is safe to skip silently. Each entry is anchored to a complete,
    // specific shape (no "starts or ends with ---" shortcuts), so a malformed dependency such as
    // "--- org.example:s:jar:2.0:compile" cannot hide behind a banner. A log level prefix is never evidence of noise.
    private static final List<Pattern> NOISE = List.of(
            Pattern.compile("^-{3,}$"),                                              // separator line
            Pattern.compile("^-+\\[ [\\w.\\-]+ ]-+$"),                              // ---[ pom ]---
            Pattern.compile("^--- \\S+ \\([^()]*\\) @ \\S+ ---$"),                  // plugin execution header
            Pattern.compile("^Reactor (?:Build Order|Summary(?: for .+)?):$"),
            Pattern.compile("^\\S.*? \\.{3,} (?:SUCCESS|FAILURE|SKIPPED)(?: +\\[.*])?$"), // reactor summary row
            Pattern.compile("^\\S+ +\\[\\w+]$"),                                      // reactor order row
            Pattern.compile("^BUILD (?:SUCCESS|FAILURE)$"),
            Pattern.compile("^Total time:.*"),
            Pattern.compile("^Finished at:.*"),
            Pattern.compile("^Scanning for projects\\.\\.\\.$"),
            Pattern.compile("^Building \\S+ \\S+(?: +\\[\\d+/\\d+])?$"),
            Pattern.compile("^from \\S+$"),
            Pattern.compile("^Wrote dependency tree to:.*"));

    private static boolean isNoise(String stripped) {
        for (Pattern p : NOISE) {
            if (p.matcher(stripped).matches()) return true;
        }
        return false;
    }
    /**
     * One open ancestor on the stack. {@code traversable}: Maven expands only SELECTED nodes, so children of omitted
     * nodes are invalid. {@code excluded}: removed by scope filtering but still kept so that descendants keep the
     * correct ancestry. {@code unusable}: a node that failed to parse; descendants are skipped without cascading errors.
     */
    private record Frame(Coordinate coordinate, boolean traversable, boolean excluded, boolean unusable) {}

    /** Placeholder for a node that could not be understood; its descendants are skipped without extra errors. */
    private static final Frame UNUSABLE = new Frame(null, false, false, true);

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final List<ModuleTree> modules = new ArrayList<>();
    private final ParseOptions options;
    private final String source;

    private int totalLines;
    private int blankLines;
    private int treeLines;
    private int noiseLines;
    private int ignoredLines;
    private int filtered;

    private Coordinate root;
    private int rootLine;
    private List<Occurrence> occurrences;
    private final List<Frame> stack = new ArrayList<>();
    private String headerGa;
    private int headerLine;

    private DependencyTreeParser(String source, ParseOptions options) {
        this.source = source;
        this.options = options;
    }

    public static ParseResult parse(Path file, ParseOptions options) throws IOException {
        Objects.requireNonNull(file, "file");
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader, file.toString(), options);
        }
    }

    public static ParseResult parse(Reader reader, String source, ParseOptions options) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return new DependencyTreeParser(source, options).run(
                reader instanceof BufferedReader buffered ? buffered : new BufferedReader(reader));
    }

    private ParseResult run(BufferedReader reader) throws IOException {
        String raw;
        while ((raw = reader.readLine()) != null) {
            totalLines++;
            if (totalLines == 1 && raw.startsWith("\uFEFF")) raw = raw.substring(1);
            processLine(raw, totalLines);
        }
        finishModule();
        reportDanglingHeader();
        if (modules.isEmpty()) {
            if (totalLines == blankLines) {
                error("EMPTY_INPUT", Math.max(totalLines, 1), "input is empty");
            } else {
                error("NO_TREE_FOUND", 1, "no dependency tree root (group:artifact:packaging:version) was found;"
                        + " the input is not Maven Dependency Plugin tree output");
            }
        }
        return new ParseResult(source, modules, diagnostics, new ParseResult.Statistics(
                totalLines, blankLines, treeLines, noiseLines, ignoredLines, filtered));
    }

    private void processLine(String raw, int line) {
        String text = ANSI.matcher(raw).replaceAll("");
        Matcher prefix = LOG_PREFIX.matcher(text);
        if (prefix.find()) {
            String level = prefix.group(1);
            text = text.substring(prefix.end());
            if (level.equals("ERROR")) {
                noiseLines++;
                error("MAVEN_ERROR_LOGGED", line, "Maven logged an error, so the captured tree may be incomplete: "
                        + text.strip());
                return;
            }
        }
        String content = text.stripTrailing();
        if (content.isBlank()) {
            blankLines++;
            return;
        }

        if (isNoise(content.strip())) {
            noiseLines++;
            return;
        }
        Matcher header = HEADER.matcher(content.strip());
        if (header.matches()) {
            treeLines++;
            startHeader(header.group(1) + ":" + header.group(2), line);
            return;
        }
        Matcher rootMatch = ROOT.matcher(content);
        if (rootMatch.matches()) {
            treeLines++;
            startModule(Coordinate.of(rootMatch.group(1), rootMatch.group(2), rootMatch.group(3), "",
                    rootMatch.group(4)), line);
            return;
        }
        Matcher child = CHILD.matcher(content);
        if (child.matches()) {
            treeLines++;
            processChild(child.group(1).length() / 3 + 1, child.group(3), line);
            return;
        }
        if (EXTENDED_TOKENS.matcher(content).matches()) {
            treeLines++;
            error("UNSUPPORTED_TOKENS", line, "extended tree characters are not supported; "
                    + "generate the tree with -Dtokens=standard");
            return;
        }
        if (MARKER_LOOKALIKE.matcher(content).matches()) {
            treeLines++;
            error("MALFORMED_TREE_LINE", line, "line looks like a dependency-tree entry but its indentation or "
                    + "marker is not supported (expected groups of '|  ' or '   ' and '+- ' or '\\- '): "
                    + content.strip());
            return;
        }
        if (ROOT_LOOKALIKE.matcher(content).matches()) {
            treeLines++;
            error("MALFORMED_ROOT", line, "line looks like a module root coordinate but is not "
                    + "group:artifact:packaging:version: " + content.strip());
            return;
        }
        if (DEPENDENCY_LOOKALIKE.matcher(content).matches()
                || TRUNCATED_ENTRY.matcher(content).matches()) {
            treeLines++;
            error("UNPARSED_DEPENDENCY_LINE", line, "line looks like a dependency but is not a supported tree "
                    + "entry (expected a root, or a node after '+- ' or '\\- '); the analysis would silently "
                    + "miss it: " + content.strip());
            return;
        }
        ignoredLines++;
        warning("UNRECOGNIZED_LINE", line, "line is neither tree structure nor known Maven log output and "
                + "was ignored: " + content.strip());
    }

    private void startHeader(String ga, int line) {
        finishModule();
        reportDanglingHeader();
        headerGa = ga;
        headerLine = line;
    }

    private void reportDanglingHeader() {
        if (headerGa != null) {
            error("HEADER_WITHOUT_TREE", headerLine,
                    "module header '" + headerGa + "' is not followed by a dependency tree root; that module's "
                            + "dependencies are missing from the capture");
            headerGa = null;
        }
    }

    private void startModule(Coordinate coordinate, int line) {
        finishModule();
        if (headerGa != null) {
            String actual = coordinate.identity().groupId() + ":" + coordinate.identity().artifactId();
            if (!headerGa.equals(actual)) {
                error("HEADER_ROOT_MISMATCH", line, "module header '" + headerGa + "' (line " + headerLine
                        + ") contradicts tree root '" + coordinate + "'");
            }
            headerGa = null;
        }
        for (ModuleTree existing : modules) {
            if (existing.root().equals(coordinate)) {
                error("DUPLICATE_MODULE", line, "module '" + coordinate + "' was already read at line "
                        + existing.line() + "; the file may contain output from two runs");
                break;
            }
        }
        root = coordinate;
        rootLine = line;
        occurrences = new ArrayList<>();
        stack.clear();
        stack.add(new Frame(coordinate, true, false, false));
    }

    private void finishModule() {
        if (root != null) modules.add(new ModuleTree(root, rootLine, occurrences));
        root = null;
        occurrences = null;
        stack.clear();
    }

    private void processChild(int depth, String nodeText, int line) {
        if (root == null) {
            error("MISSING_MODULE", line, "dependency node appears before any module root: " + nodeText.strip());
            return;
        }
        if (stack.size() < depth) {
            error("IMPOSSIBLE_HIERARCHY", line, "node at depth " + depth + " has no parent; the deepest open node"
                    + " is at depth " + (stack.size() - 1));
            while (stack.size() <= depth) stack.add(UNUSABLE);
            return;
        }
        // Closing deeper siblings: the stack holds exactly the ancestors of this node (root at index 0).
        truncate(depth);
        Frame parent = stack.get(depth - 1);

        NodeParser.Node node;
        try {
            node = NodeParser.parse(nodeText);
        } catch (NodeParser.NodeFormatException e) {
            if (!parent.unusable()) error(e.code(), line, e.getMessage());
            stack.add(new Frame(null, false, false, true));
            return;
        }
        if (parent.unusable()) {
            stack.add(new Frame(null, false, false, true));
            return;
        }
        if (!parent.traversable()) {
            error("OMITTED_PARENT", line, "node is nested under an omitted node ("
                    + stack.get(depth - 1).coordinate() + "); Maven does not expand omitted nodes");
            stack.add(new Frame(null, false, false, true));
            return;
        }
        if (node.unrecognizedOmission()) {
            warning("UNRECOGNIZED_OMISSION", line, "omission reason is not recognized: " + node.annotations());
        }
        if (node.scope() == null) warning("MISSING_SCOPE", line, "node has no scope: " + nodeText.strip());

        Coordinate coordinate = new Coordinate(node.identity(), node.version());
        boolean excluded = parent.excluded() || !options.includes(node.scope());
        stack.add(new Frame(coordinate, node.status() == NodeStatus.SELECTED, excluded, false));
        if (excluded) {
            filtered++;
            return;
        }
        // Full root-to-parent path for evidence in reports; excluded ancestors cannot reach here (excluded is inherited).
        List<Coordinate> parentPath = new ArrayList<>(depth);
        for (int i = 0; i < depth; i++) parentPath.add(stack.get(i).coordinate());
        occurrences.add(new Occurrence(root, coordinate, node.scope(), node.status(), node.selectedVersion(),
                node.managedFrom(), node.annotations(), parentPath, line));
    }

    private void truncate(int size) {
        while (stack.size() > size) stack.remove(stack.size() - 1);
    }

    private void error(String code, int line, String message) {
        diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, code, source, line, message));
    }

    private void warning(String code, int line, String message) {
        diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, code, source, line, message));
    }
}
