package fr.xyness.XCore.Gui;

import java.util.Collections;
import java.util.List;

/**
 * Generic paginator for splitting a list of items into pages.
 *
 * @param <T> The type of items being paginated.
 */
public class Pagination<T> {

    /** The full list of items to paginate. */
    private final List<T> items;

    /** The number of items displayed per page. */
    private final int itemsPerPage;

    /**
     * Creates a new paginator.
     *
     * @param items        The full list of items.
     * @param itemsPerPage The number of items per page (must be greater than 0).
     */
    public Pagination(List<T> items, int itemsPerPage) {
        this.items = items;
        this.itemsPerPage = Math.max(1, itemsPerPage);
    }

    /**
     * Returns the maximum page number (1-based).
     *
     * @return The total number of pages, minimum 1.
     */
    public int getMaxPage() {
        return Math.max(1, (int) Math.ceil((double) items.size() / itemsPerPage));
    }

    /**
     * Returns the items for the given page (1-based).
     *
     * @param page The page number (1-based).
     * @return A sublist of items for that page, or an empty list if out of range.
     */
    public List<T> getPage(int page) {
        if (page < 1 || items.isEmpty()) return Collections.emptyList();
        int start = (page - 1) * itemsPerPage;
        if (start >= items.size()) return Collections.emptyList();
        int end = Math.min(start + itemsPerPage, items.size());
        return items.subList(start, end);
    }

    /**
     * Checks whether a next page exists after the given page.
     *
     * @param page The current page number (1-based).
     * @return {@code true} if there are more items beyond this page.
     */
    public boolean hasNextPage(int page) {
        return page < getMaxPage();
    }

    /**
     * Checks whether a previous page exists before the given page.
     *
     * @param page The current page number (1-based).
     * @return {@code true} if the page is greater than 1.
     */
    public boolean hasPreviousPage(int page) {
        return page > 1;
    }
}
