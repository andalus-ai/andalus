package tech.kayys.andalus.cli;

import org.junit.jupiter.api.Test;

import tech.kayys.andalus.cli.PlainWorkbenchRenderer;
import tech.kayys.andalus.client.AndalusAqliSdk;

import static org.assertj.core.api.Assertions.assertThat;

class PlainWorkbenchRendererTest {

    @Test
    void rendersSameWorkbenchModelWithoutTamboui() {
        var sdk = AndalusAqliSdk.local();
        var workspace = sdk.inspectWorkspace(new tech.kayys.andalus.client.WorkspaceInspectionRequest(".", 80, false));
        String text = new PlainWorkbenchRenderer().render(AndalusAqliSdk.local().workbench(), workspace);

        assertThat(text)
                .contains("Andalus Workbench")
                .contains("Aqli")
                .contains("Gamelan")
                .contains("Command Palette")
                .contains("Product Surfaces")
                .contains("coding-agent");
    }
}
