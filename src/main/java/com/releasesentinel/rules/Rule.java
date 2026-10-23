package com.releasesentinel.rules;

import com.releasesentinel.model.CiEvent;
import com.releasesentinel.model.Finding;
import java.util.List;

public interface Rule { List<Finding> evaluate(CiEvent event); }
