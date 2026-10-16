package tech.kayys.andalus.cli;

import tech.kayys.andalus.boundry.AndalusSdkBoundary;

import java.util.List;

/**
 * Text renderer for SDK ownership boundaries shown by the Andalus CLI.
 */
final class AndalusSdkBoundaryTextFormat {

    private static final String NL = System.lineSeparator();

    private AndalusSdkBoundaryTextFormat() {
    }

    static String text(List<AndalusSdkBoundary> boundaries) {
        StringBuilder output = new StringBuilder();
        output.append("Andalus SDK boundaries").append(NL);
        output.append("Root package: tech.kayys.andalus.aqli.sdk").append(NL).append(NL);
        for (AndalusSdkBoundary boundary : CliLists.copy(boundaries)) {
            appendSummary(output, boundary);
        }
        return output.toString();
    }

    static String detailText(AndalusSdkBoundary boundary) {
        StringBuilder output = new StringBuilder();
        output.append("Andalus SDK boundary").append(NL);
        appendSummary(output, boundary);
        CliText.appendListLine(output, "class prefixes", boundary.classPrefixes());
        CliText.appendListLine(output, "contract schemas", boundary.contractSchemas());
        CliText.appendListLine(output, "depends on", boundary.dependsOn());
        return output.toString();
    }

    private static void appendSummary(StringBuilder output, AndalusSdkBoundary boundary) {
        output.append(boundary.name())
                .append(" (")
                .append(boundary.id())
                .append(")")
                .append(NL);
        output.append("  package: ").append(boundary.intendedPackage()).append(NL);
        output.append("  owns: ").append(boundary.responsibility()).append(NL);
    }
}
