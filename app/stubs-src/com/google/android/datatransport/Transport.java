package com.google.android.datatransport;
public interface Transport<T> {
    void send(Event<T> event);
    void schedule(Event<T> event, TransportScheduleCallback callback);
}
