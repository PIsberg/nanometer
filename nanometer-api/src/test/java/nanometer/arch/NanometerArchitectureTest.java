package nanometer.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * ArchUnit tests verifying architectural integrity and decoupling of Nanometer modules.
 */
public class NanometerArchitectureTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    public static void setup() {
        importedClasses = new ClassFileImporter().importPackages("nanometer");
    }

    @Test
    public void modelShouldNotDependOnUpperLayers() {
        ArchRule rule = noClasses().that().resideInAPackage("nanometer.model..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "nanometer.storage..",
                        "nanometer.agent..",
                        "nanometer.server..",
                        "nanometer.discovery.."
                );
        rule.check(importedClasses);
    }

    @Test
    public void bufferShouldBeIndependentOfStorageAndServer() {
        ArchRule rule = noClasses().that().resideInAPackage("nanometer.buffer..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "nanometer.storage..",
                        "nanometer.server.."
                );
        rule.check(importedClasses);
    }

    @Test
    public void noCyclicPackageDependencies() {
        ArchRule rule = slices().matching("nanometer.(*)..")
                .should().beFreeOfCycles();
        rule.check(importedClasses);
    }

    @Test
    public void recordsShouldBeInModelPackage() {
        ArchRule rule = classes().that().areRecords()
                .should().resideInAnyPackage(
                        "nanometer.model..",
                        "nanometer.graph..",
                        "nanometer.discovery..",
                        "nanometer.system..",
                        "nanometer.trace..",
                        "nanometer.anomaly..",
                        "nanometer.profiling..",
                        "nanometer.sampling..",
                        "nanometer.storage.."
                );
        rule.check(importedClasses);
    }
}
