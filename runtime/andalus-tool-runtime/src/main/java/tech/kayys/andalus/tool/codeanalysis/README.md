# Andalus Code Analysis - Import Statement Validator

## Overview

This package provides code analysis utilities for detecting broken Java import statements. It helps catch issues like:

- Typos in import statements
- Imports from wrong packages (e.g., `tech.kayys.alkhawarizm` when should be `tech.kayys.aqli`)
- Imports of non-existent classes
- Package/class name case sensitivity issues

## Problem Solved

This feature was created in response to a real issue where the Aqli project had many Java files importing `tech.kayys.alkhawarizm.tokenizer.spi.Tokenizer`, but the `Tokenizer` interface actually existed in `tech.kayys.aqli.tokenizer.spi.Tokenizer`. This caused compilation failures until all the imports were manually fixed.

## Usage

### As a Service (for Andalus Agents)

```java
import tech.kayys.andalus.tool.codeanalysis.JavaImportValidationService;
import tech.kayys.andalus.tool.codeanalysis.ImportValidationIssue;
import java.nio.file.Path;
import java.util.List;

// Create a service for a specific codebase
JavaImportValidationService service = new JavaImportValidationService(
    Path.of("/path/to/codebase")
);

// Validate all Java files
List<ImportValidationIssue> issues = service.validateCodebase();

// Output issues
issues.forEach(System.out::println);

// Get only errors
List<ImportValidationIssue> errors = service.getErrors();

// Generate a formatted report
String report = service.generateReport();
System.out.println(report);
```

### As a CLI Command

```bash
# Check imports in current directory
gamelan diagnostic import-check

# Check imports in a specific project
gamelan diagnostic import-check /path/to/project

# Get JSON output
gamelan diagnostic import-check /path/to/project --json

# Only show errors (not warnings)
gamelan diagnostic import-check /path/to/project --severity ERROR

# Quiet mode (only show issues, not summary)
gamelan diagnostic import-check /path/to/project --quiet
```

### Programmatic API

```java
import tech.kayys.andalus.tool.codeanalysis.ImportStatementValidator;
import tech.kayys.andalus.tool.codeanalysis.ImportValidationIssue;

// Create validator
ImportStatementValidator validator = new ImportStatementValidator(
    Path.of("/path/to/codebase")
);

// Validate a single file
List<ImportValidationIssue> fileIssues = validator.validateContent(
    Path.of("/path/to/MyClass.java"),
    "package tech.kayys;\nimport tech.kayys.alkhawarizm.tokenizer.spi.Tokenizer;\n..."
);

// Validate a directory
List<ImportValidationIssue> dirIssues = validator.validateDirectory(
    Path.of("/path/to/module")
);
```

## Features

### Automatic Fix Suggestions

The validator attempts to suggest fixes for common issues:

- If a class exists in a different package, it suggests the correct import
- Handles common package prefix replacements (e.g., `tech.kayys.alkhawarizm` ↔ `tech.kayys.aqli`)
- Detects when a class exists but in a different location

### Severity Levels

- **ERROR**: Critical issues that will prevent compilation
- **WARNING**: Potential issues that may cause problems
- **INFO**: Informational notes about code quality

### Caching

The validator builds an index of all classes in the codebase for fast validation. Call `clearCache()` if the codebase changes between validations.

## Integration with Andalus

This feature integrates with the Andalus coding agent platform:

1. **Automatic Detection**: Andalus agents can use this to validate code before execution
2. **CI/CD Integration**: The CLI command can be used in build pipelines
3. **Migration Assistance**: Helps identify and fix issues during code migrations

## Implementation Details

### Class Indexing

The validator builds an in-memory index of:
- All Java packages in the codebase
- All classes/interfaces/enums in each package
- Mapping from full class names to their actual packages

### Pattern Matching

- Extracts package declarations using regex
- Extracts import statements using regex
- Extracts class names using regex
- Handles wildcard imports (skips validation for these)
- Handles static imports (skips validation for now)

### Performance

- O(n) complexity where n is the number of Java files
- Index building is done once per validation run
- Subsequent validations use the cached index

## Future Enhancements

- [ ] Support for Maven/Gradle dependency resolution
- [ ] Validation against external libraries
- [ ] Fix mode to automatically correct imports
- [ ] Integration with IDE plugins
- [ ] Support for other languages (Python, TypeScript, etc.)
- [ ] Detection of unused imports
- [ ] Circular dependency detection

## License

Apache License 2.0

## Copyright

Copyright (c) 2026 Kayys.tech
