package nanometer.it;

import net.bytebuddy.jar.asm.ClassReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the layout invariants of the shaded uber-JAR.
 *
 * <p>Shade rewrites the bytecode inside {@code META-INF/versions/N/} entries to the relocated
 * package but cannot rewrite their entry paths, producing entries whose declared class name
 * disagrees with their location. Those entries were harmless only because the manifest carries no
 * {@code Multi-Release} attribute, so the JVM ignored them. These tests keep that from silently
 * becoming a {@code NoClassDefFoundError} later.
 */
@DisplayName("Shaded JAR layout")
class ShadedJarLayoutIT {

    private static Path shadedJar;

    @BeforeAll
    static void locateArtifact() {
        String value = System.getProperty("nanometer.shaded.jar");
        assertNotNull(value, "system property nanometer.shaded.jar is not set; "
                + "this test must run under maven-failsafe-plugin");
        shadedJar = Path.of(value);
        assertTrue(Files.isRegularFile(shadedJar), "shaded JAR not found: " + shadedJar);
    }

    @Test
    @DisplayName("every class entry's path matches the class it declares")
    void classPathsMatchDeclaredNames() throws IOException {
        List<String> mismatches = new ArrayList<>();

        try (JarFile jar = new JarFile(shadedJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.endsWith("module-info.class")) {
                    continue;
                }
                String declared;
                try (InputStream in = jar.getInputStream(entry)) {
                    declared = new ClassReader(in.readAllBytes()).getClassName();
                }
                String path = stripVersionPrefix(name);
                String expected = path.substring(0, path.length() - ".class".length());
                if (!expected.equals(declared)) {
                    mismatches.add(name + " declares " + declared);
                }
            }
        }

        assertEquals(List.of(), mismatches,
                "shaded JAR contains class entries whose path disagrees with the class they "
                        + "declare; loading one raises NoClassDefFoundError \"wrong name\"");
    }

    @Test
    @DisplayName("carries no multi-release entries, matching its non-multi-release manifest")
    void carriesNoMultiReleaseEntries() throws IOException {
        List<String> versioned = new ArrayList<>();
        Manifest manifest;

        try (JarFile jar = new JarFile(shadedJar.toFile())) {
            manifest = jar.getManifest();
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF/versions/") && !entry.isDirectory()) {
                    versioned.add(entry.getName());
                }
            }
        }

        assertNotNull(manifest, "shaded JAR has no manifest");
        String multiRelease = manifest.getMainAttributes().getValue(new Attributes.Name("Multi-Release"));
        assertNull(multiRelease,
                "manifest declares Multi-Release, so META-INF/versions entries would now be loaded; "
                        + "their paths must be relocated before this is enabled");
        assertEquals(List.of(), versioned,
                "shaded JAR carries META-INF/versions entries the JVM will never load because the "
                        + "manifest is not multi-release");
    }

    @Test
    @DisplayName("relocates every shaded dependency class out of its original package")
    void relocationIsComplete() throws IOException {
        List<String> unrelocated = new ArrayList<>();

        try (JarFile jar = new JarFile(shadedJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                if (name.startsWith("net/bytebuddy/") || name.startsWith("org/sqlite/")) {
                    unrelocated.add(name);
                }
            }
        }

        assertEquals(List.of(), unrelocated,
                "shaded JAR leaks unrelocated dependency classes, which defeats the "
                        + "zero-classpath-pollution guarantee");
    }

    @Test
    @DisplayName("declares the agent manifest entries")
    void declaresAgentManifestEntries() throws IOException {
        try (JarFile jar = new JarFile(shadedJar.toFile())) {
            Attributes attributes = jar.getManifest().getMainAttributes();
            assertEquals("nanometer.agent.NanometerAgent", attributes.getValue("Premain-Class"));
            assertEquals("nanometer.agent.NanometerAgent", attributes.getValue("Agent-Class"));
            assertEquals("true", attributes.getValue("Can-Retransform-Classes"));
            assertEquals("true", attributes.getValue("Can-Redefine-Classes"));
        }
    }

    private static String stripVersionPrefix(String entryName) {
        if (!entryName.startsWith("META-INF/versions/")) {
            return entryName;
        }
        int slash = entryName.indexOf('/', "META-INF/versions/".length());
        return slash < 0 ? entryName : entryName.substring(slash + 1);
    }
}
