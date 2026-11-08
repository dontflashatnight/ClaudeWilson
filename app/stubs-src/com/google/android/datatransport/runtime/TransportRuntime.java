package com.google.android.datatransport.runtime;
import android.content.Context;
import com.google.android.datatransport.Encoding;
import com.google.android.datatransport.Event;
import com.google.android.datatransport.Transport;
import com.google.android.datatransport.TransportFactory;
import com.google.android.datatransport.TransportScheduleCallback;
import com.google.android.datatransport.Transformer;

public class TransportRuntime {
    private static final TransportRuntime INSTANCE = new TransportRuntime();

    private static final Transport<Object> NOOP = new Transport<Object>() {
        public void send(Event<Object> event) {}
        public void schedule(Event<Object> event,
                TransportScheduleCallback callback) {}
    };

    private static final TransportFactory FACTORY = new TransportFactory() {
        @SuppressWarnings("unchecked")
        public <T> Transport<T> getTransport(String name, Class<T> payloadType,
                Encoding payloadEncoding, Transformer<T, byte[]> transformer) {
            return (Transport<T>) NOOP;
        }
    };

    public static void initialize(Context context) {}
    public static TransportRuntime getInstance() { return INSTANCE; }
    public TransportFactory newFactory(Destination destination) { return FACTORY; }
    public TransportFactory newFactory(String name) { return FACTORY; }
}
