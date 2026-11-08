package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;
import com.walter.claudewilson.model.Text;

/** This app's own ANDROID_ID; a readable value is the expected baseline. */
public final class AndroidIdProbe implements Probe {

    @Override
    public String id() {
        return "identity.android-id";
    }

    @Override
    public Layer layer() {
        return Layer.IDENTITY;
    }

    @Override
    public String field() {
        return "android_id";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        Text value = snapshot.androidId();
        if (!value.isReadable() || value.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "ANDROID_ID");
        }
        return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK, value.value());
    }
}
