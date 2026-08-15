package fr.xyness.XCore.Web;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * A declarative description of a dashboard page.
 *
 * <h2>Why this exists</h2>
 * The dashboard's JavaScript used to contain the interface of every addon: the endpoints, the
 * button labels and the form fields of XBans, XAuctionHouse and the rest were written into
 * {@code app.js}. Two consequences followed. Adding a page to an addon meant editing and releasing
 * <em>XCore</em>; and a server that did not run XBans still shipped and parsed its UI.
 *
 * <p>A module now declares what its pages contain, and the browser renders it. Nothing about a
 * specific addon remains in the core's JavaScript.</p>
 *
 * <h2>Labels are language keys</h2>
 * Every {@code ...Key} field is looked up in {@code lang/web_<code>.yml} by the browser, and falls
 * back to the string itself when there is no entry — so passing plain English text still works, it
 * simply is not translated.
 *
 * <h2>Example</h2>
 * <pre>
 * WebPageSpec.table("/api/xbans/bans")
 *     .titleKey("xbans-bans")
 *     .dataKeys("bans", "data")
 *     .emptyKey("no-records-found")
 *     .actions(WebPageSpec.action("unban", "/api/xbans/unban")
 *         .style("danger")
 *         .idFrom("player_name", "target", "name")
 *         .send("player", "player_name", "target", "name"))
 *     .form("ban-player", "/api/xbans/ban",
 *           WebPageSpec.field("player", "player", true).placeholderKey("player-name"),
 *           WebPageSpec.field("duration", "duration", false).placeholderKey("e-g-7d-30m-permanent"),
 *           WebPageSpec.field("reason", "reason", false).placeholderKey("reason-for-ban"));
 * </pre>
 */
public final class WebPageSpec {

    /** What kind of page the browser should build. */
    public enum Type {
        /** A grid of statistic tiles read from a single JSON object. */
        STATS,
        /** A table of rows, optionally searchable, paged, and with row actions. */
        TABLE,
        /** The generic raw-YAML configuration editor. */
        CONFIG
    }

    // **************************************************************************
    // *                              Tiles                                     *
    // **************************************************************************

    /**
     * One statistic tile of a {@link Type#STATS} page.
     *
     * <p>Build one with {@link WebPageSpec#stat(String, String...)} and add it with
     * {@link WebPageSpec#tiles(Stat...)}.</p>
     */
    public static final class Stat {
        private final String labelKey;
        private final List<String> keys;
        private String color = "";
        private String format = "";
        private final List<double[]> thresholdValues = new ArrayList<>();
        private final List<String> thresholdColors = new ArrayList<>();

        private Stat(String labelKey, List<String> keys) {
            this.labelKey = labelKey;
            this.keys = keys;
        }

        /**
         * Sets the accent class: {@code red}, {@code orange}, {@code yellow}, {@code green},
         * {@code blue}, {@code purple}, {@code cyan}, or empty for the default.
         */
        public Stat color(String color) { this.color = color == null ? "" : color; return this; }

        /**
         * Sets how the number is displayed.
         *
         * <ul>
         *   <li>{@code number} — thousands separators.</li>
         *   <li>{@code decimal} — one decimal place.</li>
         *   <li>{@code megabytes} — bytes rendered as {@code 512 MB}.</li>
         * </ul>
         */
        public Stat format(String format) { this.format = format == null ? "" : format; return this; }

        /**
         * Overrides the colour when the value falls under a threshold. The tightest crossed bound
         * wins, so the order they are declared in does not matter.
         *
         * @param value The exclusive upper bound.
         * @param color The accent class to use below it.
         */
        public Stat below(double value, String color) {
            thresholdValues.add(new double[] { value });
            thresholdColors.add(color);
            return this;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("labelKey", labelKey);
            o.addProperty("color", color);
            if (!format.isEmpty()) o.addProperty("format", format);
            JsonArray arr = new JsonArray();
            for (String k : keys) arr.add(k);
            o.add("keys", arr);
            if (!thresholdValues.isEmpty()) {
                JsonArray th = new JsonArray();
                for (int i = 0; i < thresholdValues.size(); i++) {
                    JsonObject t = new JsonObject();
                    t.addProperty("below", thresholdValues.get(i)[0]);
                    t.addProperty("color", thresholdColors.get(i));
                    th.add(t);
                }
                o.add("thresholds", th);
            }
            return o;
        }
    }

    // **************************************************************************
    // *                             Actions                                    *
    // **************************************************************************

    /**
     * One parameter of a request body.
     *
     * @param param The name to send.
     * @param from  Candidate fields on the clicked row, tried in order.
     */
    public record Param(String param, List<String> from) {}

    /** A per-row action button. Build one with {@link WebPageSpec#action(String, String)}. */
    public static final class Action {
        private final String labelKey;
        private final String endpoint;
        private String style = "danger";
        private final List<String> idFrom = new ArrayList<>();
        private final List<Param> body = new ArrayList<>();
        private boolean reload = true;

        private Action(String labelKey, String endpoint) {
            this.labelKey = labelKey;
            this.endpoint = endpoint;
        }

        /** Sets the button class: {@code danger}, {@code success}, {@code warning} or empty. */
        public Action style(String style) { this.style = style == null ? "" : style; return this; }

        /** Names the row fields that identify it in the confirmation prompt, tried in order. */
        public Action idFrom(String... fields) {
            for (String f : fields) idFrom.add(f);
            return this;
        }

        /**
         * Adds a body parameter read from the clicked row.
         *
         * @param param The name to send.
         * @param from  Candidate row fields, tried in order.
         */
        public Action send(String param, String... from) {
            body.add(new Param(param, List.of(from)));
            return this;
        }

        /**
         * Adds a body parameter with a fixed value, independent of the clicked row.
         *
         * <p>What lets one endpoint serve two buttons — accept and refuse, grant and revoke —
         * instead of duplicating a route for a single flag.</p>
         *
         * @param param The name to send.
         * @param value The literal value.
         */
        public Action constant(String param, String value) {
            body.add(new Param(param, List.of("=" + value)));
            return this;
        }

        /** Whether the table reloads once the action succeeds. Defaults to {@code true}. */
        public Action reload(boolean reload) { this.reload = reload; return this; }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("labelKey", labelKey);
            o.addProperty("endpoint", endpoint);
            o.addProperty("style", style);
            o.addProperty("reload", reload);
            JsonArray ids = new JsonArray();
            for (String f : idFrom) ids.add(f);
            o.add("idFrom", ids);
            JsonArray params = new JsonArray();
            for (Param p : body) {
                JsonObject po = new JsonObject();
                po.addProperty("param", p.param());
                JsonArray from = new JsonArray();
                for (String f : p.from()) from.add(f);
                po.add("from", from);
                params.add(po);
            }
            o.add("body", params);
            return o;
        }
    }

    // **************************************************************************
    // *                              Inputs                                    *
    // **************************************************************************

    /** One input of a creation form. */
    public static final class Field {
        private final String key;
        private final String labelKey;
        private final boolean required;
        private String type = "text";
        private String placeholderKey = "";

        private Field(String key, String labelKey, boolean required) {
            this.key = key;
            this.labelKey = labelKey;
            this.required = required;
        }

        /** Sets the HTML input type ({@code text}, {@code number}, …). */
        public Field type(String type) { this.type = type; return this; }

        /** Sets the language key for the placeholder. */
        public Field placeholderKey(String key) { this.placeholderKey = key; return this; }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("key", key);
            o.addProperty("labelKey", labelKey);
            o.addProperty("type", type);
            o.addProperty("placeholderKey", placeholderKey);
            o.addProperty("required", required);
            return o;
        }
    }

    /**
     * One choice of a drop-down filter.
     *
     * @param value    The value sent to the endpoint.
     * @param labelKey Language key for the visible label.
     */
    public record Option(String value, String labelKey) {}

    /** A drop-down filter shown next to the search box. */
    public record Filter(String param, String placeholderKey, List<Option> options) {}

    // **************************************************************************
    // *                              State                                     *
    // **************************************************************************

    private final Type type;
    private final String endpoint;
    private String titleKey = "";
    private String emptyKey = "no-data-found";
    private String errorKey = "failed-to-load-data";
    private final List<String> dataKeys = new ArrayList<>();
    private final List<String> totalKeys = new ArrayList<>();
    private final List<Stat> stats = new ArrayList<>();
    private final List<Action> actions = new ArrayList<>();
    private String formTitleKey;
    private String formEndpoint;
    private String formStyle = "danger";
    private final List<Field> formFields = new ArrayList<>();
    private String bulkLabelKey;
    private String bulkStyle = "danger";
    private String bulkEndpoint;
    private String bulkConfirmKey;
    private String searchPlaceholderKey;
    private final List<Filter> filters = new ArrayList<>();
    private int pageSize;
    private String togglesTitleKey;
    private String togglesEmptyKey;
    private String togglesFrom = "";
    private String detailsTitleKey;
    private String chartTitleKey;
    private String chartEndpoint;
    private String chartLabelField;
    private String chartValueField;
    private String chartDataKey;
    private String chartEmptyKey;
    private int chartMaxPoints;

    /**
     * Every chart declared on this page.
     *
     * <p>{@link #chart} used to keep one set of fields, so a page asking for a second series
     * silently lost the first. The fields above still describe the last one declared — the
     * dashboard reads {@code charts} and falls back to {@code chart} — and each call appends.</p>
     */
    private final List<JsonObject> charts = new ArrayList<>();

    // Heatmap, when the page draws one.
    private String heatmapTitleKey;
    private String heatmapEndpoint;
    private String heatmapDataKey;
    private String heatmapXField;
    private String heatmapZField;
    private String heatmapValueField;
    private String heatmapEmptyKey;

    private WebPageSpec(Type type, String endpoint) {
        this.type = type;
        this.endpoint = endpoint;
    }

    // **************************************************************************
    // *                             Factories                                  *
    // **************************************************************************

    /** A page of statistic tiles fed by {@code endpoint}. */
    public static WebPageSpec stats(String endpoint) { return new WebPageSpec(Type.STATS, endpoint); }

    /** A page of rows fed by {@code endpoint}. */
    public static WebPageSpec table(String endpoint) { return new WebPageSpec(Type.TABLE, endpoint); }

    /** The generic raw configuration editor for this module. */
    public static WebPageSpec config() { return new WebPageSpec(Type.CONFIG, ""); }

    /**
     * Builds a statistic tile.
     *
     * @param labelKey Language key for the tile label.
     * @param jsonKeys Candidate JSON fields, tried in order — endpoints have renamed fields over
     *                 time and a tile should not blank out because of it.
     */
    public static Stat stat(String labelKey, String... jsonKeys) {
        return new Stat(labelKey, List.of(jsonKeys));
    }

    /** Builds a row action. */
    public static Action action(String labelKey, String endpoint) {
        return new Action(labelKey, endpoint);
    }

    /** Builds a form field. */
    public static Field field(String key, String labelKey, boolean required) {
        return new Field(key, labelKey, required);
    }

    /** Builds a filter choice. */
    public static Option option(String value, String labelKey) {
        return new Option(value, labelKey);
    }

    // **************************************************************************
    // *                             Builders                                   *
    // **************************************************************************

    /** Sets the language key for the page heading. */
    public WebPageSpec titleKey(String key) { this.titleKey = key; return this; }

    /** Sets the language key shown when the endpoint returns nothing. */
    public WebPageSpec emptyKey(String key) { this.emptyKey = key; return this; }

    /** Sets the language key shown when the endpoint fails. */
    public WebPageSpec errorKey(String key) { this.errorKey = key; return this; }

    /**
     * Names the JSON fields that may hold the row array, tried in order.
     * A bare JSON array is always accepted without declaring anything.
     */
    public WebPageSpec dataKeys(String... keys) {
        for (String k : keys) dataKeys.add(k);
        return this;
    }

    /** Names the JSON fields that may hold the total row count, for paging. */
    public WebPageSpec totalKeys(String... keys) {
        for (String k : keys) totalKeys.add(k);
        return this;
    }

    /** Adds statistic tiles. */
    public WebPageSpec tiles(Stat... tiles) {
        for (Stat s : tiles) stats.add(s);
        return this;
    }

    /**
     * Adds one plain statistic tile — the short form of {@link #tiles(Stat...)}, for the common
     * case of a label, a colour and the fields to read.
     */
    public WebPageSpec tile(String labelKey, String color, String... jsonKeys) {
        stats.add(WebPageSpec.stat(labelKey, jsonKeys).color(color));
        return this;
    }

    /** Adds the per-row action buttons, in display order. */
    public WebPageSpec actions(Action... buttons) {
        for (Action a : buttons) actions.add(a);
        return this;
    }

    /** Declares the creation form shown above the table. */
    public WebPageSpec form(String titleKey, String endpoint, Field... fields) {
        this.formTitleKey = titleKey;
        this.formEndpoint = endpoint;
        for (Field f : fields) formFields.add(f);
        return this;
    }

    /** Sets the submit button class of the creation form. Defaults to {@code danger}. */
    public WebPageSpec formStyle(String style) { this.formStyle = style; return this; }

    /**
     * Declares a page-wide button above the table — "clear everything", "restock all".
     *
     * @param labelKey   Language key for the button.
     * @param style      Button class: {@code danger}, {@code success}, {@code warning} or empty.
     * @param endpoint   The POST endpoint, called with an empty body.
     * @param confirmKey Language key for the confirmation prompt.
     */
    public WebPageSpec bulk(String labelKey, String style, String endpoint, String confirmKey) {
        this.bulkLabelKey = labelKey;
        this.bulkStyle = style;
        this.bulkEndpoint = endpoint;
        this.bulkConfirmKey = confirmKey;
        return this;
    }

    /**
     * Adds a search box sending {@code ?search=}.
     *
     * @param placeholderKey Language key for the placeholder.
     */
    public WebPageSpec search(String placeholderKey) {
        this.searchPlaceholderKey = placeholderKey;
        return this;
    }

    /**
     * Adds a drop-down filter next to the search box.
     *
     * @param param          The query parameter it sets.
     * @param placeholderKey Language key for the "no filter" entry.
     * @param options        The choices.
     */
    public WebPageSpec filter(String param, String placeholderKey, Option... options) {
        filters.add(new Filter(param, placeholderKey, List.of(options)));
        return this;
    }

    /**
     * Pages the table, sending {@code ?page=&limit=}.
     *
     * @param pageSize Rows per page.
     */
    public WebPageSpec paged(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    /**
     * Renders every boolean field of the payload as an enabled/disabled list.
     * Used by status pages whose feature set is not known in advance.
     */
    public WebPageSpec toggles(String titleKey, String emptyKey) {
        return toggles(titleKey, emptyKey, "");
    }

    /**
     * Renders the boolean fields of one nested object as an enabled/disabled list.
     *
     * @param fromKey The field holding them, or empty to read the payload itself.
     */
    public WebPageSpec toggles(String titleKey, String emptyKey, String fromKey) {
        this.togglesTitleKey = titleKey;
        this.togglesEmptyKey = emptyKey;
        this.togglesFrom = fromKey == null ? "" : fromKey;
        return this;
    }

    /** Renders the remaining scalar fields as a key/value list. */
    public WebPageSpec details(String titleKey) {
        this.detailsTitleKey = titleKey;
        return this;
    }

    /**
     * Adds a bar chart under the tiles of a {@link Type#STATS} page.
     *
     * @param titleKey   Language key for the card heading.
     * @param endpoint   Where the series comes from, or empty to reuse the page endpoint.
     * @param dataKey    The field holding the array, or empty to take the first array found.
     * @param labelField The field of each entry holding its label.
     * @param valueField The field of each entry holding its value.
     * @param emptyKey   Language key shown when the series is empty.
     */
    public WebPageSpec chart(String titleKey, String endpoint, String dataKey,
                             String labelField, String valueField, String emptyKey) {
        this.chartTitleKey = titleKey;
        this.chartEndpoint = endpoint;
        this.chartDataKey = dataKey;
        this.chartLabelField = labelField;
        this.chartValueField = valueField;
        this.chartEmptyKey = emptyKey;

        JsonObject c = new JsonObject();
        c.addProperty("titleKey", titleKey);
        c.addProperty("endpoint", endpoint == null ? "" : endpoint);
        c.addProperty("dataKey", dataKey == null ? "" : dataKey);
        c.addProperty("labelField", labelField);
        c.addProperty("valueField", valueField);
        c.addProperty("emptyKey", emptyKey == null ? "no-chart-data-available" : emptyKey);
        c.addProperty("maxPoints", 0);
        charts.add(c);
        return this;
    }

    /**
     * Draws a top-down grid of values — a map of where the load is, rather than how much of it
     * there is.
     *
     * <p>A table of the heaviest chunks answers "which one"; it never shows that forty of them sit
     * in the same corner of the same world. Rows need an X, a Z and a value; the dashboard scales
     * the colours to the range it receives.</p>
     *
     * @param titleKey   Language key for the card title.
     * @param endpoint   Endpoint to read, or empty to reuse the page's own.
     * @param dataKey    Key holding the rows in the response.
     * @param xField     Field holding the column coordinate.
     * @param zField     Field holding the row coordinate.
     * @param valueField Field holding the value that decides the colour.
     * @param emptyKey   Language key shown when there is nothing to draw.
     * @return This spec.
     */
    public WebPageSpec heatmap(String titleKey, String endpoint, String dataKey,
                               String xField, String zField, String valueField, String emptyKey) {
        this.heatmapTitleKey = titleKey;
        this.heatmapEndpoint = endpoint;
        this.heatmapDataKey = dataKey;
        this.heatmapXField = xField;
        this.heatmapZField = zField;
        this.heatmapValueField = valueField;
        this.heatmapEmptyKey = emptyKey;
        return this;
    }

    /**
     * Caps how many bars the chart draws, keeping an evenly spaced sample of the series.
     * A one-sample-per-10s history would otherwise draw hundreds of unreadable bars.
     *
     * @param maxPoints The most bars to draw, or 0 for no cap.
     */
    public WebPageSpec chartLimit(int maxPoints) {
        this.chartMaxPoints = maxPoints;
        if (!charts.isEmpty()) charts.get(charts.size() - 1).addProperty("maxPoints", maxPoints);
        return this;
    }

    // **************************************************************************
    // *                           Serialisation                                *
    // **************************************************************************

    /**
     * Serialises this page for {@code /api/modules}.
     *
     * @return The JSON the browser renders from.
     */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("type", type.name().toLowerCase());
        o.addProperty("endpoint", endpoint);
        o.addProperty("titleKey", titleKey);
        o.addProperty("emptyKey", emptyKey);
        o.addProperty("errorKey", errorKey);

        if (!dataKeys.isEmpty()) o.add("dataKeys", strings(dataKeys));
        if (!totalKeys.isEmpty()) o.add("totalKeys", strings(totalKeys));

        if (!stats.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (Stat s : stats) arr.add(s.toJson());
            o.add("stats", arr);
        }

        if (!actions.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (Action a : actions) arr.add(a.toJson());
            o.add("actions", arr);
        }

        if (formEndpoint != null) {
            JsonObject f = new JsonObject();
            f.addProperty("titleKey", formTitleKey);
            f.addProperty("endpoint", formEndpoint);
            f.addProperty("style", formStyle);
            JsonArray fields = new JsonArray();
            for (Field field : formFields) fields.add(field.toJson());
            f.add("fields", fields);
            o.add("form", f);
        }

        if (bulkEndpoint != null) {
            JsonObject b = new JsonObject();
            b.addProperty("labelKey", bulkLabelKey);
            b.addProperty("style", bulkStyle);
            b.addProperty("endpoint", bulkEndpoint);
            b.addProperty("confirmKey", bulkConfirmKey);
            o.add("bulk", b);
        }

        if (searchPlaceholderKey != null) o.addProperty("searchKey", searchPlaceholderKey);
        if (pageSize > 0) o.addProperty("pageSize", pageSize);

        if (!filters.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (Filter f : filters) {
                JsonObject fo = new JsonObject();
                fo.addProperty("param", f.param());
                fo.addProperty("placeholderKey", f.placeholderKey());
                JsonArray opts = new JsonArray();
                for (Option opt : f.options()) {
                    JsonObject oo = new JsonObject();
                    oo.addProperty("value", opt.value());
                    oo.addProperty("labelKey", opt.labelKey());
                    opts.add(oo);
                }
                fo.add("options", opts);
                arr.add(fo);
            }
            o.add("filters", arr);
        }

        if (togglesTitleKey != null) {
            JsonObject tg = new JsonObject();
            tg.addProperty("titleKey", togglesTitleKey);
            tg.addProperty("emptyKey", togglesEmptyKey == null ? "no-data" : togglesEmptyKey);
            tg.addProperty("from", togglesFrom);
            o.add("toggles", tg);
        }

        if (detailsTitleKey != null) {
            JsonObject d = new JsonObject();
            d.addProperty("titleKey", detailsTitleKey);
            o.add("details", d);
        }

        if (!charts.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (JsonObject c : charts) arr.add(c);
            o.add("charts", arr);
            // Kept for dashboards that only know about a single chart.
            o.add("chart", charts.get(0));
        }

        if (heatmapTitleKey != null) {
            JsonObject h = new JsonObject();
            h.addProperty("titleKey", heatmapTitleKey);
            h.addProperty("endpoint", heatmapEndpoint == null ? "" : heatmapEndpoint);
            h.addProperty("dataKey", heatmapDataKey == null ? "" : heatmapDataKey);
            h.addProperty("xField", heatmapXField);
            h.addProperty("zField", heatmapZField);
            h.addProperty("valueField", heatmapValueField);
            h.addProperty("emptyKey", heatmapEmptyKey == null ? "no-chart-data-available" : heatmapEmptyKey);
            o.add("heatmap", h);
        }

        return o;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray arr = new JsonArray();
        for (String v : values) arr.add(v);
        return arr;
    }
}
