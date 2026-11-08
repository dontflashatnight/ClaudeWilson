package com.google.android.datatransport;
public class Event<T> {
    private final T payload;
    private Event(T payload) { this.payload = payload; }
    public static <T> Event<T> ofData(T payload) { return new Event<>(payload); }
    public static <T> Event<T> ofTelemetry(T payload) { return new Event<>(payload); }
    public static <T> Event<T> ofUrgent(T payload) { return new Event<>(payload); }
    public T getPayload() { return payload; }
}
