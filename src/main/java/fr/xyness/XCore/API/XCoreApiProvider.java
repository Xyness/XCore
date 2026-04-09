package fr.xyness.XCore.API;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Static accessor for the XCore public API.
 * <p>
 * External plugins should use {@link #get()} to obtain the API instance after
 * verifying availability with {@link #isRegistered()}.
 * The API is registered during {@code onLoad()} and unregistered during {@code onDisable()}.
 * </p>
 *
 * <pre>{@code
 * if (XCoreApiProvider.isRegistered()) {
 *     XCoreApi api = XCoreApiProvider.get();
 *     api.getPlayerAsync(uuid).thenAccept(opt -> { ... });
 * }
 * }</pre>
 */
public final class XCoreApiProvider {

	/** Thread-safe holder for the singleton API instance. */
    private static final AtomicReference<XCoreApi> api = new AtomicReference<>();

	/** Private constructor to prevent instantiation. */
    private XCoreApiProvider() {}

	/**
	 * Returns the registered API instance.
	 *
	 * @return The {@link XCoreApi} instance.
	 * @throws IllegalStateException If the API has not been registered yet.
	 */
    public static XCoreApi get() {
        XCoreApi instance = api.get();
        if (instance == null) {
            throw new IllegalStateException("XCore API is not loaded yet!");
        }
        return instance;
    }

	/**
	 * Checks whether the API has been registered and is available for use.
	 *
	 * @return {@code true} if the API is registered.
	 */
    public static boolean isRegistered() {
    	return api.get() != null;
    }

	/**
	 * Registers the API instance. Called internally during plugin {@code onLoad()}.
	 *
	 * @param instance The API implementation to register.
	 * @throws IllegalStateException If an API instance is already registered.
	 */
    public static void register(XCoreApi instance) {
        if (!api.compareAndSet(null, instance)) {
            throw new IllegalStateException("XCore API is already registered!");
        }
    }

	/**
	 * Unregisters the API instance. Called internally during plugin {@code onDisable()}.
	 */
    public static void unregister() {
        api.set(null);
    }

}
