package tech.kayys.andalus.cli;

import tech.kayys.andalus.client.ComponentStatus;
import tech.kayys.andalus.client.AndalusPlatformStatus;

final class AndalusStatusTextFormat {

    private AndalusStatusTextFormat() {
    }

    static String text(AndalusPlatformStatus status) {
        StringBuilder output = new StringBuilder();
        output.append(status.productName())
                .append(" ")
                .append(status.version())
                .append(System.lineSeparator());
        output.append("Boundary: Andalus is the agentic platform; Aqli and Gamelan stay external engines.")
                .append(System.lineSeparator())
                .append(System.lineSeparator());
        append(output, status.aqli());
        append(output, status.gamelan());
        append(output, status.agentCore());
        append(output, status.rag());
        append(output, status.mcp());
        output.append("Skills: ").append(status.activeSkills()).append(" active").append(System.lineSeparator());
        if (!status.notes().isEmpty()) {
            output.append(System.lineSeparator()).append("Notes:").append(System.lineSeparator());
            status.notes().forEach(note -> output.append("- ").append(note).append(System.lineSeparator()));
        }
        return output.toString();
    }

    private static void append(StringBuilder output, ComponentStatus component) {
        output.append(component.name())
                .append(": ")
                .append(component.state())
                .append(" - ")
                .append(component.role());
        if (!component.endpoint().isBlank()) {
            output.append(" [").append(component.endpoint()).append("]");
        }
        output.append(System.lineSeparator());
    }
}
