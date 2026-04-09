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
            new WebPage("Balances", "balances", "coins"),
            new WebPage("Transactions", "transactions", "scroll-text")
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
     * Handles {@code GET /api/xcoins/balances?currency=<id>} -- returns the top 100 players by balance.
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
     * Handles {@code GET /api/xcoins/currencies} -- returns all configured currencies with their settings.
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
     * Handles {@code GET /api/xcoins/transactions?player=<name>&page=1&limit=20} -- returns transaction history.
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
                        case "player" -> playerName = kv[1];
                        case "page" -> { try { page = Integer.parseInt(kv[1]); } catch (NumberFormatException ignored) {} }
                        case "limit" -> { try { limit = Math.min(100, Integer.parseInt(kv[1])); } catch (NumberFormatException ignored) {} }
                    }
                }
            }
        }

        if (playerName == null || playerName.isBlank()) {
            webPanel.sendJson(exchange, 400, "{\"error\":\"Missing player parameter\"}");
            return;
        }

        if (!playerName.matches("[a-zA-Z0-9_]+")) {
            webPanel.sendJson(exchange, 400, "{\"error\":\"Invalid player name\"}");
            return;
        }

        int offset = (page - 1) * limit;
        JsonObject response = new JsonObject();
        JsonArray arr = new JsonArray();

        try (Connection conn = plugin.getDataSource().getConnection()) {
            // Get total count
            try (PreparedStatement countPs = conn.prepareStatement(
                    "SELECT COUNT(*) FROM xcoins_transactions WHERE player_name = ?")) {
                countPs.setString(1, playerName);
                try (ResultSet rs = countPs.executeQuery()) {
                    if (rs.next()) {
                        response.addProperty("total", rs.getInt(1));
                    }
                }
            }

            // Get transactions
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM xcoins_transactions WHERE player_name = ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, playerName);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
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
