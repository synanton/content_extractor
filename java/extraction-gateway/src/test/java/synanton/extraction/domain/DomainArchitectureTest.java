package synanton.extraction.domain;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class DomainArchitectureTest {

    private static final JavaClasses DOMAIN = new ClassFileImporter()
            .importPackages("synanton.extraction.domain");

    @Test
    void domainShouldNotDependOnForbiddenLibraries() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("synanton.extraction.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.google.protobuf..",
                        "io.grpc..",
                        "java.sql..",
                        "javax.sql..",
                        "org.springframework..",
                        "org.apache.tika..",
                        "io.github.opendataloader..",
                        "org.opendataloader..")
                .because("domain must stay free of transport, persistence, and adapter libraries");

        rule.check(DOMAIN);
    }
}
