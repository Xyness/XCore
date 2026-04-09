package fr.xyness.XCore.Gui;

import fr.xyness.XCore.Utils.SchedulerAdapter;

/**
 * Manages a blink timer that alternates a boolean state on each tick.
 * <p>
 * Used by GUI sessions to create blinking button effects. The controller
 * toggles its internal {@link #state} flag each time the timer fires and
 * invokes a callback so the GUI can update its item lore or materials.
 * </p>
 */
public class BlinkController {

    /** The current blink state (alternates between {@code true} and {@code false}). */
    private boolean state = true;

    /** The opaque task handle returned by the scheduler, used for cancellation. */
    private Object taskHandle;

    /**
     * Starts the blink timer.
     *
     * @param scheduler     The scheduler adapter to use for task scheduling.
     * @param tickCallback  The callback invoked after each state toggle.
     * @param intervalTicks The interval in ticks between each toggle.
     */
    public void start(SchedulerAdapter scheduler, Runnable tickCallback, long intervalTicks) {
        stop(scheduler);
        taskHandle = scheduler.runAsyncTaskTimer(() -> {
            state = !state;
            tickCallback.run();
        }, intervalTicks, intervalTicks);
    }

    /**
     * Stops the blink timer if one is running.
     *
     * @param scheduler The scheduler adapter used to cancel the task.
     */
    public void stop(SchedulerAdapter scheduler) {
        if (taskHandle != null) {
            scheduler.cancelTask(taskHandle);
            taskHandle = null;
        }
    }

    /**
     * Returns the current blink state.
     * <p>
     * Alternates between {@code true} and {@code false} on each tick.
     * </p>
     *
     * @return The current state.
     */
    public boolean getState() {
        return state;
    }
}
