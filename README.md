# Extract Superclass 重构工具

## 功能概述
Extract Superclass 可以为两个或多个类提炼出一个抽象父类，并自动更新这些类的继承关系，帮助项目建立清晰可维护的类层次结构，为模板方法、代码复用等后续重构打下基础。

## 核心特性
- 基于 Eclipse JDT 的 AST 分析与重写能力
- 既支持命令行（CLI），也支持 MCP Server 调用
- 自动管理包结构、导入语句与跨模块依赖
- 支持自定义输出位置并在必要时推断包名
- 提供干运行模式与详细日志输出

## 使用方法

### 基本命令
```bash
java -jar target/extractsuperclass-cli.jar <projectRoot> --classNames <classNames> --absoluteOutputPath <outputPath>
```

### 参数说明
- `projectRoot`（必填）：项目根目录，支持使用逗号分隔多个模块路径
- `--classNames, -c`（必填）：待提炼的类的完全限定名，使用逗号分隔
- `--superName, -s`（可选）：指定新超类的完全限定名
- `--absoluteOutputPath`（必填）：新建超类文件的绝对路径，既可指向目录也可直接指向 `.java` 文件
- `--dryRun, -d`（可选）：仅执行分析，不写入文件
- `--verbose, -v`（可选）：输出更详细的日志
- `--help, -h`：显示帮助
- `--version, -V`：显示版本

### 示例
```bash
# 基本用法
java -jar target/extractsuperclass-cli.jar /path/to/project --classNames com.example.ProcessorA,com.example.ProcessorB --absoluteOutputPath /path/to/output/AbstractProcessor.java

# 指定超类名称
java -jar target/extractsuperclass-cli.jar /path/to/project --classNames com.example.ProcessorA,com.example.ProcessorB --superName com.example.AbstractProcessor --absoluteOutputPath /path/to/output/AbstractProcessor.java

# 干运行模式
java -jar target/extractsuperclass-cli.jar /path/to/project --classNames com.example.ProcessorA,com.example.ProcessorB --absoluteOutputPath /tmp/AbstractProcessor.java --dryRun

# 输出详细日志
java -jar target/extractsuperclass-cli.jar /path/to/project --classNames com.example.ProcessorA,com.example.ProcessorB --absoluteOutputPath /tmp/AbstractProcessor.java --verbose
```

## MCP 集成
执行 `mvn package` 后会生成 `target/extractsuperclass-mcp-server.jar`。在支持 MCP 协议的代理（如 Cline）中可以通过 stdio 启动：
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

MCP 工具名称为 `extract_superclass`，核心参数如下：
- 必填：`projectRoot`、`classNames`、`absoluteOutputPath`/`absolute_output_path`
- 可选：`superQualifiedName`/`superName`、`dryRun`、`verbose`

调用成功后会返回文本摘要及结构化字段（如 `modifiedFiles`、`executionTimeMs` 等）。

## 工具行为
1. **所有目标类都无父类**：创建新的抽象超类，并让所有目标类继承它。
2. **仅有一个类已有父类**：其余类直接继承该现有父类。
3. **多个目标类已有相同父类**：生成新的抽象超类，让它继承原始父类，再将这些子类调至继承新超类，实现“插入一层”。
4. **多个目标类拥有不同父类**：为避免破坏原有层次，不执行任何修改。

当未显式指定 `--superName` 时，工具会基于输入类的共同前缀生成名称；若提供 `absoluteOutputPath` 为目录，则会在该目录下以自动生成的名称创建文件，并根据目录位置推断包名。

## 构建与运行
```bash
mvn clean compile    # 编译项目
mvn test             # 运行测试
mvn package          # 打包（生成 CLI 与 MCP Server）
java -jar target/extractsuperclass-cli.jar [参数]  # 运行 CLI 工具
```

## 项目结构
```
src/main/java/com/refactoring/extractsuperclass/
├── ExtractSuperclassCLI.java         # 命令行入口
├── ExtractSuperclassMcpServer.java   # MCP stdio server 实现
├── ExtractSuperclassRefactorer.java  # 核心重构逻辑
├── ExtractSuperclassRequest.java     # 请求对象
└── ExtractSuperclassResult.java      # 返回结果
```

## 依赖
- Eclipse JDT Core 3.36.0
- Picocli 4.7.5
- SLF4J 1.7.36
- Logback 1.2.13
- Jackson Databind 2.17.1
- JUnit Jupiter 5.10.2

## 许可证
本项目采用 MIT 许可证。
