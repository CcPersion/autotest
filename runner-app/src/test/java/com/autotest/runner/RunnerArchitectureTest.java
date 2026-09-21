package com.autotest.runner;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@AnalyzeClasses(packages = "com.autotest.runner")
class RunnerArchitectureTest {

    @ArchTest
    static final ArchRule runnerDoesNotDependOnPlatform = noClasses()
            .that().resideInAnyPackage("com.autotest.runner..")
            .should().dependOnClassesThat().resideInAnyPackage("com.autotest.platform..");

    @ArchTest
    static final ArchRule runnerDoesNotDependOnSpring = noClasses()
            .that().resideInAnyPackage("com.autotest.runner..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..");

    @Test
    void runnerModuleIsPresentForArchitectureScanning() {
        assertNotNull(RunnerModule.class);
    }
}
