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
 * <h2>Permissions</h2>
 * Whoever reads the dashboard does so with their own in-game permissions. A module's pages open
 * with {@code xcore.dashboard.<module>}; a page that shows what not every member of staff may see
 * — addresses, accounts — names the permission reading it needs ({@link #readPermission}). Each
 * thing a page lets the reader change — a row action, the creation form, the page-wide button —
 * names the permission the matching command asks for in game ({@link Action#permission},
 * {@link #formPermission}, {@link #bulkPermission}); one that names none needs
 * {@code xcore.dashboard.<module>.manage}. Saving a configuration page needs
 * {@code xcore.dashboard.config.save} and what {@link #savePermission} names, and nothing short
 * of an operator when it names nothing. The panel checks them on every request, and leaves out of
 * what it sends a reader the pages and buttons that reader could not use.
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
 *         .send("player", "player_name", "target", "name")
 *         .permission("xbans.unban"))
 *     .form("ban-player", "/api/xbans/ban",
 *           WebPageSpec.field("player", "player", true).placeholderKey("player-name"),
 *           WebPageSpec.field("duration", "duration", false).placeholderKey("e-g-7d-30m-permanent"),
 *           WebPageSpec.field("reason", "reason", false).placeholderKey("reason-for-ban"))
 *     .formPermission("xbans.ban");
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
        CONFIG,
        /**
         * An inventory seen from above: rows of nine cells, an item in each, one container at a
         * time — a shop, a kit, a crate. See {@link WebPageSpec#grid(String)}.
         *
         * @since 2.1
         */
        GRID
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
        private final List<String> permission = new ArrayList<>();
        private final List<Field> fields = new ArrayList<>();
        private String kind = "post";
        private String titleKey = "";
        private String icon = "";
        private String chartLabelField = "";
        private String chartValueField = "";
        private String whenField = "";
        private final List<String> whenValues = new ArrayList<>();

        private Action(String labelKey, String endpoint) {
            this.labelKey = labelKey;
            this.endpoint = endpoint;
        }

        /**
         * Has the button open a form instead of asking "are you sure?": the fields, filled in
         * from the clicked row, and sent with what {@link #send} and {@link #constant} declare.
         *
         * <p>A field reads its starting value from the row fields named by {@link Field#from},
         * or from the row field of its own key when it names none. What the reader leaves empty
         * is sent as an empty string, so the endpoint can tell "cleared" from "not asked".</p>
         *
         * @param inputs The fields of the form, in display order.
         * @since 2.1
         */
        public Action fields(Field... inputs) {
            for (Field f : inputs) fields.add(f);
            return this;
        }

        /**
         * Sets the heading of the dialog the button opens; without it the dialog is titled by the
         * button and what the row is about ("Edit Stone").
         *
         * @since 2.1
         */
        public Action titleKey(String key) { this.titleKey = key == null ? "" : key; return this; }

        /**
         * Sets the icon shown on the button where a page draws its actions as buttons with
         * icons (the panel of a grid cell).
         *
         * @since 2.1
         */
        public Action icon(String name) { this.icon = name == null ? "" : name; return this; }

        /**
         * Turns the button into "show me": the endpoint is read with a GET, the declared
         * parameters in its query string, and the series it answers is drawn in a dialog.
         *
         * @param labelField The field of each point holding its label, a moment or a name.
         * @param valueField The field of each point holding its value.
         * @since 2.1
         */
        public Action chart(String labelField, String valueField) {
            this.kind = "chart";
            this.chartLabelField = labelField;
            this.chartValueField = valueField;
            return this;
        }

        /**
         * On a {@link Type#GRID} page, has the button ask for a destination: the reader clicks
         * another cell, and the request carries it as {@code to_page} and {@code to_slot} beside
         * the declared parameters. What moving onto an occupied cell does is the endpoint's call.
         *
         * @since 2.1
         */
        public Action pickSlot() { this.kind = "pick"; return this; }

        /**
         * Shows the button only on the rows where a field holds one of the given values —
         * "stop" on what is running, "restore" on what was removed. Compared as text.
         *
         * @param field  The row field to look at.
         * @param values The values that keep the button.
         * @since 2.1
         */
        public Action when(String field, String... values) {
            this.whenField = field == null ? "" : field;
            this.whenValues.clear();
            for (String v : values) this.whenValues.add(v);
            return this;
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

        /**
         * Names the permission the action needs: the one the matching command asks for in game, so
         * that unbanning from the dashboard takes exactly what {@code /unban} takes. Several nodes
         * mean any one of them — a command that also lets its plugin's administrator through.
         *
         * <p>An action that names none needs {@code xcore.dashboard.<module>.manage}.</p>
         *
         * @param nodes The permission, or its alternatives.
         */
        public Action permission(String... nodes) {
            addNodes(permission, nodes);
            return this;
        }

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
            if (!permission.isEmpty()) o.add("permission", strings(permission));
            if (!"post".equals(kind)) o.addProperty("kind", kind);
            if (!titleKey.isEmpty()) o.addProperty("titleKey", titleKey);
            if (!icon.isEmpty()) o.addProperty("icon", icon);
            if (!fields.isEmpty()) {
                JsonArray arr = new JsonArray();
                for (Field f : fields) arr.add(f.toJson());
                o.add("fields", arr);
            }
            if ("chart".equals(kind)) {
                JsonObject c = new JsonObject();
                c.addProperty("labelField", chartLabelField);
                c.addProperty("valueField", chartValueField);
                o.add("chart", c);
            }
            if (!whenField.isEmpty()) {
                JsonObject w = new JsonObject();
                w.addProperty("field", whenField);
                w.add("values", strings(whenValues));
                o.add("when", w);
            }
            return o;
        }

        /** @return Whether the button only reads: its endpoint is fetched, nothing is posted. */
        boolean reads() {
            return "chart".equals(kind);
        }
    }

    // **************************************************************************
    // *                          Page-wide buttons                             *
    // **************************************************************************

    /**
     * A button above a page's content, acting on the page as a whole: "restock everything",
     * "raise every price of a shop by ten percent", "export what I am looking at".
     *
     * <p>Build one with {@link WebPageSpec#button(String, String)} and add it with
     * {@link WebPageSpec#buttons(Button...)}. {@link WebPageSpec#bulk} still declares the single
     * button a 2.0 page could have; a page may use both.</p>
     *
     * @since 2.1
     */
    public static final class Button {
        private final String labelKey;
        private final String endpoint;
        private String style = "";
        private String confirmKey = "";
        private String icon = "";
        private String kind = "post";
        private final List<Field> fields = new ArrayList<>();
        private final List<String> permission = new ArrayList<>();
        private final List<Param> body = new ArrayList<>();

        private Button(String labelKey, String endpoint) {
            this.labelKey = labelKey;
            this.endpoint = endpoint;
        }

        /** Sets the button class: {@code danger}, {@code success}, {@code warning} or empty. */
        public Button style(String style) { this.style = style == null ? "" : style; return this; }

        /** Sets the sentence shown under the dialog's title before the request is sent. */
        public Button confirmKey(String key) { this.confirmKey = key == null ? "" : key; return this; }

        /** Sets the button's icon; one that fits its style is picked without it. */
        public Button icon(String name) { this.icon = name == null ? "" : name; return this; }

        /** Has the button ask for values first: the fields of its dialog, sent as the request body. */
        public Button fields(Field... inputs) {
            for (Field f : inputs) fields.add(f);
            return this;
        }

        /**
         * Turns the button into a download: the endpoint is read with a GET carrying the page's
         * current search and filters, and what it answers is saved as a file, named by the
         * response's {@code Content-Disposition}.
         */
        public Button download() { this.kind = "download"; return this; }

        /**
         * Adds a parameter with a fixed value to what the button sends — the body it posts with
         * its fields, or the query string of a {@link #download()} — as {@link Action#constant}
         * does for a row action.
         *
         * <p>What lets one endpoint tell two buttons apart: a "new shop" button sending
         * {@code create=true}, so that an id which already exists is refused instead of edited by
         * the route the grid's "edit" action shares. A field of the button's form under the same
         * name wins over it, as a field does over an action's declared parameters.</p>
         *
         * @param key   The name to send.
         * @param value The literal value.
         * @since 2.1
         */
        public Button constant(String key, String value) {
            body.add(new Param(key, List.of("=" + value)));
            return this;
        }

        /**
         * Names the permission the button needs, as {@link Action#permission} does for a row
         * action. Several nodes mean any one of them.
         */
        public Button permission(String... nodes) {
            addNodes(permission, nodes);
            return this;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("labelKey", labelKey);
            o.addProperty("endpoint", endpoint);
            o.addProperty("style", style);
            if (!confirmKey.isEmpty()) o.addProperty("confirmKey", confirmKey);
            if (!icon.isEmpty()) o.addProperty("icon", icon);
            if (!"post".equals(kind)) o.addProperty("kind", kind);
            if (!fields.isEmpty()) {
                JsonArray arr = new JsonArray();
                for (Field f : fields) arr.add(f.toJson());
                o.add("fields", arr);
            }
            if (!permission.isEmpty()) o.add("permission", strings(permission));
            // Written as an action writes its parameters, so the browser reads both the same way.
            if (!body.isEmpty()) o.add("body", paramsJson(body));
            return o;
        }

        /** @return Whether the button only reads: its endpoint is fetched, nothing is posted. */
        boolean reads() {
            return "download".equals(kind);
        }
    }

    // **************************************************************************
    // *                              Inputs                                    *
    // **************************************************************************

    /**
     * One input of a form: the creation form of a page, the form a row action or a page-wide
     * button opens.
     *
     * <p>A text box unless it says otherwise. Since 2.1 a field can be a number, a switch, a
     * list of choices, a currency of the server or one of its items, picked with its icon.</p>
     */
    public static final class Field {
        private final String key;
        private final String labelKey;
        private final boolean required;
        private String type = "text";
        private String placeholderKey = "";
        private String helpKey = "";
        private String value = "";
        private Double min;
        private Double max;
        private Double step;
        private final List<String> from = new ArrayList<>();
        private final List<Option> options = new ArrayList<>();
        private String optionsEndpoint = "";
        private String optionsDataKey = "";
        private String optionsValueField = "";
        private String optionsLabelField = "";

        private Field(String key, String labelKey, boolean required) {
            this.key = key;
            this.labelKey = labelKey;
            this.required = required;
        }

        /** Sets the HTML input type ({@code text}, {@code number}, …). */
        public Field type(String type) { this.type = type; return this; }

        /** Sets the language key for the placeholder. */
        public Field placeholderKey(String key) { this.placeholderKey = key; return this; }

        /**
         * A number. Sent as the text the reader typed, like every field: the endpoint parses it.
         *
         * @since 2.1
         */
        public Field number() { this.type = "number"; return this; }

        /**
         * Bounds a {@link #number()}: the browser refuses a value outside them, and the endpoint
         * checks again — a form is a convenience, never a guard.
         *
         * @param min The lowest value accepted.
         * @param max The highest value accepted.
         * @since 2.1
         */
        public Field range(double min, double max) {
            this.min = min;
            this.max = max;
            return this;
        }

        /**
         * Sets the step of a {@link #number()}: {@code 1} for whole numbers, {@code 0.01} for a price.
         *
         * @since 2.1
         */
        public Field step(double step) { this.step = step; return this; }

        /**
         * A switch. Sent as {@code "true"} or {@code "false"}.
         *
         * @since 2.1
         */
        public Field toggle() { this.type = "toggle"; return this; }

        /**
         * A list of choices known in advance.
         *
         * @param choices The choices, in display order.
         * @since 2.1
         */
        public Field select(Option... choices) {
            this.type = "select";
            for (Option o : choices) options.add(o);
            return this;
        }

        /**
         * A list of choices the server answers with: the shops there are, the kits, the worlds.
         *
         * @param endpoint   Where the choices are read, with a GET.
         * @param dataKey    The field holding the array, or empty to take the first array found.
         * @param valueField The field of each entry holding the value to send.
         * @param labelField The field of each entry holding what to show, or empty for the value.
         * @since 2.1
         */
        public Field selectFrom(String endpoint, String dataKey, String valueField, String labelField) {
            this.type = "select";
            this.optionsEndpoint = endpoint == null ? "" : endpoint;
            this.optionsDataKey = dataKey == null ? "" : dataKey;
            this.optionsValueField = valueField == null ? "" : valueField;
            this.optionsLabelField = labelField == null ? "" : labelField;
            return this;
        }

        /**
         * One of the server's currencies, read from {@code /api/currencies}: XCore's own, and the
         * economy another plugin registered with Vault when there is one. Sends the currency id.
         *
         * @since 2.1
         */
        public Field currency() { this.type = "currency"; return this; }

        /**
         * One of the server's items, searched by name in the reader's language and shown with its
         * icon. Sends the material name.
         *
         * @since 2.1
         */
        public Field item() { this.type = "item"; return this; }

        /**
         * Several lines of text: a list of commands, a lore. Sent as one string, lines separated
         * by {@code \n}.
         *
         * @since 2.1
         */
        public Field multiline() { this.type = "textarea"; return this; }

        /**
         * In a form opened from a row, names the row fields the starting value is read from,
         * tried in order. Without it the row field of the field's own key is read.
         *
         * @since 2.1
         */
        public Field from(String... rowFields) {
            for (String f : rowFields) from.add(f);
            return this;
        }

        /**
         * Sets the starting value where no row supplies one.
         *
         * @since 2.1
         */
        public Field value(String defaultValue) { this.value = defaultValue == null ? "" : defaultValue; return this; }

        /**
         * Sets the language key of a line of help shown under the field.
         *
         * @since 2.1
         */
        public Field helpKey(String key) { this.helpKey = key == null ? "" : key; return this; }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("key", key);
            o.addProperty("labelKey", labelKey);
            o.addProperty("type", type);
            o.addProperty("placeholderKey", placeholderKey);
            o.addProperty("required", required);
            if (!helpKey.isEmpty()) o.addProperty("helpKey", helpKey);
            if (!value.isEmpty()) o.addProperty("value", value);
            if (min != null) o.addProperty("min", min);
            if (max != null) o.addProperty("max", max);
            if (step != null) o.addProperty("step", step);
            if (!from.isEmpty()) o.add("from", strings(from));
            if (!options.isEmpty()) o.add("options", optionsJson(options));
            if (!optionsEndpoint.isEmpty()) {
                o.add("optionsFrom", sourceJson(optionsEndpoint, optionsDataKey, optionsValueField, optionsLabelField));
            }
            return o;
        }

        /** @return Where the field's choices are read, or an empty string when they are not read. */
        String optionsEndpoint() {
            return optionsEndpoint;
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

    /** A drop-down filter whose choices the server answers with. */
    private record DynamicFilter(String param, String placeholderKey, String endpoint, String dataKey,
                                 String valueField, String labelField) {}

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
    /** The fields a table shows, in order; empty for every field, as in 2.0. */
    private final List<String> columns = new ArrayList<>();
    private final List<Stat> stats = new ArrayList<>();
    private final List<Action> actions = new ArrayList<>();
    private String formTitleKey;
    private String formEndpoint;
    private String formStyle = "danger";
    private final List<Field> formFields = new ArrayList<>();
    private final List<String> formPermission = new ArrayList<>();
    private String bulkLabelKey;
    private String bulkStyle = "danger";
    private String bulkEndpoint;
    private String bulkConfirmKey;
    private final List<String> bulkPermission = new ArrayList<>();
    private final List<String> savePermission = new ArrayList<>();
    private final List<String> readPermission = new ArrayList<>();
    private String searchPlaceholderKey;
    private final List<Filter> filters = new ArrayList<>();
    private final List<DynamicFilter> dynamicFilters = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private final List<Action> containerActions = new ArrayList<>();
    private final List<JsonObject> lists = new ArrayList<>();
    private String containerParam = "container";
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
     * A page showing one container at a time as the game shows an inventory: rows of nine cells,
     * each with the icon of what it holds.
     *
     * <p>The endpoint is read with {@code ?container=<id>&page=<n>} (see {@link #containerParam})
     * and answers:</p>
     * <pre>
     * {
     *   "containers": [{"id": "blocks", "label": "Blocks", "material": "BRICKS", "count": 42}],
     *   "container": "blocks",          // the one shown: the one asked for, or the first
     *   "rows": 6, "page": 1, "pages": 3,
     *   "slots": [{"slot": 10, "material": "STONE", "label": "4 $", "sub": "1 $", …}],
     *   "locked": [45, 46, 47, 48, 49, 50, 51, 52, 53]
     * }
     * </pre>
     * <p>A slot is a row like a table's: {@code material} or {@code itemDetails} draws its icon,
     * {@code amount} the count in its corner, {@code label} and {@code sub} the two short lines
     * under it, {@code tone} a colour ({@code success}, {@code warning}, {@code danger}), and
     * every other field is listed in the panel a click opens, with the page's {@link #actions}.
     * A click on an empty cell opens the page's {@link #form}, which is sent the container, the
     * page and the slot besides its fields. {@code locked} cells take neither.</p>
     *
     * @param endpoint Where a container's cells are read.
     * @since 2.1
     */
    public static WebPageSpec grid(String endpoint) { return new WebPageSpec(Type.GRID, endpoint); }

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

    /**
     * Builds a page-wide button.
     *
     * @param labelKey Language key for the button.
     * @param endpoint The endpoint it posts to — or reads, for a {@link Button#download()}.
     * @since 2.1
     */
    public static Button button(String labelKey, String endpoint) {
        return new Button(labelKey, endpoint);
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
     * Names the permission the creation form needs, as {@link Action#permission} does for a row
     * action: the one the matching command asks for in game. Several nodes mean any one of them.
     *
     * @param nodes The permission, or its alternatives.
     */
    public WebPageSpec formPermission(String... nodes) {
        addNodes(formPermission, nodes);
        return this;
    }

    /**
     * Names the permission the page-wide button needs, as {@link Action#permission} does for a
     * row action. Several nodes mean any one of them.
     *
     * @param nodes The permission, or its alternatives.
     */
    public WebPageSpec bulkPermission(String... nodes) {
        addNodes(bulkPermission, nodes);
        return this;
    }

    /**
     * On a {@link #config()} page, names the permission saving the file needs. Saving reloads the
     * plugin, so the natural choice is what its reload command asks for. Several nodes mean any
     * one of them.
     *
     * <p>Reading the file needs {@code xcore.dashboard.config} besides the module's own
     * permission; saving it needs {@code xcore.dashboard.config.save} and this as well. A module
     * that names nothing — here or in {@link WebModule#getConfigSavePermission()} — can only have
     * its file saved by an operator: a configuration can make a plugin run console commands, and
     * saving one is trusted like the console.</p>
     *
     * @param nodes The permission, or its alternatives.
     */
    public WebPageSpec savePermission(String... nodes) {
        addNodes(savePermission, nodes);
        return this;
    }

    /**
     * Names the permission reading the page needs, besides {@code xcore.dashboard.<module>}: the
     * one the matching command asks for in game, for a page that shows what not every member of
     * staff may see — the addresses players connect from, their accounts. Several nodes mean any
     * one of them.
     *
     * <p>It covers every endpoint the page reads — its own, its charts' and its heat map's —
     * whatever the method. A reader who lacks it does not get the page at all, its buttons
     * included; a link to it says which permission it needs.</p>
     *
     * @param nodes The permission, or its alternatives.
     */
    public WebPageSpec readPermission(String... nodes) {
        addNodes(readPermission, nodes);
        return this;
    }

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
     * Adds a drop-down filter whose choices the server answers with — the shops there are, the
     * currencies in use — next to the search box of a table, or above the tiles of a
     * {@link Type#STATS} page, which is then read again with the choice in its query string.
     *
     * @param param          The query parameter it sets.
     * @param placeholderKey Language key for the "no filter" entry.
     * @param endpoint       Where the choices are read, with a GET.
     * @param dataKey        The field holding the array, or empty to take the first array found.
     * @param valueField     The field of each entry holding the value to send.
     * @param labelField     The field of each entry holding what to show, or empty for the value.
     * @since 2.1
     */
    public WebPageSpec filterFrom(String param, String placeholderKey, String endpoint, String dataKey,
                                  String valueField, String labelField) {
        dynamicFilters.add(new DynamicFilter(param, placeholderKey, endpoint == null ? "" : endpoint,
                dataKey == null ? "" : dataKey, valueField == null ? "" : valueField,
                labelField == null ? "" : labelField));
        return this;
    }

    /**
     * Adds page-wide buttons, in display order.
     *
     * @since 2.1
     */
    public WebPageSpec buttons(Button... added) {
        for (Button b : added) buttons.add(b);
        return this;
    }

    /**
     * On a {@link Type#GRID} page, names the query parameter the container is asked by, and the
     * body parameter it is sent as by the form, the cell actions and the buttons.
     * {@code container} without it.
     *
     * @since 2.1
     */
    public WebPageSpec containerParam(String param) {
        if (param != null && !param.isBlank()) this.containerParam = param.trim();
        return this;
    }

    /**
     * On a {@link Type#GRID} page, adds the buttons that act on the container being shown —
     * rename it, delete it — beside the list of containers. Their parameters are read from the
     * container's entry in {@code containers}, as a row action reads its row.
     *
     * @since 2.1
     */
    public WebPageSpec containerActions(Action... added) {
        for (Action a : added) containerActions.add(a);
        return this;
    }

    /**
     * Adds a short table under the tiles and charts of a {@link Type#STATS} page: the ten best
     * sellers, the last few events. Its rows are drawn as a table's are, icons and faces included.
     *
     * @param titleKey Language key for the card heading.
     * @param endpoint Where the rows come from, or empty to reuse the page endpoint.
     * @param dataKey  The field holding the array, or empty to take the first array found.
     * @param emptyKey Language key shown when there is nothing to list.
     * @since 2.1
     */
    public WebPageSpec list(String titleKey, String endpoint, String dataKey, String emptyKey) {
        JsonObject l = new JsonObject();
        l.addProperty("titleKey", titleKey);
        l.addProperty("endpoint", endpoint == null ? "" : endpoint);
        l.addProperty("dataKey", dataKey == null ? "" : dataKey);
        l.addProperty("emptyKey", emptyKey == null ? "no-data-found" : emptyKey);
        lists.add(l);
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
     * Names the fields a {@link Type#TABLE} page shows, in that order: its columns on a wide
     * screen, the lines of each row's card on a phone. Without it every field of the rows is a
     * column, as in 2.0.
     *
     * <p>Only what is shown changes. Each row keeps all its fields, and everything that reads
     * them still finds them: the form a row action opens ({@link Field#from}), the parameters it
     * sends ({@link Action#send}), {@link Action#when}, a chart. The search and the filters are
     * the endpoint's own, so a hidden field can still be searched or filtered on. A row is
     * typically a product with its prices at a glance and, for its edit form, a dozen settings
     * that need not take a column each.</p>
     *
     * <p>{@code "item"} names the item column: the row's {@code itemDetails}, or else the
     * material one of its fields names ({@code material}, {@code item_id}, {@code item_material},
     * {@code item_type}), drawn with its icon and its name in the reader's language — the column
     * the dashboard otherwise puts first by itself. A row that has neither shows its own field
     * {@code item}, when it has one.</p>
     *
     * <p>The first field named is the one a row is about: it heads each card on a phone. A name
     * that no row has makes an empty column, the same on every page of the table.</p>
     *
     * @param fields The fields to show, in display order.
     * @since 2.1
     */
    public WebPageSpec columns(String... fields) {
        if (fields == null) return this;
        for (String field : fields) {
            if (field == null || field.isBlank()) continue;
            String clean = field.trim();
            if (!columns.contains(clean)) columns.add(clean);
        }
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
        if (!columns.isEmpty()) o.add("columns", strings(columns));

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
            if (!formPermission.isEmpty()) f.add("permission", strings(formPermission));
            o.add("form", f);
        }

        if (bulkEndpoint != null) {
            JsonObject b = new JsonObject();
            b.addProperty("labelKey", bulkLabelKey);
            b.addProperty("style", bulkStyle);
            b.addProperty("endpoint", bulkEndpoint);
            b.addProperty("confirmKey", bulkConfirmKey);
            if (!bulkPermission.isEmpty()) b.add("permission", strings(bulkPermission));
            o.add("bulk", b);
        }

        if (type == Type.CONFIG && !savePermission.isEmpty()) o.add("savePermission", strings(savePermission));
        if (!readPermission.isEmpty()) o.add("readPermission", strings(readPermission));

        if (searchPlaceholderKey != null) o.addProperty("searchKey", searchPlaceholderKey);
        if (pageSize > 0) o.addProperty("pageSize", pageSize);

        if (!filters.isEmpty() || !dynamicFilters.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (Filter f : filters) {
                JsonObject fo = new JsonObject();
                fo.addProperty("param", f.param());
                fo.addProperty("placeholderKey", f.placeholderKey());
                fo.add("options", optionsJson(f.options()));
                arr.add(fo);
            }
            for (DynamicFilter f : dynamicFilters) {
                JsonObject fo = new JsonObject();
                fo.addProperty("param", f.param());
                fo.addProperty("placeholderKey", f.placeholderKey());
                fo.add("options", new JsonArray());
                fo.add("optionsFrom", sourceJson(f.endpoint(), f.dataKey(), f.valueField(), f.labelField()));
                arr.add(fo);
            }
            o.add("filters", arr);
        }

        if (!buttons.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (Button b : buttons) arr.add(b.toJson());
            o.add("buttons", arr);
        }

        if (type == Type.GRID) {
            JsonObject g = new JsonObject();
            g.addProperty("containerParam", containerParam);
            o.add("grid", g);
            if (!containerActions.isEmpty()) {
                JsonArray arr = new JsonArray();
                for (Action a : containerActions) arr.add(a.toJson());
                o.add("containerActions", arr);
            }
        }

        if (!lists.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (JsonObject l : lists) arr.add(l);
            o.add("lists", arr);
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

    /** Parameters of a request body as the browser reads them: {@code [{"param", "from": [...]}]}. */
    private static JsonArray paramsJson(List<Param> params) {
        JsonArray arr = new JsonArray();
        for (Param p : params) {
            JsonObject po = new JsonObject();
            po.addProperty("param", p.param());
            po.add("from", strings(p.from()));
            arr.add(po);
        }
        return arr;
    }

    private static JsonArray optionsJson(List<Option> options) {
        JsonArray opts = new JsonArray();
        for (Option opt : options) {
            JsonObject oo = new JsonObject();
            oo.addProperty("value", opt.value());
            oo.addProperty("labelKey", opt.labelKey());
            opts.add(oo);
        }
        return opts;
    }

    /** Where a list of choices is read, and how each entry reads. */
    private static JsonObject sourceJson(String endpoint, String dataKey, String valueField, String labelField) {
        JsonObject o = new JsonObject();
        o.addProperty("endpoint", endpoint);
        o.addProperty("dataKey", dataKey);
        o.addProperty("valueField", valueField);
        o.addProperty("labelField", labelField);
        return o;
    }

    /**
     * Adds permission nodes to a list, trimmed and in lower case — the server compares them
     * ignoring case — leaving out blanks and repeats.
     */
    private static void addNodes(List<String> into, String... nodes) {
        if (nodes == null) return;
        for (String node : nodes) {
            if (node == null) continue;
            String clean = node.trim().toLowerCase(java.util.Locale.ROOT);
            if (!clean.isEmpty() && !into.contains(clean)) into.add(clean);
        }
    }

    // **************************************************************************
    // *                      What the panel reads back                         *
    // **************************************************************************

    /** @return What kind of page this is. */
    Type type() {
        return type;
    }

    /**
     * Hands each endpoint this page posts to, with the permission declared for it, to
     * {@code sink}: the row actions, the creation form and the page-wide button. An endpoint
     * declared without a permission is left out — the panel then asks for the module's manage
     * permission.
     *
     * @param sink Receives the endpoint and the nodes, any one of which lets the reader through.
     */
    void declaredPermissions(java.util.function.BiConsumer<String, List<String>> sink) {
        // What only reads (a chart, a download) is judged as a read, in readingPermissions: as a
        // write rule its permission applied to every method on its path, a POST included.
        for (Action action : actions) {
            if (!action.permission.isEmpty() && !action.reads()) sink.accept(action.endpoint, List.copyOf(action.permission));
        }
        for (Action action : containerActions) {
            if (!action.permission.isEmpty() && !action.reads()) sink.accept(action.endpoint, List.copyOf(action.permission));
        }
        for (Button button : buttons) {
            if (!button.permission.isEmpty() && !button.reads()) sink.accept(button.endpoint, List.copyOf(button.permission));
        }
        if (formEndpoint != null && !formPermission.isEmpty()) sink.accept(formEndpoint, List.copyOf(formPermission));
        if (bulkEndpoint != null && !bulkPermission.isEmpty()) sink.accept(bulkEndpoint, List.copyOf(bulkPermission));
    }

    /**
     * The endpoints of the buttons that only read — a chart a row opens, a download — which are
     * judged as a read is: {@code xcore.dashboard.<module>.manage} is not asked of them when they
     * declare no permission.
     *
     * @return The endpoints, without repeats.
     */
    List<String> readingActionEndpoints() {
        List<String> out = new ArrayList<>();
        for (Action action : actions) {
            if (action.reads() && !out.contains(action.endpoint)) out.add(action.endpoint);
        }
        for (Action action : containerActions) {
            if (action.reads() && !out.contains(action.endpoint)) out.add(action.endpoint);
        }
        for (Button button : buttons) {
            if (button.reads() && !out.contains(button.endpoint)) out.add(button.endpoint);
        }
        return out;
    }

    /**
     * Hands each endpoint a reading action or button gets, with the permission it declares, to
     * {@code sink}: a read rule, asked of a GET only. A reading button on the very path of its
     * page shares the page's rule: a path does not tell its query strings apart.
     *
     * @param sink Receives the endpoint and the nodes, any one of which lets the reader through.
     */
    void readingPermissions(java.util.function.BiConsumer<String, List<String>> sink) {
        for (Action action : actions) {
            if (!action.permission.isEmpty() && action.reads()) sink.accept(action.endpoint, List.copyOf(action.permission));
        }
        for (Action action : containerActions) {
            if (!action.permission.isEmpty() && action.reads()) sink.accept(action.endpoint, List.copyOf(action.permission));
        }
        for (Button button : buttons) {
            if (!button.permission.isEmpty() && button.reads()) sink.accept(button.endpoint, List.copyOf(button.permission));
        }
    }

    /**
     * The module's own lists this page reads its choices from: a filter's, a field's (the
     * creation form, a row action, a container action, a page button). Only a module's own,
     * {@code /api/<module>/...}: XCore's shared lists ({@code /api/currencies}, {@code /api/items})
     * serve every page and keep their own rules.
     *
     * @return The endpoints, without repeats.
     */
    List<String> optionEndpoints() {
        List<String> out = new ArrayList<>();
        for (DynamicFilter filter : dynamicFilters) addOwn(out, filter.endpoint());
        for (Field field : formFields) addOwn(out, field.optionsEndpoint());
        for (Action action : actions) for (Field field : action.fields) addOwn(out, field.optionsEndpoint());
        for (Action action : containerActions) for (Field field : action.fields) addOwn(out, field.optionsEndpoint());
        for (Button button : buttons) for (Field field : button.fields) addOwn(out, field.optionsEndpoint());
        return out;
    }

    /** Adds an endpoint when it is a module's own: {@code /api/<module>/<route>}. */
    private static void addOwn(List<String> out, String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return;
        String path = endpoint.split("[?#]", 2)[0];
        String[] parts = path.split("/");
        if (parts.length < 4 || !parts[0].isEmpty() || !"api".equals(parts[1])) return;
        if (!out.contains(endpoint)) out.add(endpoint);
    }

    /** @return On a configuration page, what saving it needs; empty anywhere else, or when nothing was declared. */
    List<String> savePermissions() {
        return type == Type.CONFIG ? List.copyOf(savePermission) : List.of();
    }

    /** @return What reading the page needs besides its module's permission; empty when it declared nothing. */
    List<String> readPermissions() {
        return List.copyOf(readPermission);
    }

    /**
     * Every endpoint the page reads: its own, and each chart's and its heat map's when they have
     * one of their own. An empty one means the page's own, and a configuration page reads none.
     *
     * @return The endpoints, without repeats.
     */
    List<String> readEndpoints() {
        List<String> out = new ArrayList<>();
        if (endpoint != null && !endpoint.isBlank()) out.add(endpoint);
        for (JsonObject chart : charts) {
            String own = chart.has("endpoint") ? chart.get("endpoint").getAsString() : "";
            if (!own.isBlank() && !out.contains(own)) out.add(own);
        }
        if (heatmapEndpoint != null && !heatmapEndpoint.isBlank() && !out.contains(heatmapEndpoint)) out.add(heatmapEndpoint);
        for (JsonObject list : lists) {
            String own = list.has("endpoint") ? list.get("endpoint").getAsString() : "";
            if (!own.isBlank() && !out.contains(own)) out.add(own);
        }
        for (String reading : readingActionEndpoints()) {
            if (!reading.isBlank() && !out.contains(reading)) out.add(reading);
        }
        return out;
    }

    /** @return The endpoint the page is built from, empty for a configuration page. */
    String endpoint() {
        return endpoint == null ? "" : endpoint;
    }
}
