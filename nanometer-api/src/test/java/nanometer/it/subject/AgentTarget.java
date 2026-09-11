package nanometer.it.subject;

/**
 * The instrumented subject for {@code ShadedAgentIT}.
 *
 * <p>Lives in its own package because the agent is started with this package as its prefix. The
 * driver that calls into it deliberately sits outside that package, so {@link #parent()} really is
 * the outermost instrumented frame and must therefore record no parent span.
 *
 * <p>The package is deliberately not named {@code target}: the repository's .gitignore excludes
 * {@code target/}, which would silently keep this file out of the commit and break the build for
 * everyone but the author.
 */
public final class AgentTarget {

    /** Instrumented, outermost: calls {@link #child()} and returns normally. */
    public String parent() {
        return child() + "-parent";
    }

    /** Instrumented, nested inside {@link #parent()}, so it must record a parent span id. */
    public String child() {
        return "child";
    }

    /** Instrumented: always throws, so its event must carry the exception type. */
    public void boom() {
        throw new IllegalStateException("expected");
    }
}
