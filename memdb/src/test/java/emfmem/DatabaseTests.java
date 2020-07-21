package emfmem;

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import ru.neoflex.emf.memdb.MemDBServer;
import ru.neoflex.emf.memdb.test.Group;
import ru.neoflex.emf.memdb.test.TestFactory;
import ru.neoflex.emf.memdb.test.TestPackage;
import ru.neoflex.emf.memdb.test.User;

import java.io.IOException;

public class DatabaseTests extends TestBase {
    @Before
    public void startUp() throws Exception {
        memDBServer = refreshDatabase();
    }

    @After
    public void shutDown() throws IOException {
        memDBServer.close();
    }

    @Test
    public void createEMFObject() throws Exception {
        Group group = TestFactory.eINSTANCE.createGroup();
        String[] ids = memDBServer.inTransaction(false, (MemDBServer.TxFunction<String[]>) tx -> {
            group.setName("masters");
            ResourceSet resourceSet = tx.createResourceSet();
            Resource groupResource = resourceSet.createResource(memDBServer.createURI(""));
            groupResource.getContents().add(group);
            groupResource.save(null);
            String groupId = memDBServer.getId(groupResource.getURI());
            User user = TestFactory.eINSTANCE.createUser();
            user.setName("Orlov");
            user.setGroup(group);
            Resource userResource = resourceSet.createResource(memDBServer.createURI(""));
            userResource.getContents().add(user);
            userResource.save(null);
            String userId = memDBServer.getId(userResource.getURI());
            Assert.assertNotNull(userId);
            return new String[]{userId, groupId};
        });
        memDBServer.inTransaction(false, (MemDBServer.TxFunction<Void>) tx -> {
            ResourceSet resourceSet = tx.createResourceSet();
            Resource userResource = resourceSet.createResource(memDBServer.createURI(ids[0]));
            userResource.load(null);
            User user = (User) userResource.getContents().get(0);
            user.setName("Simanihin");
            userResource.save(null);
            return null;
        });
        memDBServer.inTransaction(false, (MemDBServer.TxFunction<Void>) tx -> {
            User user = TestFactory.eINSTANCE.createUser();
            user.setName("Orlov");
            user.setGroup(group);
            ResourceSet resourceSet = tx.createResourceSet();
            Resource userResource = resourceSet.createResource(memDBServer.createURI(""));
            userResource.getContents().add(user);
            userResource.save(null);
            Assert.assertEquals(3, tx.findAll(resourceSet).count());
            Assert.assertEquals(2, tx.findByClass(resourceSet, TestPackage.Literals.USER).count());
            Assert.assertEquals(2, tx.findReferencedTo(group.eResource()).count());
            Assert.assertEquals(1, tx.findByClassAndQName(resourceSet, TestPackage.Literals.USER, "Simanihin").count());
            return null;
        });
        memDBServer.inTransaction(true, (MemDBServer.TxFunction<Void>) tx -> {
            ResourceSet resourceSet = tx.createResourceSet();
            Assert.assertEquals(3, tx.findAll(resourceSet).count());
            Assert.assertEquals(2, tx.findByClass(resourceSet, TestPackage.Literals.USER).count());
            Assert.assertEquals(2, tx.findReferencedTo(group.eResource()).count());
            Assert.assertEquals(1, tx.findByClassAndQName(resourceSet, TestPackage.Literals.USER, "Simanihin").count());
            return null;
        });
//        memBDServer.inTransaction(true, (MemBDServer.TxFunction<Void>) tx -> {
//            return null;
//        });
//        memBDServer.inTransaction(true, (MemBDServer.TxFunction<Void>) tx -> {
//            return null;
//        });
    }
}
