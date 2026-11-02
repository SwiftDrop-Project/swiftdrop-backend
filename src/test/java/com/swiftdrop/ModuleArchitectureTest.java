package com.swiftdrop;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the module boundaries (no Docker needed). Fails when a module uses another module's {@code internal}
 * package, or when two modules depend on each other (a cycle). Also writes the module diagrams and canvases to
 * {@code target/spring-modulith-docs}.
 */
class ModuleArchitectureTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(SwiftdropBackendApplication.class);

    @Test
    void modulesAreTheOnesFromTheArchitectureDiagram() {
        assertThat(MODULES.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .containsExactlyInAnyOrder("shared", "infrastructure", "identity", "merchants", "pricing", "delivery",
                        "dispatch", "reporting", "tracking", "notifications", "webhooks", "outbox");
    }

    @Test
    void moduleBoundariesAreRespected() {
        MODULES.verify();
    }

    @Test
    void writeModuleDocumentation() {
        new Documenter(MODULES)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases();
    }
}
