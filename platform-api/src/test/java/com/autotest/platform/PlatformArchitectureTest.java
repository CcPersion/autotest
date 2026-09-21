package com.autotest.platform;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@AnalyzeClasses(packages = "com.autotest.platform")
class PlatformArchitectureTest {

    @ArchTest
    static final ArchRule platformDoesNotDependOnRunner = noClasses()
            .that().resideInAnyPackage("com.autotest.platform..")
            .should().dependOnClassesThat().resideInAnyPackage("com.autotest.runner..");

    @Test
    void platformModuleIsPresentForArchitectureScanning() {
        assertNotNull(PlatformModule.class);
    }
}
