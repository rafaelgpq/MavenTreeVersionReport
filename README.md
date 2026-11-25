# MavenTreeVersionReport

A Java 17 Maven application that reads a saved dependency tree, groups children by groupId:artifactId, identifies multiple version strings, and writes Markdown, CSV, and dependencyManagement XML reports.

## Editable input configuration

Edit `application.properties` in the project root:

```properties
deps.file=src/main/resources/deps.txt
```

Set `deps.file` to your actual saved dependency-tree file. The original input data was not supplied and is not fabricated in this project. Absolute paths are supported. Use forward slashes on Windows, for example `C:/Users/YOUR_USERNAME/projects/example/deps.txt`; Java properties interprets backslashes as escape characters.

The external properties file is read at startup, so changing its value does not require recompiling Java. Relative dependency paths are resolved against the directory containing the selected properties file. Report paths remain relative to the application's working directory.

Run these commands from the project root:

```shell
mvn test
mvn compile exec:java
```

Select a different external configuration file:

```shell
mvn compile exec:java -Dapp.config=/path/to/application.properties
```

Optionally override the input for one run:

```shell
mvn compile exec:java -Ddeps.file=/path/to/deps.txt
```

A configuration file is still required when using an override. Missing files and missing or blank `deps.file` values produce errors instead of silently selecting an input.

## What changed

- The workstation-specific input path was removed from Java code.
- `ApplicationConfig` loads the external UTF-8 properties file and validates its input setting.
- The existing grouping and version-selection logic moved into `DependencyAnalyzer`.
- `DependencyTreeReader` provides an injectable file-reading boundary, implemented by the existing parser method in normal execution.
- The command-line entry point loads configuration and invokes the analyzer before calling the existing report methods.
- JUnit 5, Mockito, AssertJ, and Maven Surefire were added for automated tests.

All application packages use `com.rafaelgpq`. The analysis logic continues to group by groupId and artifactId, retain children with multiple version strings, and suggest the highest version seen. Identical child versions from different parents are not included in conflict reports. Distinct parents are not separately required for a conflict.

## Source files

The main entry point is `com.rafaelgpq.depguardian.DependencyConflictFinder`. `Main.java` remains the independent IntelliJ greeting example.

The other application classes are `ApplicationConfig`, `DependencyAnalyzer`, `DependencyTreeReader`, `DepsFileParser`, `ReportPrinter`, and `VersionUtils`.

The initial parser package/import block and opening Javadoc were reconstructed because those lines were not visible in the original screenshots. Other photographed classes were assembled from overlapping screenshots. The current code then includes the explicitly requested namespace, configuration, and testing changes.

## Unit tests

| Test class | Executed cases | Scope |
|---|---:|---|
| ApplicationConfigTest | 10 | Editable values, relative and absolute paths, override precedence, UTF-8, missing files and invalid values |
| DependencyAnalyzerTest | 6 | Shared-child conflicts, preserved origins, same-version duplicates, separate groupIds, mixed input and read failures |
| DepsFileParserTest | 7 | Supported direct-child inputs, root exclusion, scopes, supported module headers, malformed input and missing files |
| VersionUtilsTest | 14 | Numeric and qualifier ordering with the real Maven version comparator, empty/null handling, major extraction and exact equality |
| ReportPrinterTest | 6 | Markdown origins, CSV rows and escaping, UTF-8, XML structure and output failure reporting |
| **Total** | **43** | **0 failures, 0 errors, 0 skipped** |

The Maven test run succeeded using Maven 3.9.11 and OpenJDK 21.0.13, compiling with Java 17 source/target settings. Execution on a separate Java 17 runtime was not performed. A command-line smoke check loaded a separately located properties file, resolved its relative input path, detected one synthetic conflict, and generated valid CSV and XML reports.

Tests use temporary files rather than original input data. Mockito mocks the injected `DependencyTreeReader` in analyzer tests. The test-only `mock-maker-subclass` configuration avoids requiring an inline instrumentation agent; no static or final mocking is needed. AssertJ supplies the test assertions.

## Existing parser and report limitations

The requested changes do not repair all limitations identified in the screenshot transcription:

- The parser recognizes `+- ` and `-- ` markers, and a narrowly formatted `--< module >--` header. Other tree-marker/header formats may be skipped or misclassified.
- Depth is based only on vertical-bar count; indentation made of spaces is not counted as depth.
- Parent paths copied directly from the deque can be reversed relative to the root-first comments.
- Classifier fields and omitted/conflict annotations are not explicitly parsed.
- Test/provided scopes are always skipped; skipped lines do not update the parent stack.
- Grouping excludes type and classifier from the artifact identity.
- Report writers print and swallow IO errors; XML values are concatenated without XML escaping.

The parser tests cover supported inputs and do not establish correctness for all Maven tree formats or nested parent paths. The analyzer's mocked-parent tests establish preservation of supplied origins, not correctness of the parser's construction of those origins. Highest-seen suggestions do not establish dependency compatibility.

## Framework documentation

- [JUnit 5 user guide](https://docs.junit.org/5.12.0/user-guide/index.html)
- [Mockito JUnit Jupiter extension](https://github.com/mockito/mockito/blob/main/mockito-extensions/mockito-junit-jupiter/src/main/java/org/mockito/junit/jupiter/MockitoExtension.java)
- [AssertJ documentation](https://assertj.github.io/doc/)
