package dev.enginehost.api;

import java.util.Objects;

/** Host-normalized controller input, after the user's global remapping. */
public final class EngineControllerEvent {
    private final String action;
    private final float value;
    private final int deviceId;
    private final String deviceDescriptor;
    private final long eventTime;

    public EngineControllerEvent(
            String action, float value, int deviceId, String deviceDescriptor, long eventTime) {
        this.action = Objects.requireNonNull(action);
        this.value = Math.max(-1.0f, Math.min(1.0f, value));
        this.deviceId = deviceId;
        this.deviceDescriptor = Objects.requireNonNull(deviceDescriptor);
        this.eventTime = eventTime;
    }

    public String action() { return action; }
    /** Digital inputs use 0 or 1; axes use the full -1..1 range. */
    public float value() { return value; }
    public boolean pressed() { return value > 0.5f; }
    public int deviceId() { return deviceId; }
    public String deviceDescriptor() { return deviceDescriptor; }
    public long eventTime() { return eventTime; }
}
