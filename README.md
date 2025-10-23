# Extract Superclass

## Overview
Extract Superclass creates a shared abstract parent for a set of existing classes. It relies on Eclipse JDT for source rewriting and understands multi-module Maven builds.

## Highlights
- CLI entry point and MCP server transport
- Automatic superclass naming heuristics
- Dependency-aware placement that avoids Maven circular dependencies
- Dry-run mode plus verbose logging for diagnostics

## CLI Usage
```bash
java -jar target/extractsuperclass-cli.jar <projectRoot> --classNames <classNames>
```

### Parameters
- `projectRoot`: one or more module roots; separate multiple paths with commas
- `--classNames, -c`: comma-separated fully qualified class names to include in the extraction
- `--superName, -s`: optional fully qualified name for the new superclass
- `--dryRun, -d`: analyse changes without touching files
- `--verbose, -v`: enable detailed logging
- `--help, -h`: show usage details
- `--version, -V`: show version information

### Examples
```bash
# Basic usage
java -jar target/extractsuperclass-cli.jar /path/to/project --classNames com.example.A,com.example.B

# Specify the superclass name
java -jar target/extractsuperclass-cli.jar /path/to/project --classNames com.example.A,com.example.B --superName com.example.AbstractBase

# Dry run with verbose output
java -jar target/extractsuperclass-cli.jar /path/to/project --classNames com.example.A,com.example.B --dryRun --verbose
```

## Placement Strategy
The tool analyses the Maven dependency graph for all modules that contain the selected classes. It places the new superclass in a module that every target already depends on (directly or transitively), preventing circular dependencies in the resulting build. When `--superName` is omitted the package is inferred from the chosen module, and the file is created under `src/main/java`.

## MCP Server
Run `mvn package` to produce `target/extractsuperclass-mcp-server.jar`, then configure the server as an MCP stdio tool:

```json
{
  "mcpServers": {
    "extract-superclass": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "C:/path/to/extractsuperclass-mcp-server.jar"],
      "timeout": 60000,
      "autoApprove": ["extract_superclass"],
      "env": {"JAVA_OPTS": "-Xmx512m"}
    }
  }
}
```

The `extract_superclass` tool accepts:
- `projectRoot` / `projectRoots`
- `classNames`
- Optional `superQualifiedName` / `superName`
- Optional `dryRun`, `verbose`

Responses include a human-readable summary plus `modifiedFiles` and `executionTimeMs` when available.

## Behaviour Summary
1. If none of the target classes share a superclass, a new abstract class is created and all targets extend it.
2. If exactly one class already extends a superclass, the remaining classes adopt that superclass; no new class is created.
3. If all targets already share the same superclass, it is reused.
4. If targets have incompatible superclasses, the refactoring is skipped.

## Build & Test
```bash
mvn clean compile
mvn test
mvn package
```

## Project Layout
```
src/main/java/com/refactoring/extractsuperclass/
├── ExtractSuperclassCLI.java
├── ExtractSuperclassMcpServer.java
├── ExtractSuperclassRefactorer.java
├── ExtractSuperclassRequest.java
├── ExtractSuperclassResult.java
└── ModuleDependencyManager.java
```

## License
MIT
