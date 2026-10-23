package com.releasesentinel.rules;
import com.releasesentinel.model.*; import org.junit.jupiter.api.Test; import java.time.Instant; import java.util.List; import static org.junit.jupiter.api.Assertions.*;
class MigrationRollbackRuleTest {
    private CiEvent e(ChangedFile... fs) { return new CiEvent(1,"e","t","x","r",1,"s",List.of(fs),"",Instant.now()); }
    @Test void reportsMissingRollback() { assertEquals(1,new MigrationRollbackRule().evaluate(e(new ChangedFile("db/migrations/001_add.sql","ADDED",""))).size()); }
    @Test void acceptsDownScript() { assertTrue(new MigrationRollbackRule().evaluate(e(new ChangedFile("db/migrations/001_add.sql","ADDED",""),new ChangedFile("db/migrations/001_down.sql","ADDED",""))).isEmpty()); }
    @Test void ignoresNonMigrationSql() { assertTrue(new MigrationRollbackRule().evaluate(e(new ChangedFile("scripts/report.sql","ADDED",""))).isEmpty()); }
}
