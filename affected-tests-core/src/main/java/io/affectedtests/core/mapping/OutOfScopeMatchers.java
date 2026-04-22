package io.affectedtests.core.mapping;

import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Compiles the raw {@code outOfScope*Dirs} strings a user writes in
 * {@code build.gradle} into {@link Predicate}s over normalised path
 * strings.
 *
 * <p>Kept in its own class so both {@link PathToClassMapper} (which
 * classifies diff-side paths) and
 * {@link io.affectedtests.core.discovery.ProjectIndex} (which filters
 * indexed files on disk) evaluate the same shape of entry with the
 * same semantics. Before this extraction the two sides disagreed on
 * glob-form entries: the mapper honoured
 * {@code outOfScopeTestDirs = ["api-test/**"]} while the index
 * treated the same string as a literal prefix, so a mixed diff (one
 * production file + a refactor under {@code api-test/}) would bucket
 * the api-test file correctly but still dispatch its test because the
 * index never filtered it out. That divergence is what this utility
 * closes — there is exactly one source of truth for "is this path
 * out of scope" now.
 *
 * <p>Each raw entry is classified into one of two semantics based on
 * whether it contains any glob metacharacter ({@code *}, {@code ?},
 * {@code [}, or the opening brace — which has to be written here as
 * {@code &#123;} because Javadoc would otherwise close the
 * {@code @code} tag at the very character we are trying to document):
 *
 * <ul>
 *   <li>Glob entries (e.g. {@code "api-test/**"}) compile to a
 *       {@link PathMatcher} using the JVM's default file system
 *       {@code glob:} syntax, so {@code **} crosses directory
 *       boundaries as users expect from Ant/Gradle conventions.</li>
 *   <li>Literal entries (e.g. {@code "api-test/src/test/java"}) keep
 *       the boundary-aware prefix semantics the README has documented
 *       since v1: the entry matches only when it sits at the start of
 *       the path or is preceded by {@code '/'}, so {@code "api-test"}
 *       never claims {@code "api-test-utils/..."}.</li>
 * </ul>
 *
 * <p>Mixing both shapes in the same list is supported — this is what
 * lets existing adopters migrate at their own pace without the plugin
 * ever silently losing coverage.
 *
 * <p>{@code null} and blank entries are dropped quietly: a
 * mis-concatenated list literal on the Gradle side is not worth
 * failing a build over, and the {@code --explain} hint already
 * surfaces the more likely "configured but nothing matched" failure.
 *
 * <p>Malformed glob entries fail the build at config-time with a
 * targeted {@link IllegalStateException} naming the offending key,
 * index, and pattern — the raw
 * {@link java.util.regex.PatternSyntaxException} the JVM throws is
 * useless on its own because it does not mention which config entry
 * caused the regex error, and by the time it surfaces the user is
 * several stack-trace frames deep in the engine.
 */
public final class OutOfScopeMatchers {

    private OutOfScopeMatchers() {}

    /**
     * Compile the raw {@code dirs} strings into matchers, labelling
     * any malformed glob errors with {@code configKey}.
     *
     * @param dirs raw entries from {@code build.gradle}; may be empty
     * @param configKey the user-facing name of the list being compiled
     *                  ({@code "outOfScopeTestDirs"} etc.) so error
     *                  messages point at the right knob
     * @return immutable list of predicates; empty when {@code dirs} is
     *         empty or contains only null/blank entries
     */
    public static List<Predicate<String>> compile(List<String> dirs, String configKey) {
        if (dirs == null || dirs.isEmpty()) return List.of();
        List<Predicate<String>> matchers = new ArrayList<>(dirs.size());
        for (int i = 0; i < dirs.size(); i++) {
            String dir = dirs.get(i);
            if (dir == null || dir.isBlank()) continue;
            String normalizedDir = dir.replace('\\', '/');
            if (hasGlobMetachar(normalizedDir)) {
                PathMatcher pm;
                try {
                    pm = FileSystems.getDefault().getPathMatcher("glob:" + normalizedDir);
                } catch (IllegalArgumentException e) {
                    // Covers both PatternSyntaxException and raw IAE shapes
                    // from the default glob compiler; both inherit from IAE.
                    throw new IllegalStateException(
                            "Affected Tests: invalid glob at " + configKey + "[" + i
                                    + "]: '" + dir + "' — " + e.getMessage(), e);
                }
                matchers.add(path -> {
                    try {
                        return pm.matches(java.nio.file.Path.of(path));
                    } catch (InvalidPathException e) {
                        // A changed file whose path contains characters the
                        // platform refuses to parse (mostly a Windows-in-git
                        // edge case) can't match any glob — fail closed so
                        // the engine routes it through the unmapped bucket
                        // instead of pretending the glob bit.
                        return false;
                    }
                });
            } else {
                // The `bare` form is the directory without any trailing slash;
                // `prefix` is the same thing with a trailing slash appended.
                //
                // The matcher accepts four shapes, all of which represent the
                // same logical "path is under this directory" relationship:
                //
                //   path == bare            — caller handed us the directory
                //                             itself (ProjectIndex does this
                //                             after Path.relativize, which
                //                             never emits a trailing slash).
                //                             Pre-fix this case silently
                //                             leaked: an entry exactly
                //                             equal to a resolved source
                //                             dir was not filtered.
                //   path.startsWith(prefix) — path is a descendant file or
                //                             subdirectory.
                //   path ends with bare     — path *is* the directory, but
                //                             nested under a parent (covers
                //                             the relativized-from-project-
                //                             root case where the entry is
                //                             a sub-path like
                //                             "sub/src/main/java").
                //   path.contains("/"+prefix) — legacy shape preserved for
                //                               parity with v1 behaviour.
                String bare = normalizedDir.endsWith("/")
                        ? normalizedDir.substring(0, normalizedDir.length() - 1)
                        : normalizedDir;
                String prefix = bare + "/";
                matchers.add(path ->
                        path.equals(bare)
                                || path.startsWith(prefix)
                                || path.endsWith("/" + bare)
                                || path.contains("/" + prefix));
            }
        }
        return List.copyOf(matchers);
    }

    /**
     * Fast-path "does any compiled matcher claim this path?" check.
     * Works on an already-normalised path string so every caller
     * agrees on the slash character.
     */
    public static boolean matchesAny(String normalizedPath, List<Predicate<String>> matchers) {
        if (matchers.isEmpty()) return false;
        for (Predicate<String> matcher : matchers) {
            if (matcher.test(normalizedPath)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} iff the entry contains at least one glob
     * metacharacter. Only the opening forms {@code [} and {@code {}
     * are checked because balanced closers can't appear in a string
     * that didn't already open one — and a user typing a literal
     * directory name is overwhelmingly more likely to write an
     * unmatched {@code ]} or {@code &#125;} inside a filename than
     * they are to mean "glob". Promoting unbalanced closers to glob
     * compilation would only convert "typo-in-literal" into a
     * build-breaking glob syntax error. {@code *} and {@code ?} have
     * no balanced counterpart, so they are unconditional giveaways.
     */
    private static boolean hasGlobMetachar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == '{') return true;
        }
        return false;
    }
}
