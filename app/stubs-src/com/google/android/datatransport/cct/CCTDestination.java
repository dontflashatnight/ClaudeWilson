package com.google.android.datatransport.cct;
import com.google.android.datatransport.runtime.Destination;
public class CCTDestination implements Destination {
    public static final CCTDestination INSTANCE = new CCTDestination();
    public static CCTDestination fromByteArray(byte[] bytes) { return INSTANCE; }
    public String getName() { return "cct"; }
    public byte[] getExtras() { return null; }
}
