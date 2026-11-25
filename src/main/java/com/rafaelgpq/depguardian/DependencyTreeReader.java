package com.rafaelgpq.depguardian;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** File-reading boundary used by the analyzer and its unit tests. */
@FunctionalInterface
public interface DependencyTreeReader {
    List<DependencyConflictFinder.OccurrenceRecord> read(Path input) throws IOException;
}
