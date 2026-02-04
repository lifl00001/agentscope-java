/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.quickstart;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.nacos.mcp.NacosMcpClientBuilder;
import io.agentscope.extensions.nacos.mcp.NacosMcpClientWrapper;
import io.agentscope.extensions.nacos.mcp.NacosMcpServerManager;
import io.agentscope.extensions.nacos.mcp.tool.NacosToolkit;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Properties;

/**
 * NacosMcpExample - Demonstrates MCP tool integration via Nacos service discovery.
 *
 * <p>This example shows how to: 1. Connect to a Nacos server 2. Discover MCP servers
 * registered in Nacos 3. Use MCP tools dynamically discovered from Nacos
 *
 * <p>Prerequisites: 1. Nacos server running at the configured address 2. MCP servers
 * registered in Nacos 3. DASHSCOPE_API_KEY environment variable set
 */
public class NacosMcpExample {

    private static final BufferedReader reader =
            new BufferedReader(new InputStreamReader(System.in));

    // Nacos server configuration
    private static final String NACOS_SERVER_ADDR = "47.122.78.28:8848";
    private static final String NACOS_USERNAME = "nacos";
    private static final String NACOS_PASSWORD = "lifl1234";
    private static final String NACOS_NAMESPACE = ""; // Empty for default namespace

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "Nacos MCP Example",
                "This example demonstrates MCP (Model Context Protocol) integration via Nacos.\n"
                        + "MCP servers are discovered dynamically from Nacos service registry.\n"
                        + "Nacos Server: "
                        + NACOS_SERVER_ADDR);

        // Get API key
        String apiKey = ExampleUtils.getDashScopeApiKey();

        // Connect to Nacos and discover MCP servers
        AiService aiService = createNacosAiService();

        // Get MCP server name from user
        String mcpServerName = getMcpServerName();

        // Initialize MCP client from Nacos
        System.out.println("\n========================================");
        System.out.println("Initializing MCP client from Nacos...");
        System.out.println("========================================");

        NacosMcpServerManager mcpServerManager = new NacosMcpServerManager(aiService);

        try {
            NacosMcpClientWrapper mcpClient =
                    NacosMcpClientBuilder.create(mcpServerName, mcpServerManager).build();

            System.out.println("✓ Successfully connected to MCP server: " + mcpServerName);
            System.out.println("✓ Discovered and registered MCP tools");

            // Create toolkit with Nacos support
            Toolkit toolkit = new NacosToolkit();
            toolkit.registerMcpClient(mcpClient).block();

            // Display available tools
            System.out.println("\n========================================");
            System.out.println("Available Tools:");
            System.out.println("========================================");
            toolkit.getToolNames().stream()
                    .sorted()
                    .forEach(tool -> System.out.println("  - " + tool));
            System.out.println("========================================\n");

            // Create agent with MCP tools
            ReActAgent agent =
                    createAgent(apiKey, toolkit);

            System.out.println("\nAgent is ready! You can now ask questions that require MCP tools.");
            System.out.println("Type 'exit' to quit.\n");

            // Start interactive chat
            ExampleUtils.startChat(agent);

        } catch (Exception e) {
            System.err.println("\n✗ Failed to initialize MCP client: " + e.getMessage());
            System.err.println("\nPossible reasons:");
            System.err.println("  1. MCP server '" + mcpServerName + "' not found in Nacos");
            System.err.println("  2. Nacos server is not accessible");
            System.err.println("  3. MCP server is not healthy or reachable");
            System.err.println("\nPlease check:");
            System.err.println("  - Nacos server is running at: " + NACOS_SERVER_ADDR);
            System.err.println("  - MCP server is registered in Nacos");
            System.err.println("  - Network connectivity to Nacos and MCP server");
            throw e;
        }
    }

    /**
     * Creates and configures Nacos AiService for MCP server discovery.
     *
     * @return configured AiService instance
     * @throws NacosException if connection to Nacos fails
     */
    private static AiService createNacosAiService() throws NacosException {
        System.out.println("\n========================================");
        System.out.println("Connecting to Nacos...");
        System.out.println("========================================");
        System.out.println("Server: " + NACOS_SERVER_ADDR);
        System.out.println("Username: " + NACOS_USERNAME);
        System.out.println("========================================\n");

        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, NACOS_SERVER_ADDR);
        properties.put(PropertyKeyConst.USERNAME, NACOS_USERNAME);
        properties.put(PropertyKeyConst.PASSWORD, NACOS_PASSWORD);

        // Set namespace if provided (empty for default namespace)
        if (NACOS_NAMESPACE != null && !NACOS_NAMESPACE.isEmpty()) {
            properties.put(PropertyKeyConst.NAMESPACE, NACOS_NAMESPACE);
        }

        try {
            AiService aiService = AiFactory.createAiService(properties);
            System.out.println("✓ Successfully connected to Nacos");
            return aiService;
        } catch (NacosException e) {
            System.err.println("✗ Failed to connect to Nacos: " + e.getMessage());
            System.err.println("\nPlease check:");
            System.err.println("  - Nacos server is running at: " + NACOS_SERVER_ADDR);
            System.err.println("  - Username and password are correct");
            System.err.println("  - Network connectivity to Nacos server");
            throw e;
        }
    }

    /**
     * Gets MCP server name from user input.
     *
     * @return MCP server name registered in Nacos
     * @throws Exception if read fails
     */
    private static String getMcpServerName() throws Exception {
        System.out.println("\n========================================");
        System.out.println("MCP Server Configuration");
        System.out.println("========================================");
        System.out.print(
                "Enter MCP server name (registered in Nacos)\n"
                        + "Press Enter for default 'business-mcp-server': ");

        String serverName = reader.readLine().trim();

        if (serverName.isEmpty()) {
            serverName = "business-mcp-server";
            System.out.println("Using default: " + serverName);
        }

        System.out.println("========================================\n");
        return serverName;
    }

    /**
     * Creates and configures a ReActAgent with MCP tools.
     *
     * @param apiKey DashScope API key
     * @param toolkit toolkit with MCP tools registered
     * @return configured ReActAgent
     */
    private static ReActAgent createAgent(String apiKey, Toolkit toolkit) {
        System.out.println("Creating ReActAgent with MCP tools...");

        return ReActAgent.builder()
                .name("NacosMcpAgent")
                .sysPrompt(
                        "You are a helpful assistant with access to various tools via MCP (Model Context Protocol). "
                                + "Use the available tools to help users with their requests. "
                                + "Always check if a tool is available before attempting to use it.")
                .model(
                        DashScopeChatModel.builder()
                                .apiKey(apiKey)
                                .modelName("qwen-max")
                                .stream(true)
                                .enableThinking(false)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .build();
    }
}
