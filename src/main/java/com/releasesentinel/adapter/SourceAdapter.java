package com.releasesentinel.adapter;

import com.releasesentinel.model.CiEvent;
import java.util.Map;

public interface SourceAdapter {
    CiEvent adapt(String rawInput, Map<String, String> headers);
}
