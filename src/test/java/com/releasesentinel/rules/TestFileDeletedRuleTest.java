package com.releasesentinel.rules;
import com.releasesentinel.model.*; import org.junit.jupiter.api.Test; import java.time.Instant; import java.util.List; import static org.junit.jupiter.api.Assertions.*;
class TestFileDeletedRuleTest {
    private CiEvent e(String path,String status) { return new CiEvent(1,"e","t","x","r",1,"s",List.of(new ChangedFile(path,status,"")),"",Instant.now()); }
    @Test void reportsDeletedTest() { assertEquals(1,new TestFileDeletedRule().evaluate(e("src/test/FooTest.java","DELETED")).size()); }
    @Test void ignoresDeletedProduction() { assertTrue(new TestFileDeletedRule().evaluate(e("src/main/Foo.java","DELETED")).isEmpty()); }
    @Test void ignoresModifiedTest() { assertTrue(new TestFileDeletedRule().evaluate(e("src/test/FooTest.java","MODIFIED")).isEmpty()); }
}
