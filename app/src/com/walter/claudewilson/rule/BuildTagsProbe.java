package com.walter.claudewilson.rule;

import com.walter.claudewilson.model.Finding;
import com.walter.claudewilson.model.Layer;
import com.walter.claudewilson.model.Probe;
import com.walter.claudewilson.model.Reason;
import com.walter.claudewilson.model.Snapshot;
import com.walter.claudewilson.model.Status;

/** Build signing tags: a release build of a locked device signs with release-keys. */
public final class BuildTagsProbe implements Probe {

    @Override
    public String id() {
        return "env.build-tags";
    }

    @Override
    public Layer layer() {
        return Layer.ENV_EVIDENCE;
    }

    @Override
    public String field() {
        return "build_tags";
    }

    @Override
    public Finding run(Snapshot snapshot) {
        String tags = snapshot.build().tags();
        if (tags.isEmpty()) {
            return Finding.of(id(), layer(), field(), Status.UNKNOWN, Reason.UNREADABLE,
                    "Build.TAGS");
        }
        if (tags.contains("test-keys")) {
            return Finding.of(id(), layer(), field(), Status.FAIL, Reason.TEST_KEYS, tags);
        }
        return Finding.of(id(), layer(), field(), Status.PASS, Reason.OK, tags);
    }
}
