package fr.xyness.XCore.Economy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Web.WebModule;
import fr.xyness.XCore.Web.WebPage;
import fr.xyness.XCore.Web.WebPageSpec;
import fr.xyness.XCore.Web.WebPanel;

/**
 * Web dashboard module for XCore economy.
 * <p>
 * Provides API endpoints for viewing player balances, currency configuration,
 * and transaction history through XCore's unified web panel.
 * </p>
 */
public class EconomyWebModule implements WebModule {

    /** Reference to the XCore plugin instance. */
    private final XCore plugin;

    /** Reference to the CoinsManager for economy data. */
    private final CoinsManager coinsManager;

    /** Reference to the WebPanel for authentication and response helpers. */
    private final WebPanel webPanel;

    /**
     * Creates a new EconomyWebModule.
     *
     * @param plugin       The XCore plugin instance.
     * @param coinsManager The economy CoinsManager.
     */
    public EconomyWebModule(XCore plugin, CoinsManager coinsManager) {
        this.plugin = plugin;
        this.coinsManager = coinsManager;
        this.webPanel = plugin.getWebPanel();
    }

    @Override
    public String getName() {
        return "Economy";
    }

    @Override
    public String getIcon() {
        return "coins";
    }

    @Override
    public List<WebPage> getPages() {
        return List.of(
            new WebPage("balances", "balances", "coins", WebPageSpec.table("/api/economy/balances")
                .titleKey("economy-balances")
                .dataKeys("balances")
                .emptyKey("no-data-found")),
            new WebPage("transactions", "transactions", "scroll-text",
                WebPageSpec.table("/api/economy/transactions")
                    .titleKey("economy-transactions")
                    .dataKeys("transactions")
                    .emptyKey("no-transactions-found")
                    .search("search-by-player")
                    .paged(50))
        );
    }

    @Override
    public void registerRoutes(HttpServer server, String basePath) {
        server.createContext(basePath + "/balances", this::handleBalances);
        server.createContext(basePath + "/currencies", this::handleCurrencies);
        server.createContext(basePath + "/transactions", this::handleTransactions);
    }

    // **************************************************************************
    // *                          GET Handlers                                  *
    // **************************************************************************

    /**
     * Handles {@code GET /api/economy/balances?currency=<id>} -- returns the top 100 players by balance.
     */
    private void handleBalances(HttpExchange exchange) throws IOException {
        webPanel.addCorsHeaders(exchange);
        if (webPanel.handlePreflight(exchange)) return;
        if (!webPanel.authenticate(exchange)) return;

        // Parse currency query parameter
        String currencyId = null;
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && "currency".equals(kv[0])) {
                    currencyId = kv[1];
                }
            }
        }
        if (currencyId == null || coinsManager.getCurrency(currencyId) == null) {
            currencyId = coinsManager.getVaultCurrency().getId();
        }

        if (!currencyId.matches("[a-zA-Z0-9_]+")) {
            webPanel.sendJson(exchange, 400, "{\"error\":\"Invalid currency\"}");
            return;
        }

        String colName = coinsManager.col(currencyId);
        JsonArray arr = new JsonArray();
        try (Connection conn = plugin.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT player_name, " + colName + " FROM players ORDER BY " + colName + " DESC LIMIT 100");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("player_name", rs.getString("player_name"));
                obj.addProperty(currencyId, rs.getDouble(colName));
                arr.add(obj);
            }
        } catch (Exception e) {
            plugin.logger().sendWarning("Web balances query failed: " + e.getMessage());
            webPanel.sendJson(exchange, 500, "{\"error\":\"Database error\"}");
            return;
        }

        webPanel.sendJson(exchange, 200, arr.toString());
    }

    /**
     * Handles {@code GET /api/economy/currencies} -- returns all configured currencies with their settings.
     */
    private void handleCurrencies(HttpExchange exchange) throws IOException {
        webPanel.addCorsHeaders(exchange);
        if (webPanel.handlePreflight(exchange)) return;
        if (!webPanel.authenticate(exchange)) return;

        JsonArray arr = new JsonArray();
        for (Currency c : coinsManager.getCurrencies()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", c.getId());
            obj.addProperty("symbol", c.getSymbol());
            obj.addProperty("symbol_before", c.isSymbolBefore());
            obj.addProperty("decimals", c.getDecimals());
            obj.addProperty("starting_balance", c.getStartingBalance());
            obj.addProperty("vault_primary", c.isVaultPrimary());
            obj.addProperty("max_balance", c.getMaxBalance());
            arr.add(obj);
        }

        webPanel.sendJson(exchange, 200, arr.toString());
    }

    /**
     * Handles {@code GET /api/economy/transactions?player=<name>&page=1&limit=20} -- returns transaction history.
     */
    private void handleTransactions(HttpExchange exchange) throws IOException {
        webPanel.addCorsHeaders(exchange);
        if (webPanel.handlePreflight(exchange)) return;
        if (!webPanel.authenticate(exchange)) return;

        // Parse query parameters
        String playerName = null;
        int page = 1;
        int limit = 20;
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2) {
                    switch (kv[0]) {
                        case "player", "search" ->
                            playerName = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                        case "page" -> { try { page = Math.max(1, Integer.parseInt(kv[1])); } catch (NumberFormatException ignored) {} }
                        case "limit" -> { try { limit = Math.clamp(Integer.parseInt(kv[1]), 1, 100); } catch (NumberFormatException ignored) {} }
                    }
                }
            }
        }

        // No player means the whole ledger, newest first — which is what the page opens on. The
        // filter narrows it; requiring it made the page useless until something was typed.
        boolean filtered = playerName != null && !playerName.isBlank();
        if (filtered && !playerName.matches("[a-zA-Z0-9_]{1,16}")) {
            webPanel.sendJson(exchange, 400, "{\"error\":\"Invalid player name\"}");
            return;
        }

        int offset = (page - 1) * limit;
        JsonObject response = new JsonObject();
        JsonArray arr = new JsonArray();
        String where = filtered ? " WHERE player_name = ?" : "";

        try (Connection conn = plugin.getDataSource().getConnection()) {
            // Get total count
            try (PreparedStatement countPs = conn.prepareStatement(
                    "SELECT COUNT(*) FROM xcore_transactions" + where)) {
                if (filtered) countPs.setString(1, playerName);
                try (ResultSet rs = countPs.executeQuery()) {
                    if (rs.next()) {
                        response.addProperty("total", rs.getInt(1));
                    }
                }
            }

            // Get transactions
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM xcore_transactions" + where + " ORDER BY id DESC LIMIT ? OFFSET ?")) {
                int idx = 1;
                if (filtered) ps.setString(idx++, playerName);
                ps.setInt(idx++, limit);
                ps.setInt(idx, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject obj = new JsonObject();
                        obj.addProperty("id", rs.getInt("id"));
                        obj.addProperty("player_uuid", rs.getString("player_uuid"));
                        obj.addProperty("player_name", rs.getString("player_name"));
                        obj.addProperty("currency", rs.getString("currency"));
                        obj.addProperty("amount", rs.getDouble("amount"));
                        obj.addProperty("type", rs.getString("type"));
                        obj.addProperty("target_name", rs.getString("target_name"));
                        obj.addProperty("details", rs.getString("details"));
                        obj.addProperty("created_at", rs.getString("created_at"));
                        arr.add(obj);
                    }
                }
            }
        } catch (Exception e) {
            plugin.logger().sendWarning("Web transactions query failed: " + e.getMessage());
            webPanel.sendJson(exchange, 500, "{\"error\":\"Database error\"}");
            return;
        }

        response.addProperty("page", page);
        response.addProperty("limit", limit);
        response.add("transactions", arr);

        webPanel.sendJson(exchange, 200, response.toString());
    }

}
