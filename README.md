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

MCP Server

Run mvn package to produce target/extractsuperclass-mcp-server.jar, then use the provided PowerShell wrapper script to set the Java 21 runtime environment and connect to MCP:

$env:EXTRACT_SUPERCLASS_JAVA_HOME = 'C:\Program Files\Java\jdk-21'  # Example path, can point to any JDK 21  
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File scripts/run-mcp-server.ps1  


The script will attempt to use EXTRACT_SUPERCLASS_JAVA_HOME, JAVA_HOME_21, and JAVA_HOME in order, checking if java -version matches 21; if it can't find a suitable JDK, it will throw an error. Additionally, it will automatically locate target/extractsuperclass-mcp-server.jar, so make sure to run mvn package to generate this file first. If you need to view debug information, you can add -Verbose at the end of the command.

Configuration example (such as in VS Code Kilo Code's mcp_setting.json):

```json
{
  "mcpServers": {
    "extract-superclass": {
      "type": "stdio",
      "command": "powershell.exe",
      "args": [
        "-NoLogo",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        "C:/path/to/Extract_Superclass/scripts/run-mcp-server.ps1"
      ],
      "timeout": 60,
      "autoApprove": ["extract_superclass"],
      "env": {
        "EXTRACT_SUPERCLASS_JAVA_HOME": "C:/Program Files/Java/jdk-21",
        "JAVA_OPTS": "-Xmx512m"
      }
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

Troubleshooting


"No Java 21 runtime detected" message: Check if EXTRACT_SUPERCLASS_JAVA_HOME points to the correct JDK root directory (containing bin\java.exe). Run & "$env:EXTRACT_SUPERCLASS_JAVA_HOME\bin\java.exe" -version to verify the version is 21. If needed, add -Verbose at the end of the script command to view detailed logs.


"extractsuperclass-mcp-server.jar not found" message: Ensure that you have run mvn package in the project root directory, and that the script hasn't been moved to another directory.


Client still using old configuration: After modifying the MCP configuration file, restart the client or execute Developer: Reload Window in VS Code to apply the new commands and environment variables.


## Behaviour Summary
1. No existing superclass among targets: create a new abstract superclass and update all targets to extend it.
2. Exactly one target already extends something: reuse that superclass for the remaining targets; no new class is created.
3. All targets already share the same superclass: create a new abstract class that extends the shared parent, and rewire the targets to extend the new intermediate class.
4. Targets have incompatible superclasses: create a new concrete class in the planned location without touching the targets’ inheritance.

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
