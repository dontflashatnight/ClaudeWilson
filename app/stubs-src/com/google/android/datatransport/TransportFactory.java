package com.google.android.datatransport;
public interface TransportFactory {
    <T> Transport<T> getTransport(String name, Class<T> payloadType,
            Encoding payloadEncoding, Transformer<T, byte[]> transformer);
}
