package com.refactoring.extractsuperclass;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class ExtractSuperclassMcpServer {
	private static final Logger logger = LoggerFactory.getLogger(ExtractSuperclassMcpServer.class);
	private static final String SERVER_NAME = "Extract Superclass Refactoring MCP Server";
	private static final String VERSION = "1.0.0";

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
	private final PrintWriter writer = new PrintWriter(System.out, true);

	public static void main(String[] args) {
		new ExtractSuperclassMcpServer().run();
	}

	public void run() {
		logger.info("Starting MCP Server: {}", SERVER_NAME);
		try {
			while (true) {
				String line = reader.readLine();
				if (line == null) {
					return;
				}
				if (line.trim().isEmpty()) {
					continue;
				}
				try {
					JsonNode request = objectMapper.readTree(line);
					JsonNode response = handleRequest(request);
					if (response == null) {
						continue;
					}
					writer.println(objectMapper.writeValueAsString(response));
					writer.flush();
				} catch (Exception ex) {
					logger.error("Error processing request: {}", ex.getMessage(), ex);
					ObjectNode errorResponse = createErrorResponse(null, -32603, "Internal error", ex.getMessage());
					writer.println(objectMapper.writeValueAsString(errorResponse));
					writer.flush();
				}
			}
		} catch (IOException ioEx) {
			logger.error("IO error: {}", ioEx.getMessage(), ioEx);
		}
	}

	private JsonNode handleRequest(JsonNode request) {
		String method = request.path("method").asText();
		JsonNode params = request.path("params");
		JsonNode id = request.path("id");
		logger.debug("Handling request: method={}, id={}", method, id);
		switch (method) {
			case "initialize":
				return handleInitialize(id);
			case "tools/list":
				return handleToolsList(id);
			case "tools/call":
				return handleToolsCall(id, params);
			default:
				return createErrorResponse(id, -32601, "Method not found", "Unknown method: " + method);
		}
	}

	private JsonNode handleInitialize(JsonNode id) {
		ObjectNode response = objectMapper.createObjectNode();
		response.put("jsonrpc", "2.0");
		response.set("id", id);

		ObjectNode result = objectMapper.createObjectNode();
		result.put("protocolVersion", "2024-11-05");
		result.set("capabilities", createCapabilities());
		result.set("serverInfo", createServerInfo());
		response.set("result", result);
		return response;
	}

	private JsonNode handleToolsList(JsonNode id) {
		ObjectNode response = objectMapper.createObjectNode();
		response.put("jsonrpc", "2.0");
		response.set("id", id);

		ObjectNode result = objectMapper.createObjectNode();
		ArrayNode tools = objectMapper.createArrayNode();

		ObjectNode tool = objectMapper.createObjectNode();
		tool.put("name", "extract_superclass");
		tool.put("description", "Create a shared abstract superclass for the selected classes.");

		ObjectNode inputSchema = objectMapper.createObjectNode();
		inputSchema.put("type", "object");

		ArrayNode required = objectMapper.createArrayNode();
		required.add("projectRoot");
		required.add("classNames");
		required.add("absoluteOutputPath");
		inputSchema.set("required", required);

		ObjectNode properties = objectMapper.createObjectNode();

		ObjectNode projectRootProperty = objectMapper.createObjectNode();
		projectRootProperty.put("type", "string");
		projectRootProperty.put("description", "Project root directory path (e.g. C:/workspace/demo-project). Multiple modules can be separated by commas.");
		ArrayNode projectRootExamples = objectMapper.createArrayNode();
		projectRootExamples.add("C:/workspace/demo-project");
		projectRootProperty.set("examples", projectRootExamples);
		properties.set("projectRoot", projectRootProperty);

		ObjectNode classNamesProperty = objectMapper.createObjectNode();
		classNamesProperty.put("type", "array");
		classNamesProperty.put("description", "Fully qualified names of the classes to refactor.");
		classNamesProperty.put("minItems", 2);
		ObjectNode classNameItems = objectMapper.createObjectNode();
		classNameItems.put("type", "string");
		classNamesProperty.set("items", classNameItems);
		properties.set("classNames", classNamesProperty);

		properties.set("superQualifiedName", createStringProperty("Optional fully qualified name for the new superclass.", false));
		properties.set("superName", createStringProperty("Alias for superQualifiedName to match CLI arguments.", false));
		properties.set("absoluteOutputPath", createStringProperty("Absolute directory or file path for the generated superclass.", true));
		properties.set("absolute_output_path", createStringProperty("Alias for absoluteOutputPath to match snake_case arguments.", true));

		inputSchema.set("properties", properties);
		tool.set("inputSchema", inputSchema);

		tools.add(tool);
		result.set("tools", tools);

		response.set("result", result);
		return response;
	}

	private JsonNode handleToolsCall(JsonNode id, JsonNode params) {
		String toolName = params.path("name").asText();
		JsonNode arguments = params.path("arguments");
		logger.info("Calling tool: {}", toolName);

		if ("extract_superclass".equals(toolName)) {
			return handleExtractSuperclass(id, arguments);
		}

		return createErrorResponse(id, -32601, "Tool not found", "Unknown tool: " + toolName);
	}

	private JsonNode handleExtractSuperclass(JsonNode id, JsonNode arguments) {
		List<String> projectRoots = new ArrayList<>();
		projectRoots.addAll(collectStringValues(arguments.path("projectRoot")));
		projectRoots.addAll(collectStringValues(arguments.path("projectRoots")));

		if (projectRoots.isEmpty()) {
			return createErrorResponse(id, -32602, "Invalid parameters", "Missing required parameter: projectRoot");
		}

		List<String> classNames = new ArrayList<>();
		classNames.addAll(collectStringValues(arguments.path("classNames")));
		classNames.addAll(collectStringValues(arguments.path("className")));

		if (classNames.isEmpty()) {
			return createErrorResponse(id, -32602, "Invalid parameters", "Missing required parameter: classNames");
		}

		String superQualifiedName = optionalText(arguments, "superQualifiedName");
		if (superQualifiedName == null) {
			superQualifiedName = optionalText(arguments, "superName");
		}

		String absoluteOutputPath = optionalText(arguments, "absoluteOutputPath");
		if (absoluteOutputPath == null) {
			absoluteOutputPath = optionalText(arguments, "absolute_output_path");
		}
		if (absoluteOutputPath == null || absoluteOutputPath.trim().isEmpty()) {
			return createErrorResponse(id, -32602, "Invalid parameters", "Missing required parameter: absoluteOutputPath");
		}
		absoluteOutputPath = absoluteOutputPath.trim();

		boolean dryRun = arguments.path("dryRun").asBoolean(false);
		boolean verbose = arguments.path("verbose").asBoolean(false);

		List<File> projectRootFiles = new ArrayList<>();
		List<String> invalidRoots = new ArrayList<>();
		for (String root : projectRoots) {
			if (root.isEmpty()) {
				continue;
			}
			File file = new File(root);
			if (!file.exists() || !file.isDirectory()) {
				invalidRoots.add(root);
			} else {
				projectRootFiles.add(file);
			}
		}

		if (!invalidRoots.isEmpty()) {
			return createErrorResponse(id, -32602, "Invalid parameters", "Provided projectRoot path(s) must exist and be directories: " + invalidRoots);
		}
		if (projectRootFiles.isEmpty()) {
			return createErrorResponse(id, -32602, "Invalid parameters", "No usable project root paths were provided.");
		}

		logger.info(
			"Executing extract_superclass: projectRoots={}, classNames={}, superQualifiedName={}, absoluteOutputPath={}, dryRun={}, verbose={}",
			projectRoots,
			classNames,
			superQualifiedName,
			absoluteOutputPath,
			dryRun,
			verbose
		);

		ExtractSuperclassResult result;
		try {
			ExtractSuperclassRefactorer refactorer = new ExtractSuperclassRefactorer(projectRootFiles);
			ExtractSuperclassRequest request = new ExtractSuperclassRequest(
				classNames,
				superQualifiedName,
				absoluteOutputPath,
				dryRun,
				verbose
			);
			result = refactorer.performRefactoring(request);
		} catch (Exception ex) {
			logger.error("Refactoring failed with exception", ex);
			return createErrorResponse(id, -32603, "Internal error", ex.getMessage());
		}

		ObjectNode response = objectMapper.createObjectNode();
		response.put("jsonrpc", "2.0");
		response.set("id", id);

		ObjectNode toolResult = objectMapper.createObjectNode();
		ArrayNode content = objectMapper.createArrayNode();
		ObjectNode textContent = objectMapper.createObjectNode();
		textContent.put("type", "text");

		StringBuilder resultText = new StringBuilder();
		if (result.isSuccess()) {
			resultText.append("[SUCCESS] Extract Superclass refactoring completed.\n");
			if (result.getSuperclassQualifiedName() != null && !result.getSuperclassQualifiedName().isEmpty()) {
				resultText.append("  Superclass: ").append(result.getSuperclassQualifiedName()).append("\n");
			}
			if (dryRun) {
				resultText.append("  Dry run requested - no files were modified.\n");
			} else if (result.getModifiedFiles() != null && !result.getModifiedFiles().isEmpty()) {
				resultText.append("  Modified files:\n");
				for (String file : result.getModifiedFiles()) {
					resultText.append("    ").append(file).append("\n");
				}
			} else {
				resultText.append("  No files were modified.\n");
			}
			if (result.getExecutionTimeMs() > 0) {
				resultText.append("  Execution time: ").append(result.getExecutionTimeMs()).append(" ms\n");
			}
		} else {
			resultText.append("[ERROR] Extract Superclass refactoring failed.\n");
			String errorMessage = result.getErrorMessage();
			if (errorMessage == null || errorMessage.trim().isEmpty()) {
				errorMessage = "An unknown error occurred.";
			}
			resultText.append("  ").append(errorMessage).append("\n");
		}

		textContent.put("text", resultText.toString());
		content.add(textContent);
		toolResult.set("content", content);
		toolResult.put("isError", !result.isSuccess());
		if (result.getExecutionTimeMs() > 0) {
			toolResult.put("executionTimeMs", result.getExecutionTimeMs());
		}
		if (result.getSuperclassQualifiedName() != null && !result.getSuperclassQualifiedName().isEmpty()) {
			toolResult.put("superclassQualifiedName", result.getSuperclassQualifiedName());
		}
		ArrayNode modifiedFiles = objectMapper.createArrayNode();
		if (result.getModifiedFiles() != null) {
			for (String file : result.getModifiedFiles()) {
				modifiedFiles.add(file);
			}
		}
		toolResult.set("modifiedFiles", modifiedFiles);

		response.set("result", toolResult);
		return response;
	}

	private ObjectNode createCapabilities() {
		ObjectNode capabilities = objectMapper.createObjectNode();
		ObjectNode tools = objectMapper.createObjectNode();
		tools.put("listChanged", false);
		capabilities.set("tools", tools);
		return capabilities;
	}

	private ObjectNode createServerInfo() {
		ObjectNode serverInfo = objectMapper.createObjectNode();
		serverInfo.put("name", SERVER_NAME);
		serverInfo.put("version", VERSION);
		return serverInfo;
	}

	private ObjectNode createErrorResponse(JsonNode id, int code, String message, String data) {
		ObjectNode response = objectMapper.createObjectNode();
		response.put("jsonrpc", "2.0");
		response.set("id", id);

		ObjectNode error = objectMapper.createObjectNode();
		error.put("code", code);
		error.put("message", message);
		if (data != null && !data.isEmpty()) {
			error.put("data", data);
		}
		response.set("error", error);
		return response;
	}

	private ObjectNode createStringProperty(String description, boolean required) {
		ObjectNode property = objectMapper.createObjectNode();
		property.put("type", "string");
		property.put("description", description);
		return property;
	}

	private List<String> collectStringValues(JsonNode node) {
		List<String> values = new ArrayList<>();
		if (node == null || node.isMissingNode() || node.isNull()) {
			return values;
		}
		if (node.isArray()) {
			for (JsonNode element : node) {
				String text = element.asText("").trim();
				if (!text.isEmpty()) {
					values.add(text);
				}
			}
			return values;
		}

		String text = node.asText("");
		if (!text.isEmpty()) {
			String[] parts = text.split(",");
			for (String part : parts) {
				String trimmed = part.trim();
				if (!trimmed.isEmpty()) {
					values.add(trimmed);
				}
			}
		}
		return values;
	}

	private String optionalText(JsonNode node, String fieldName) {
		if (node == null || node.isMissingNode()) {
			return null;
		}
		JsonNode value = node.get(fieldName);
		if (value == null || value.isNull()) {
			return null;
		}
		String text = value.asText("").trim();
		return text.isEmpty() ? null : text;
	}
}
