package tech.kayys.andalus.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import tech.kayys.andalus.memory.model.Memory;
import tech.kayys.andalus.memory.service.VectorMemoryStore;
import tech.kayys.andalus.memory.service.InMemoryVectorStore;
import tech.kayys.andalus.vector.faiss.FaissVectorStore;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

final class AndalusMemoryCommands {

    private AndalusMemoryCommands() {
    }

    @Command(
            name = "memory",
            description = "Manage long-term vector memory.",
            mixinStandardHelpOptions = true,
            subcommands = {
                    MemoryCommand.ExportCommand.class,
                    MemoryCommand.ImportCommand.class,
                    MemoryCommand.ClearCommand.class,
                    MemoryCommand.RemoveCommand.class,
                    MemoryCommand.ListCommand.class
            })
    static final class MemoryCommand implements Callable<Integer> {
        @ParentCommand
        AndalusAqliCli parent;

        @Override
        public Integer call() {
            parent.run();
            return 0;
        }

        private static VectorMemoryStore getStore() {
            return new InMemoryVectorStore();
        }

        @Command(name = "export", description = "Export all memory to a markdown file.")
        static final class ExportCommand implements Callable<Integer> {
            @ParentCommand
            MemoryCommand parent;

            @Option(names = {"-o", "--output"}, description = "Output file path", required = true)
            String outputPath;

            @Override
            public Integer call() {
                try {
                    VectorMemoryStore store = getStore();
                    List<Memory> allMemories = store.searchByFilter(Map.of()).await().indefinitely();
                    
                    StringBuilder sb = new StringBuilder();
                    sb.append("# Memory Export\n\n");
                    
                    String[] categories = {"Instructions", "Identity", "Career", "Projects", "Preferences"};
                    for (String cat : categories) {
                        sb.append("## ").append(cat).append("\n");
                        allMemories.stream()
                            .filter(m -> cat.equalsIgnoreCase((String) m.getMetadata().get("category")))
                            .forEach(m -> {
                                String dateStr = m.getMetadata().containsKey("date") ? (String) m.getMetadata().get("date") : "unknown";
                                sb.append("- [").append(dateStr).append("] ").append(m.getContent()).append("\n");
                            });
                        sb.append("\n");
                    }
                    
                    Path path = Paths.get(outputPath);
                    Files.writeString(path, sb.toString());
                    System.out.println("Exported " + allMemories.size() + " memories to " + path.toAbsolutePath());
                    return 0;
                } catch (Exception e) {
                    System.err.println("Export failed: " + e.getMessage());
                    return 1;
                }
            }
        }

        @Command(name = "import", description = "Import memory from a markdown file.")
        static final class ImportCommand implements Callable<Integer> {
            @ParentCommand
            MemoryCommand parent;

            @Parameters(index = "0", description = "Markdown file path to import")
            String inputPath;

            @Option(names = {"--overwrite"}, description = "Clear existing memory before importing")
            boolean overwrite;

            @Override
            public Integer call() {
                try {
                    VectorMemoryStore store = getStore();
                    if (overwrite) {
                        store.deleteNamespace("default").await().indefinitely();
                        System.out.println("Cleared existing memory.");
                    }
                    
                    Path path = Paths.get(inputPath);
                    List<String> lines = Files.readAllLines(path);
                    
                    String currentCategory = "Preferences";
                    int count = 0;
                    
                    for (String line : lines) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("# ")) continue;
                        
                        if (line.startsWith("## ")) {
                            currentCategory = line.substring(3).trim();
                            continue;
                        }
                        
                        if (line.startsWith("- ")) {
                            String content = line.substring(2);
                            String date = "unknown";
                            if (content.startsWith("[")) {
                                int closeBracket = content.indexOf("]");
                                if (closeBracket > 0) {
                                    date = content.substring(1, closeBracket);
                                    content = content.substring(closeBracket + 1).trim();
                                }
                            }
                            
                            Memory mem = Memory.builder()
                                    .content(content)
                                    .type(tech.kayys.andalus.memory.model.MemoryType.SEMANTIC)
                                    .metadata(Map.of("category", currentCategory, "date", date))
                                    .build();
                            
                            store.store(mem).await().indefinitely();
                            count++;
                        }
                    }
                    
                    System.out.println("Imported " + count + " memories from " + path.toAbsolutePath());
                    return 0;
                } catch (Exception e) {
                    System.err.println("Import failed: " + e.getMessage());
                    return 1;
                }
            }
        }

        @Command(name = "clear", description = "Clear memory by category or all.")
        static final class ClearCommand implements Callable<Integer> {
            @ParentCommand
            MemoryCommand parent;

            @Parameters(index = "0", description = "Category name or 'all'")
            String category;

            @Override
            public Integer call() {
                try {
                    VectorMemoryStore store = getStore();
                    if ("all".equalsIgnoreCase(category)) {
                        store.deleteNamespace("default").await().indefinitely();
                        System.out.println("Cleared all memories.");
                    } else {
                        List<Memory> cats = store.searchByFilter(Map.of("category", category)).await().indefinitely();
                        List<String> ids = new ArrayList<>();
                        for (Memory m : cats) ids.add(m.getId());
                        if (!ids.isEmpty()) {
                            store.delete(ids.get(0)); // We need delete batch but delete takes String. Wait, delete takes List<String> in VectorStore. Wait VectorMemoryStore delete takes String. We will loop.
                            for (String id : ids) store.delete(id).await().indefinitely();
                        }
                        System.out.println("Cleared " + ids.size() + " memories in category: " + category);
                    }
                    return 0;
                } catch (Exception e) {
                    System.err.println("Clear failed: " + e.getMessage());
                    return 1;
                }
            }
        }
        
        @Command(name = "remove", description = "Remove a specific memory by ID.")
        static final class RemoveCommand implements Callable<Integer> {
            @ParentCommand
            MemoryCommand parent;

            @Parameters(index = "0", description = "Memory ID to remove")
            String id;

            @Override
            public Integer call() {
                try {
                    VectorMemoryStore store = getStore();
                    store.delete(id).await().indefinitely();
                    System.out.println("Removed memory: " + id);
                    return 0;
                } catch (Exception e) {
                    System.err.println("Remove failed: " + e.getMessage());
                    return 1;
                }
            }
        }
        
        @Command(name = "list", description = "List all memories by category.")
        static final class ListCommand implements Callable<Integer> {
            @ParentCommand
            MemoryCommand parent;

            @Override
            public Integer call() {
                try {
                    VectorMemoryStore store = getStore();
                    List<Memory> allMemories = store.searchByFilter(Map.of()).await().indefinitely();
                    if (allMemories.isEmpty()) {
                        System.out.println("No memories found.");
                        return 0;
                    }

                    Map<String, List<Memory>> byCategory = new java.util.HashMap<>();
                    for (Memory m : allMemories) {
                        String cat = m.getMetadata().containsKey("category") ? (String) m.getMetadata().get("category") : "Unknown";
                        byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(m);
                    }

                    for (Map.Entry<String, List<Memory>> entry : byCategory.entrySet()) {
                        System.out.println("\n=== " + entry.getKey() + " ===");
                        for (Memory m : entry.getValue()) {
                            String dateStr = m.getMetadata().containsKey("date") ? (String) m.getMetadata().get("date") : "unknown";
                            System.out.println("ID: " + m.getId());
                            System.out.println("Date: " + dateStr);
                            System.out.println("Content: " + m.getContent());
                            System.out.println("----------------------------------------");
                        }
                    }
                    return 0;
                } catch (Exception e) {
                    System.err.println("List failed: " + e.getMessage());
                    return 1;
                }
            }
        }
    }
}
