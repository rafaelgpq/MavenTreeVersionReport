# MavenTreeVersionReport (Dependency Conflict Finder)

## 1. What it answers

> Inside one Maven module, is the **same child artifact** requested with **different versions** by **different
> immediate parents**?

```
demo-app
+- parent-a 1.0
|  \- shared 1.0        <- parent-a asks for shared 1.0
\- parent-b 1.0
   \- shared 2.0        <- parent-b asks for shared 2.0  => reported conflict
```

A reported conflict means "two parents disagree about the version of the same child". It is **not** proof that both
versions are on a runtime classpath, that Maven selected the wrong one, or that anything breaks. Maven's usual
nearest-definition mediation picks the winner; it is **not** "the highest version seen". The tool shows the highest
observed version only as an informational candidate and never edits a POM.

It reads a *saved* Maven Dependency Plugin tree (it does not run Maven or scan live projects).

## 2. Prerequisites

- **Java 17** (the POM sets `maven.compiler.release` 17; JDK 17+ builds it; verified with Temurin 21) and a compatible
  **Maven 3.9.x**. There is no Maven wrapper in this repository, so use your installed `mvn`.
- **Git** for contributing.
- IntelliJ IDEA: *File > Open* the `pom.xml` (import as Maven project); set *Project SDK* to a JDK 17+ and
  *Settings > Build Tools > Maven > Runner > JRE* to the same JDK. Run `com.rafaelgpq.depguardian.DependencyConflictFinder`.

## 3. Quick start (first successful run)

```shell
mvn -B -ntp compile exec:java "-Dapp.config=examples/conflict/application.properties"
```

Expected (verified): 1 module, 1 conflict, exit status 0 (a completed analysis exits 0 even when it finds a conflict).
Reports appear in `target/demo-reports/conflict/` (`dependency-conflicts.md`, `dependency-occurrences.csv`); open the
Markdown in any editor or Markdown viewer and the CSV in a spreadsheet. The first Maven run needs network access to
download plugins.

Running with no `-Dapp.config` uses the root `application.properties`, which also points at `examples/conflict`.
If `deps.file` does not exist you get `ERROR: dependency tree input not found: <path> (see deps.file ...)` and exit 1.

Build and test everything: `mvn -B -ntp clean verify` (no network needed after dependencies are cached; no Maven
subprocesses are spawned by tests).

## 4. Package map

```
src/main/java/com/rafaelgpq/depguardian
  DependencyConflictFinder      entry point / composition root (only place that calls System.exit)
  cli/       ConflictFinderCommand        run coordination, messages, exit status
  config/    ApplicationConfig            property loading, -D overrides, validation
  model/     DependencyModel, VersionUtils immutable coordinates, occurrences, findings; version ordering
  input/     DependencyTreeReader         reading contract + ParseOptions/ParseResult/Diagnostic
  parser/    DependencyTreeParser, NodeParser  tree/log parsing, ancestry, coordinate/annotation parsing
  analysis/  DependencyAnalyzer           validation and module-local classification
  report/    ReportWriter, CsvReport, MarkdownReport, DependencyManagementXml   outputs
src/test/java/com/rafaelgpq/depguardian    mirrors the above, plus integration/ and support/
```

Dependency direction (checked by listing every `import com.rafaelgpq.depguardian...` per package; no cycles):
`model` depends on nothing; `input -> model`; `parser -> input, model`; `analysis -> input, model`;
`config -> input, model`; `report -> analysis, input, model`; `cli -> analysis, config, input, model, report`;
the root class wires `parser::parse` into the command. The reader contract does not know the parser.

## 5. Maintained-file catalog

| Path | Purpose |
|---|---|
| `pom.xml` | Java 17 build; maven-artifact (version ordering), JUnit 5, Mockito, AssertJ (test); exec plugin entry point |
| `application.properties` | Default editable configuration (see section 9) |
| `.gitignore`, `.gitattributes` | Ignore `target/`, IDE files, private captures; keep fixtures byte-exact, script CRLF |
| `.github/workflows/ci.yml` | CI: `mvn -B -ntp clean verify` on JDK 17 |
| `src/main/java/.../DependencyConflictFinder.java` | Entry point, `-D` override collection, exit status |
| `.../cli/ConflictFinderCommand.java` | Runs read, analyze, write; prints summary; maps outcomes to exit 0/1/2 |
| `.../config/ApplicationConfig.java` | Loads properties, presence-based override precedence, validation |
| `.../model/DependencyModel.java` | `ArtifactIdentity`, `Coordinate`, `Occurrence`, `ModuleTree`, `ChildFinding`, `ModuleAnalysis`, `AnalysisResult`, `KNOWN_SCOPES` |
| `.../model/VersionUtils.java` | Maven-compatible version ordering with deterministic tie-break |
| `.../input/DependencyTreeReader.java` | Reading contract, `ParseOptions`, `ParseResult`, `Diagnostic`, `InvalidInputException` |
| `.../parser/DependencyTreeParser.java` | Streaming parser: structure stack, scope filtering, diagnostics |
| `.../parser/NodeParser.java` | Parses one node: coordinate, scope, omitted/managed annotations |
| `.../analysis/DependencyAnalyzer.java` | Rejects unusable input, classifies findings per module |
| `.../report/ReportWriter.java` | Writes reports atomically, removes stale candidate XML, `ReportException` |
| `.../report/CsvReport.java`, `MarkdownReport.java`, `DependencyManagementXml.java` | Serializers with escaping |
| `src/test/java/.../{config,analysis,parser,cli,report,model}/*Test.java` | Unit tests per component (`UnsupportedInputTest`, `UnsupportedInputCommandTest`, `BlankOverrideTest` are the regression tests for the four fixed issues) |
| `.../integration/FixtureEndToEndTest.java`, `ExamplesTest.java` | End-to-end over real fixtures and the teaching examples |
| `.../support/TestTrees.java` | Test-only helper that parses/analyzes tree text |
| `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker` | Selects Mockito's subclass mock maker (`mock-maker-subclass`) |
| `src/test/resources/fixtures/maven-generated/*.tree.txt, *.console.log, tool-versions.txt` | Real Maven output (5 cases x 2 formats) |
| `src/test/resources/fixtures/hand-authored/malformed.console.log` | Hand-written malformed input (not Maven output) |
| `src/test/resources/fixtures/README.md` | Fixture provenance and expectations |
| `examples/conflict/*`, `examples/shared/*` | Hand-authored teaching inputs and their configs |
| `fixtures/generate-fixtures.ps1` | Optional regeneration of the Maven-generated fixtures (Windows PowerShell) |
| `fixtures/libs/pom.xml`, `lib-a`..`lib-h`, `shared-1.0/2.0/3.0` | Synthetic `org.example.lib` artifacts installed into an isolated repo |
| `fixtures/projects/{conflict,noconflict,classifier,managed,multimodule}/pom.xml` (+ `multimodule/mod-*`) | Synthetic projects whose trees are captured |

## 6. How one occurrence flows through the pipeline

Reading -> parsing -> validation/classification -> reporting. Using `examples/conflict`:

1. **Read**: `deps.file` is opened as UTF-8 and handed to the parser.
2. **Parse**: line 5 `\- (org.example:shared:jar:2.0:compile - omitted for conflict with 1.0)` at depth 2 becomes
   - child coordinate `org.example:shared:jar:2.0` (identity `org.example:shared:jar` + empty classifier),
   - immediate parent `org.example:parent-b:jar:1.0`,
   - path `demo-app > parent-b > shared:2.0`, status `OMITTED_FOR_CONFLICT`, selected version 1.0, source line 5.
3. **Classify**: within the module, `shared:jar` has parents parent-a and parent-b and versions 1.0 and 2.0 -> `CONFLICT`.
4. **Report**: it becomes a section in the Markdown and one CSV row per occurrence (`Category=CONFLICT`).

Parse failures stop before classification (strict) or mark the whole result incomplete (lenient).

## 7. Conflict semantics

- **Child identity** = groupId, artifactId, type and classifier (absent classifier normalized to empty). Version and
  scope are not identity: plain jar, `native` classifier and `tests` classifier are three different children.
- **Parent** = the complete coordinate (with version) of the immediate introducing dependency; the module root is the
  parent of direct dependencies.
- **Conflict** = same module, same child identity, different parent coordinates, different exact version strings.
- Two parents asking for the **same** version = *sharing* (`SHARED_SAME_VERSION`), not a conflict. The
  `omitted for duplicate` annotation is still useful provenance: it proves a second parent reaches the child.
- Repeated paths to the same parent do not create a second parent; all paths stay as evidence.
- Different versions under only one parent coordinate = `SAME_PARENT_VARIATION`, reported separately, never a primary conflict.
- Each module is analyzed alone; different versions in independent modules are not a shared-classpath conflict.
- Selected / omitted-for-conflict / omitted-for-duplicate status, scope and `version managed from` annotations are kept
  as provenance. A managed-from annotation is not an extra competing occurrence.
- All supported scopes (`compile, provided, runtime, test, system, import`) are included by default; scope filtering
  happens after structure is tracked, so excluded nodes cannot corrupt descendant ancestry.
- Versions are compared as exact strings for the finding; ordering uses Maven's rules with deterministic tie-breaking.
- A captured tree only describes its modules, profiles, scopes and branches. A non-verbose tree omits precisely the
  discarded versions this tool needs, hence `-Dverbose=true` below.

## 8. Capturing a real project's tree (in the TARGET project, not here)

Do not edit the target project's dependencies. Run from the project to analyze, with its normal profiles/settings
(add `-P...` if you analyze a profile). Use a **fresh** absolute output file (or delete only a previous capture):

```shell
mvn -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree "-Dverbose=true" "-Dtokens=standard" "-DoutputType=text" "-DappendOutput=true" "-DoutputFile=ABSOLUTE_PATH_TO_A_FRESH_dependency-tree.txt"
```

Windows PowerShell example (replace paths with yours):

```powershell
Remove-Item C:\work\captures\app-tree.txt -ErrorAction SilentlyContinue
mvn -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree "-Dverbose=true" "-Dtokens=standard" "-DoutputType=text" "-DappendOutput=true" "-DoutputFile=C:\work\captures\app-tree.txt"
```

POSIX: `rm -f /tmp/app-tree.txt` then the same command with `-DoutputFile=/tmp/app-tree.txt`.

- `verbose=true` keeps `(... - omitted for conflict with X)` nodes: the requested versions Maven discarded.
- In a reactor every module appends to the same absolute file (`appendOutput=true`); clear it before a fresh capture and
  never run parallel writers (`-T`) into it. A stale or doubled file yields `DUPLICATE_MODULE`.
- Supported inputs: plain `outputFile` text, and console logs (`[INFO] ` prefixes, ANSI colours, reactor headers,
  CRLF, UTF-8 BOM). Not supported: `-Dtokens=extended` box drawing, `outputType` other than text (json, dot, graphml, tgf).
- Then point **this** repository at the capture (analysis happens here):

```shell
mvn -B -ntp compile exec:java "-Ddeps.file=C:/work/captures/app-tree.txt" "-Dreport.dir=C:/work/reports"
```

## 9. Configuration

The file is chosen by `-Dapp.config=<path>` (default `application.properties` in the working directory). Precedence is
decided by **key presence**: a `-D` key that is present wins over the file; an absent key falls back to the file, then
the default. The chosen value is then validated, so a present-but-blank override is an error and never silently reuses the
file value.

| Property | Required | Default | `-D` | Blank value | Path base |
|---|---|---|---|---|---|
| `app.config` | no | `application.properties` | `-Dapp.config` only | n/a | working directory |
| `deps.file` | **yes** | none | yes | error | directory of the properties file |
| `report.dir` | no | `target/dependency-conflict-report` | yes | error | directory of the properties file |
| `scopes.include` | no | `all` | yes | error | n/a (`all` or comma list of known scopes) |
| `parser.strict` | no | `true` | yes | error | n/a (`true`/`false`) |
| `report.dependencyManagement` | no | `false` | yes | error | n/a (`true`/`false`) |

"Optional" means an *absent* key uses the default; a key that is present must have a valid value. Relative paths in a
properties file resolve against that file's directory, not the terminal's (shown by the examples, which use
`../../target/demo-reports/...`). Relative paths given via `-D` also resolve against the properties file's directory.
Use forward slashes for Windows paths in properties.

## 10. Examples

Hand-authored teaching inputs (not Maven output). Both run from the project root:

| Example | Command | Result (verified) |
|---|---|---|
| `examples/conflict` | `mvn -B -ntp compile exec:java "-Dapp.config=examples/conflict/application.properties"` | 1 module, **1 conflict** `org.example:shared:jar` (no classifier), versions 1.0 (selected, via parent-a) and 2.0 (omitted for conflict, via parent-b); exit 0; CSV + Markdown in `target/demo-reports/conflict`, no XML |
| `examples/shared` | `mvn -B -ntp compile exec:java "-Dapp.config=examples/shared/application.properties"` | 1 shared child, **0 conflicts**, two different parents, complete; exit 0; files in `target/demo-reports/shared` |

Real report excerpt from `examples/conflict` (an excerpt, not the full file; the full Markdown also has a header
summary and a disclaimer):

```
#### ` org.example:shared:jar `

- Observed versions: ` 1.0, 2.0 `
- Selected by Maven (per input): ` 1.0 `
- Highest seen (informational candidate): ` 2.0 `

| Version | Scope | Status | Immediate parent | Full path | Line |
| 1.0 | compile | SELECTED | org.example:parent-a:jar:1.0 | org.example.demo:demo-app:pom:1.0 > org.example:parent-a:jar:1.0 > org.example:shared:jar:1.0 | 3 |
| 2.0 | compile | OMITTED_FOR_CONFLICT (selected 1.0) | org.example:parent-b:jar:1.0 | ... > org.example:parent-b:jar:1.0 > org.example:shared:jar:2.0 | 5 |
```

CSV (excerpt, 2 of the data rows):

```
CONFLICT,org.example.demo:demo-app:pom:1.0,org.example,shared,jar,,1.0,compile,SELECTED,,,org.example:parent-a:jar:1.0,<path>,true,2.0,3
CONFLICT,org.example.demo:demo-app:pom:1.0,org.example,shared,jar,,2.0,compile,OMITTED_FOR_CONFLICT,1.0,,org.example:parent-b:jar:1.0,<path>,true,2.0,5
```

### Real Maven-generated fixtures (`src/test/resources/fixtures/maven-generated`)

Run any with e.g. `mvn -B -ntp compile exec:java "-Ddeps.file=src/test/resources/fixtures/maven-generated/classifier.tree.txt" "-Dreport.dir=target/demo-reports/classifier"`
(a relative `-D` path resolves against the root `application.properties` directory, which is the project root).

| Case | What it demonstrates | Observed (executed) | Status |
|---|---|---|---|
| `conflict` | shared requested as 1.0, 2.0 and 3.0 under different parents | 1 module, 1 conflict, 4 CSV rows | 0 |
| `noconflict` | same version via two parents | 1 shared child, 0 conflicts, 2 rows | 0 |
| `classifier` | plain jar, `native` and `tests` stay separate identities | 1 conflict on `shared:jar:native`, 2 rows | 0 |
| `managed` | `version managed from` is provenance only | 1 shared child, 0 conflicts, 2 rows (no invented occurrence) | 0 |
| `multimodule` | module-local results | 4 modules, 1 conflict + 1 shared child, no cross-module conflict, 4 rows | 0 |
| `hand-authored/malformed.console.log` | strict failure / lenient incomplete | strict: `IMPOSSIBLE_HIERARCHY` + `MALFORMED_TREE_LINE`, no reports, exit 1; `-Dparser.strict=false`: reports written, `PARTIAL RESULT`, exit 2 | 1 / 2 |
| missing file (`-Ddeps.file=nope.txt`) | useful error, not an empty success | `ERROR: dependency tree input not found: ...`, exit 1 | 1 |

Each `*.tree.txt` is plain `outputFile` text and each `*.console.log` is the console form of the same run.

## 11. Reports

Written to `report.dir`, UTF-8, overwritten each run:

- `dependency-occurrences.csv`: header `Category,Module,GroupId,ArtifactId,Type,Classifier,ObservedVersion,Scope,Status,SelectedVersion,ManagedFromVersion,ParentCoordinate,FullPath,InvolvedInConflict,CandidateVersion,SourceLine`.
  One row per occurrence of every shared child (categories `CONFLICT`, `SHARED_SAME_VERSION`, `SAME_PARENT_VARIATION`);
  each row carries module, version, immediate parent, full path and source line so findings can be regrouped.
- `dependency-conflicts.md`: human-readable summary, an `INCOMPLETE` banner when parsing had errors, and per-module sections.
- `dependency-management-candidates.xml`: **only** when `report.dependencyManagement=true`. It lists informational
  candidates (highest observed version). It needs human review and is not a verified compatible fix.
  When the option is `false` the tool deletes a leftover file of exactly that name in `report.dir` (it never touches other
  files); if it cannot, the run fails with exit 1 so stale XML never looks current.

## 12. Exit status and troubleshooting

| Process exit | Meaning |
|---|---|
| 0 | Analysis completed (conflicts or not) |
| 1 | Input, configuration or report failure (also strict parse failure) |
| 2 | Lenient mode (`parser.strict=false`) continued past errors: result is INCOMPLETE |

These are the application's statuses. `mvn exec:java` runs the entry point inside Maven's own JVM, so a non-zero
status ends that JVM through `System.exit` (Maven then exits with the same code and prints no `BUILD` summary; status 0
returns normally and Maven prints `BUILD SUCCESS`). Treat Maven's status as a wrapper of this one; run
`java -cp ... DependencyConflictFinder` (classpath from `mvn dependency:build-classpath`) for an unwrapped status.

Input line policy (applies to plain and `[INFO]`-prefixed lines alike):

- **Recognized noise** is skipped silently: separators, `--- plugin:ver:goal (id) @ module ---` headers, `---[ pom ]---`, reactor
  rows, `Building`/`from`/`BUILD`/`Total time`/`Finished at` lines. Each is matched as a complete known shape; a log
  level or a leading/trailing `---` is never enough.
- **Malformed dependency-looking content is an error**: unsupported markers (`   - org.example:s:jar:2.0:compile`,
  `   --- org.example:s:jar:2.0:compile`), truncated coordinates (`   - org.example:s:jar`) or a module header with no
  tree. Strict mode exits 1 and writes no reports; lenient mode (`parser.strict=false`) writes reports marked
  `Input status: **INCOMPLETE**`, prints `PARTIAL RESULT` and exits 2.
- **Unknown ordinary prose is a warning** (`UNRECOGNIZED_LINE`) only. Warnings do not make the parse incomplete.

Results describe only the captured input and the selected scopes. Reports are written atomically per file (temp file, then
replace), not as one transaction across the set; a failed scan leaves earlier reports untouched and never refreshes them.

| Symptom | Fix |
|---|---|
| `dependency tree input not found` | Create the capture (section 8) or fix `deps.file` (relative to the properties file) |
| `Property '...' is present but blank` | Give the `-D`/file key a value or remove it |
| `DUPLICATE_MODULE` | The file holds two runs; recapture into a fresh file |
| `UNPARSED_DEPENDENCY_LINE`, `MALFORMED_TREE_LINE`, `UNSUPPORTED_TOKENS` | Re-capture with `-Dtokens=standard`, `-DoutputType=text` |
| Old reports | A new run overwrites them; check the timestamp; stale XML is removed when disabled |
| `Cannot create report directory` / permission errors | Choose a writable `report.dir` |
| `release version 17 not supported` | Run Maven with JDK 17+ (`mvn -version` shows the runtime) |
| PowerShell drops `-D...` args | Quote them: `"-Dkey=value"` |

## 13. Tests, fixtures and contributing

- JUnit 5 + AssertJ; Mockito only for collaborator behavior and failure paths. Tests never start Maven or touch the network.
- Fixtures: `maven-generated` (real Maven 3.9.16, dependency-plugin 3.11.0, JDK 21; see `fixtures` README) and
  `hand-authored` / `examples` (written by hand, labelled as such).
- Regenerating fixtures is optional and needs network and Maven (Windows PowerShell):
  `powershell -ExecutionPolicy Bypass -File fixtures\generate-fixtures.ps1 -Maven "C:\path\to\mvn.cmd"`. It builds the
  synthetic libs into `target/fixture-work/m2` (it creates the log directory itself) and can be re-run without duplicating
  trees. Do not run it casually: it rewrites checked-in expectations.
- Workflow: branch, `mvn -B -ntp clean verify`, review `git diff`, commit with a conventional message, push.

## Limitations

Only captured text is analyzed (no live scanning, no automatic compatibility checks, no automatic fixes). Results depend on the
captured profiles/scopes/modules. A highest-seen candidate is not guaranteed binary-compatible.
