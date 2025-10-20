# Extract Superclass 重构工具

## 功能概述

Extract Superclass 重构工具通过为两个或多个原本无继承关系的类引入一个新的抽象父类，并将其作为它们的共同父类，来建立继承结构。

## 工具目的

让选定的类具备可维护的继承关系，为后续的模板方法、代码复用等重构操作创造条件。

## 技术特性

- 采用 Eclipse JDT 进行 Java 代码分析与重写
- 支持命令行（CLI）方式调用
- 自动处理包结构和导入语句
- 智能命名抽象超类
- 多种继承场景下的兼容处理

## 使用方法

### 基本用法

```bash
java -jar target/extractsuperclass-cli.jar <projectRoot> --classNames <classNames>
```

### 参数说明

- `projectRoot`（必填）：项目根目录路径，可用逗号分隔多个路径
- `--classNames, -c`（必填）：需要提炼共同父类的类名，支持多个类，用逗号分隔
- `--superName, -s`（可选）：指定新超类的完全限定名
- `--targetPackage, -p`（可选）：指定新超类的目标包
- `--dryRun, -d`（可选）：执行分析但不进行实际修改
- `--verbose, -v`（可选）：启用详细日志
- `--help, -h`：显示帮助信息
- `--version, -V`：显示版本信息

### 使用示例

```bash
# 基本用法
java -jar target/extractsuperclass-cli.jar /path/to/project --classNames com.example.ProcessorA,com.example.ProcessorB

# 指定超类名称
java -jar target/extractsuperclass-cli.jar /path/to/project --classNames com.example.ProcessorA,com.example.ProcessorB --superName com.example.AbstractProcessor

# 指定目标包
java -jar target/extractsuperclass-cli.jar /path/to/project --classNames com.example.ProcessorA,com.example.ProcessorB --targetPackage com.common

# 干运行模式（仅分析不修改）
java -jar target/extractsuperclass-cli.jar /path/to/project --classNames com.example.ProcessorA,com.example.ProcessorB --dryRun

# 详细输出
java -jar target/extractsuperclass-cli.jar /path/to/project --classNames com.example.ProcessorA,com.example.ProcessorB --verbose
```

### MCP 集成

完成 `mvn package` 后会生成 `target/extractsuperclass-mcp-server.jar`。可按以下方式在支持 MCP 协议的代理（如 Cline）中通过 stdio 启动：

```json
{
  "mcpServers": {
    "extract-superclass-refactoring": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "C:/path/to/extractsuperclass-mcp-server.jar"
      ],
      "timeout": 60000,
      "autoApprove": [
        "extract_superclass"
      ],
      "env": {
        "JAVA_OPTS": "-Xmx512m"
      }
    }
  }
}
```

暴露出的工具名称为 `extract_superclass`，核心参数包括：

- `projectRoot`：项目根目录路径（例如 `C:/Users/wudi/Desktop/project`，支持使用逗号分隔多个模块路径）
- `classNames`：需要重构的类名数组，必须是完全限定名且至少两个
- 可选参数：`superQualifiedName`/`superName`

服务会复用 CLI 的实现逻辑，返回文本摘要以及 `modifiedFiles`、`executionTimeMs` 等结构化信息。

## 工具行为

### 继承情况分析

1. **所有类都没有超类**：创建新的抽象超类，让所有类继承它
2. **只有一个类有超类**：让其他没有超类的类继承该超类
3. **多个类已有不同的超类**：不进行任何操作

### 超类命名规则

- 如果用户指定了 `--superName`，则直接使用
- 否则基于输入类的共同前缀生成名称（如 `AbstractProcessor`）
- 若无法找到共同前缀，则使用 `AbstractBase` 作为默认名称

### 包位置选择

- 如果指定了 `--targetPackage`，将在该包中创建超类
- 否则选择输入类中最常见的包作为超类位置

## 构建和运行

```bash
mvn clean compile   # 构建项目
mvn test            # 运行测试
mvn package         # 打包（生成 CLI 与 MCP server）
java -jar target/extractsuperclass-cli.jar [参数]   # 运行 CLI 工具
```

## 项目结构

```
src/main/java/com/refactoring/extractsuperclass/
├── ExtractSuperclassCLI.java          # 命令行接口
├── ExtractSuperclassMcpServer.java    # MCP stdio server 适配器
├── ExtractSuperclassRefactorer.java   # 核心重构逻辑
├── ExtractSuperclassRequest.java      # 请求参数类
└── ExtractSuperclassResult.java       # 结果返回类
```

## 依赖项

- Eclipse JDT Core 3.36.0
- Picocli 4.7.5
- SLF4J 1.7.36
- Logback 1.2.13
- Jackson Databind 2.17.1
- JUnit Jupiter 5.10.2

## 许可证

本项目采用 MIT 许可证。
