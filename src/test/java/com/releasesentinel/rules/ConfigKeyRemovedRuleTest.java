package com.releasesentinel.rules;
import com.releasesentinel.model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ConfigKeyRemovedRuleTest {
    private CiEvent e(String path, String patch) { return new CiEvent(1,"e","t","x","r",1,"sha",List.of(new ChangedFile(path,"MODIFIED",patch)),"", Instant.now()); }
    @Test void reportsRemovedKey() { assertEquals(1, new ConfigKeyRemovedRule().evaluate(e("application.yml","-server.port: 8080\n+server.host: x")).size()); }
    @Test void ignoresCommentAndChangedValue() { assertTrue(new ConfigKeyRemovedRule().evaluate(e("application.yml","-# old.key: x\n-old.key: x\n+old.key: y")).isEmpty()); }
    @Test void ignoresJavaFile() { assertTrue(new ConfigKeyRemovedRule().evaluate(e("A.java","-foo: bar")).isEmpty()); }
}
