package com.releasesentinel.rules;

import com.releasesentinel.model.ChangedFile;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

final class DiffLines {
    private DiffLines() { }
    static List<String> removed(ChangedFile file) { return lines(file.patch(), "-"); }
    static List<String> added(ChangedFile file) { return lines(file.patch(), "+"); }
    private static List<String> lines(String patch, String marker) {
        return Arrays.stream(patch.split("\\R"))
                .filter(line -> line.startsWith(marker) && !line.startsWith(marker + marker))
                .map(line -> line.substring(1)).toList();
    }
    static boolean pathMatches(String path, Pattern pattern) { return pattern.matcher(path).find(); }
}
