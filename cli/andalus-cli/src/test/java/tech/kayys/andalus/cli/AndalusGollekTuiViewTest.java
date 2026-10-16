package tech.kayys.andalus.cli;

// import tech.kayys.andalus.tui.Component; // removed: class no longer exists
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import tech.kayys.andalus.client.AndalusAqliSdk;
import tech.kayys.andalus.client.AndalusWorkbenchModel;

import static org.assertj.core.api.Assertions.assertThat;

@Disabled("AndalusAqliTuiView and Component have been removed; test needs migration")
class AndalusAqliTuiViewTest {

    @Test
    void buildsElementTreeFromWorkbenchModel() {
        var sdk = AndalusAqliSdk.local();
        AndalusWorkbenchModel model = sdk.workbench();
        var workspace = sdk.inspectWorkspace(new tech.kayys.andalus.client.WorkspaceInspectionRequest(".", 80, false));
        // AndalusAqliTuiView view = new AndalusAqliTuiView();
        // Component element = view.render(model, workspace);
        // assertThat(element).isNotNull();
    }
}
