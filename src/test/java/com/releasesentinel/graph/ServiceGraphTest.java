package com.releasesentinel.graph;
import com.releasesentinel.model.*; import org.junit.jupiter.api.Test; import java.time.Instant; import java.util.List; import static org.junit.jupiter.api.Assertions.*;
class ServiceGraphTest {
    @Test void findsTransitiveDependentsAndStopsCycles() { var g=ServiceGraph.fromYaml("services:\n  - name: db\n    paths: [db/**]\n    dependsOn: [api]\n  - name: api\n    paths: [api/**]\n    dependsOn: [web]\n  - name: web\n    paths: [web/**]\n    dependsOn: [db]\n"); var e=new CiEvent(1,"e","t","x","r",1,"s",List.of(new ChangedFile("db/a.sql","MODIFIED","")),"",Instant.now()); assertEquals(2,g.blastRadius(e).size()); }
    @Test void declaredGraphDoesNotInferOwnership() { var g=ServiceGraph.fromYaml("services:\n  - name: api\n    paths: [api/**]\n    dependsOn: []\n"); var e=new CiEvent(1,"e","t","x","r",1,"s",List.of(new ChangedFile("other/a.java","MODIFIED","")),"",Instant.now()); assertTrue(g.blastRadius(e).isEmpty()); }
}
