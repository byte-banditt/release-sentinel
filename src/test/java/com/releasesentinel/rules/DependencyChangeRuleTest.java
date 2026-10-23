package com.releasesentinel.rules;
import com.releasesentinel.model.*; import org.junit.jupiter.api.Test; import java.time.Instant; import java.util.List; import static org.junit.jupiter.api.Assertions.*;
class DependencyChangeRuleTest {
    private CiEvent e(String path,String patch) { return new CiEvent(1,"e","t","x","r",1,"s",List.of(new ChangedFile(path,"MODIFIED",patch)),"",Instant.now()); }
    @Test void reportsMajorBump() { assertFalse(new DependencyChangeRule().evaluate(e("package.json","-\"lib\": \"1.2.0\"\n+\"lib\": \"2.0.0\"")).isEmpty()); }
    @Test void reportsRemoval() { assertFalse(new DependencyChangeRule().evaluate(e("requirements.txt","-lib==1.2.0")).isEmpty()); }
    @Test void ignoresPatchBump() { assertTrue(new DependencyChangeRule().evaluate(e("package.json","-\"lib\": \"1.2.0\"\n+\"lib\": \"1.3.0\"")).isEmpty()); }
    @Test void ignoresDocumentation() { assertTrue(new DependencyChangeRule().evaluate(e("README.md","-lib 1.0\n+lib 2.0")).isEmpty()); }
}
