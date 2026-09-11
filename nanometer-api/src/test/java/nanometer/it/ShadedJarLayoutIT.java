package nanometer.it;

import net.bytebuddy.jar.asm.ClassReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    @DisplayName("is a coherent multi-release JAR: the flag is set and versioned entries exist")
    void isACoherentMultiReleaseJar() throws IOException {
        List<String> versionedClasses = new ArrayList<>();
        List<String> moduleInfos = new ArrayList<>();
        Manifest manifest;

        try (JarFile jar = new JarFile(shadedJar.toFile())) {
            manifest = jar.getManifest();
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("META-INF/versions/")) {
                    continue;
                }
                if (name.endsWith("module-info.class")) {
                    moduleInfos.add(name);
                } else if (name.endsWith(".class")) {
                    versionedClasses.add(name);
                }
            }
        }

        assertNotNull(manifest, "shaded JAR has no manifest");
        assertEquals("true",
                manifest.getMainAttributes().getValue(new Attributes.Name("Multi-Release")),
                "versioned entries are shipped but the manifest does not declare Multi-Release, so "
                        + "the JVM will silently ignore every one of them");
        assertNotEquals(List.of(), versionedClasses,
                "the manifest declares Multi-Release but there are no versioned classes, so the "
                        + "flag is claiming something the JAR does not provide");
        assertEquals(List.of(), moduleInfos,
                "a shaded uber-JAR must not ship module-info; its packages are merged from many "
                        + "modules and the descriptor cannot be correct");
    }

    /**
     * The reason the versioned entries are carried at all: Byte Buddy ships its JDK ClassFile API
     * bridge only under {@code META-INF/versions/24}, and uses it in place of its bundled ASM on a
     * new enough JVM.
     *
     * <p>Asserts both directions rather than skipping below 24, so the test always proves
     * something: the bridge resolves where it should and is correctly invisible where it should
     * not be.
     */
    @Test
    @DisplayName("exposes the relocated JDK ClassFile API bridge exactly on JDK 24 and newer")
    void exposesTheClassFileApiBridgeOnSupportedRuntimes() throws IOException {
        String bridge = "nanometer.shaded.bytebuddy.jar.asmjdkbridge.JdkClassReader";
        int runtime = Runtime.version().feature();

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{shadedJar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {

            if (runtime >= 24) {
                Class<?> loaded;
                try {
                    loaded = Class.forName(bridge, false, loader);
                } catch (ClassNotFoundException e) {
                    throw new AssertionError("JDK " + runtime + " should see the relocated "
                            + "ClassFile API bridge, but it did not resolve. Either the "
                            + "Multi-Release manifest entry is missing or the versioned entry "
                            + "paths were not relocated to match the classes they declare.", e);
                }
                assertEquals(bridge, loaded.getName());
            } else {
                assertThrows(ClassNotFoundException.class,
                        () -> Class.forName(bridge, false, loader),
                        "JDK " + runtime + " is below the bridge's META-INF/versions/24 floor, so "
                                + "it must not resolve; if it does, the versioned classes have "
                                + "leaked into the base JAR where older JVMs would load them");
            }
        }
    }

    @Test
    @DisplayName("relocates Byte Buddy out of its original package")
    void relocationIsComplete() throws IOException {
        List<String> unrelocated = new ArrayList<>();

        try (JarFile jar = new JarFile(shadedJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                // Versioned entries count too: their paths are relocated separately from the base
                // ones, so a relocation that covers only the base JAR would slip through here.
                if (stripVersionPrefix(name).startsWith("net/bytebuddy/")) {
                    unrelocated.add(name);
                }
            }
        }

        assertEquals(List.of(), unrelocated,
                "Byte Buddy must be relocated: a host running its own agent would otherwise meet "
                        + "two copies of it");
    }

    @Test
    @DisplayName("leaves the JNI SQLite driver in its original package")
    void sqliteIsDeliberatelyNotRelocated() throws IOException {
        boolean originalPackagePresent = false;
        boolean relocatedPackagePresent = false;

        try (JarFile jar = new JarFile(shadedJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("org/sqlite/")) {
                    originalPackagePresent = true;
                } else if (name.startsWith("nanometer/shaded/sqlite/")) {
                    relocatedPackagePresent = true;
                }
            }
        }

        assertTrue(originalPackagePresent, "the SQLite driver is missing from the uber-JAR");
        // sqlite-jdbc is a JNI library: its native binary exports Java_org_sqlite_core_NativeDB_*
        // and resolves its classes by that name. Relocating the Java side made the JVM look for
        // symbols that do not exist, and the first query died with
        // NoClassDefFoundError: org/sqlite/core/NativeDB.
        assertFalse(relocatedPackagePresent,
                "org.sqlite was relocated; a native library cannot be renamed by a bytecode "
                        + "rewriter and the embedded store stops working");
    }

    @Test
    @DisplayName("points the JDBC service entry at a class that exists in the JAR")
    void serviceEntriesNameLoadableClasses() throws IOException {
        try (JarFile jar = new JarFile(shadedJar.toFile())) {
            JarEntry entry = jar.getJarEntry("META-INF/services/java.sql.Driver");
            assertNotNull(entry, "no JDBC driver service entry, so DriverManager finds no driver");

            String declared;
            try (InputStream in = jar.getInputStream(entry)) {
                declared = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }

            // Relocation rewrites class files but not service files unless shade is told to, which
            // left this naming org.sqlite.JDBC after that class had been renamed. The symptom was
            // "No suitable driver found for jdbc:sqlite:" from a JAR that contained the driver.
            assertNotNull(jar.getJarEntry(declared.replace('.', '/') + ".class"),
                    "service entry names " + declared + ", which is not in this JAR");
        }
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

    @Test
    @DisplayName("publishes a POM that still carries the module's dependencies")
    void doesNotPublishADependencyReducedPom() {
        String basedir = System.getProperty("nanometer.module.basedir");
        assertNotNull(basedir, "nanometer.module.basedir is not set");

        // The uber-JAR is attached under the "all" classifier, so the main artifact is still the
        // thin nanometer-api jar and needs its dependencies. Shade's dependency-reduced POM strips
        // every shaded dependency from the published POM, and a consumer then resolves a jar with
        // no transitive dependencies and dies on NoClassDefFoundError for
        // nanometer/buffer/MetricRingBuffer. A reactor build never notices, because sibling modules
        // resolve from the reactor rather than from the repository.
        assertFalse(Files.exists(Path.of(basedir, "dependency-reduced-pom.xml")),
                "shade is publishing a dependency-reduced POM again; set "
                        + "createDependencyReducedPom to false");
    }

    private static String stripVersionPrefix(String entryName) {
        if (!entryName.startsWith("META-INF/versions/")) {
            return entryName;
        }
        int slash = entryName.indexOf('/', "META-INF/versions/".length());
        return slash < 0 ? entryName : entryName.substring(slash + 1);
    }
}