package tech.kayys.andalus.cli;

import tech.kayys.andalus.client.ComponentStatus;
import tech.kayys.andalus.client.ProductSurface;
import tech.kayys.andalus.client.AndalusPlatformStatus;
import tech.kayys.andalus.client.AndalusWorkbenchModel;
import tech.kayys.andalus.client.WorkspaceSnapshot;

final class PlainWorkbenchRenderer implements AndalusWorkbenchRenderer<String> {

    @Override
    public String render(AndalusWorkbenchModel model, WorkspaceSnapshot workspace) {
        AndalusPlatformStatus status = model.status();
        StringBuilder out = new StringBuilder();
        out.append("Andalus Workbench\n");
        out.append("Version: ").append(status.version()).append('\n');
        out.append("Boundary: Andalus agent platform above Aqli inference/training and Gamelan workflows\n\n");

        out.append("Components\n");
        appendComponent(out, status.aqli());
        appendComponent(out, status.gamelan());
        appendComponent(out, status.agentCore());
        appendComponent(out, status.rag());
        appendComponent(out, status.mcp());
        out.append("  Skills: ").append(status.activeSkills()).append(" active\n\n");

        out.append("Product Surfaces\n");
        for (ProductSurface surface : model.productSurfaces()) {
            out.append("  - ")
                    .append(surface.name())
                    .append(" [")
                    .append(surface.id())
                    .append("]: ")
                    .append(surface.role())
                    .append('\n');
        }
        out.append('\n');

        appendSection(out, "Command Palette", model.commandPalette());
        appendSection(out, "Next Actions", model.nextActions());
        appendSection(out, "Platform Notes", status.notes());
        return out.toString();
    }

    private void appendComponent(StringBuilder out, ComponentStatus component) {
        out.append("  - ")
                .append(component.name())
                .append(": ")
                .append(component.state())
                .append(" (")
                .append(component.healthPercent())
                .append("%) - ")
                .append(component.role())
                .append('\n');
    }

    private void appendSection(StringBuilder out, String title, Iterable<String> lines) {
        out.append(title).append('\n');
        for (String line : lines) {
            out.append("  - ").append(line).append('\n');
        }
        out.append('\n');
    }
}
