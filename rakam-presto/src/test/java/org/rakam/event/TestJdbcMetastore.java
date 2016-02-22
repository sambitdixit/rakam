package org.rakam.event;

import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.analysis.metadata.Metastore;
import org.rakam.collection.FieldDependencyBuilder;
import org.rakam.collection.FieldType;
import org.rakam.collection.SchemaField;
import org.rakam.presto.analysis.JDBCMetastore;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.rakam.collection.FieldType.LONG;
import static org.rakam.collection.FieldType.STRING;
import static org.testng.Assert.*;

public class TestJdbcMetastore extends TestingEnvironment {
    private JDBCMetastore metastore;
    private static final String PROJECT_NAME = TestJdbcMetastore.class.getName().replace(".", "_").toLowerCase();

    @BeforeMethod
    public void setUpMethod() throws Exception {
        JDBCPoolDataSource metastoreDataSource = JDBCPoolDataSource.getOrCreateDataSource(getPostgresqlConfig());

        metastore = new JDBCMetastore(metastoreDataSource, getPrestoConfig(),
                new EventBus(), new FieldDependencyBuilder().build());
        metastore.setup();
    }

    @AfterMethod
    public void tearDownMethod() throws Exception {
        metastore.clearCache();
        metastore.deleteProject(PROJECT_NAME);
    }

    @Test
    public void testCreateProject() throws Exception {
        metastore.createProject(PROJECT_NAME);
        assertTrue(metastore.getProjects().contains(PROJECT_NAME));
    }

    @Test
    public void testCreateCollection() throws Exception {
        metastore.createProject(PROJECT_NAME);

        ImmutableSet<SchemaField> schema = ImmutableSet.of(new SchemaField("test", STRING));
        metastore.getOrCreateCollectionFields(PROJECT_NAME, "test", schema);

        assertEquals(metastore.getCollection(PROJECT_NAME, "test"), schema);
    }

    @Test
    public void testCreateFields() throws Exception {
        metastore.createProject(PROJECT_NAME);

        metastore.getOrCreateCollectionFields(PROJECT_NAME, "test", ImmutableSet.of());

        ImmutableSet<SchemaField> schema = ImmutableSet.of(new SchemaField("test", STRING));
        metastore.getOrCreateCollectionFields(PROJECT_NAME, "test", schema);

        assertEquals(metastore.getCollection(PROJECT_NAME, "test"), schema);
    }

    @Test
    public void testCreateApiKeys() throws Exception {
        metastore.createProject(PROJECT_NAME);

        Metastore.ProjectApiKeys testing = metastore.createApiKeys(PROJECT_NAME);

        assertTrue(metastore.checkPermission(PROJECT_NAME, Metastore.AccessKeyType.READ_KEY, testing.readKey));
        assertTrue(metastore.checkPermission(PROJECT_NAME, Metastore.AccessKeyType.WRITE_KEY, testing.writeKey));
        assertTrue(metastore.checkPermission(PROJECT_NAME, Metastore.AccessKeyType.MASTER_KEY, testing.masterKey));

        assertFalse(metastore.checkPermission(PROJECT_NAME, Metastore.AccessKeyType.READ_KEY, "invalidKey"));
        assertFalse(metastore.checkPermission(PROJECT_NAME, Metastore.AccessKeyType.WRITE_KEY, "invalidKey"));
        assertFalse(metastore.checkPermission(PROJECT_NAME, Metastore.AccessKeyType.MASTER_KEY, "invalidKey"));
    }

    @Test
    public void testRevokeApiKeys() throws Exception {
        metastore.createProject(PROJECT_NAME);

        Metastore.ProjectApiKeys testing = metastore.createApiKeys(PROJECT_NAME);

        metastore.revokeApiKeys(PROJECT_NAME, testing.id);

        assertFalse(metastore.checkPermission(PROJECT_NAME, Metastore.AccessKeyType.READ_KEY, testing.readKey));
        assertFalse(metastore.checkPermission(PROJECT_NAME, Metastore.AccessKeyType.WRITE_KEY, testing.writeKey));
        assertFalse(metastore.checkPermission(PROJECT_NAME, Metastore.AccessKeyType.MASTER_KEY, testing.masterKey));
    }

    @Test
    public void testDeleteProject() throws Exception {
        metastore.createProject(PROJECT_NAME);
        metastore.deleteProject(PROJECT_NAME);

        assertFalse(metastore.getProjects().contains(PROJECT_NAME));
    }

    @Test
    public void testGetApiKeys() throws Exception {
        metastore.createProject(PROJECT_NAME);

        Metastore.ProjectApiKeys testing = metastore.createApiKeys(PROJECT_NAME);
        assertEquals(ImmutableSet.copyOf(metastore.getApiKeys(new int[]{testing.id})), ImmutableSet.of(testing));
    }

    @Test
    public void testCollectionMethods() throws Exception {
        metastore.createProject(PROJECT_NAME);

        ImmutableSet<SchemaField> schema = ImmutableSet.of(new SchemaField("test1", STRING), new SchemaField("test2", STRING));
        metastore.getOrCreateCollectionFields(PROJECT_NAME, "testcollection1", schema);
        metastore.getOrCreateCollectionFields(PROJECT_NAME, "testcollection2", schema);

        assertEquals(ImmutableSet.of("testcollection1", "testcollection2"), ImmutableSet.copyOf(metastore.getCollectionNames(PROJECT_NAME)));

        Map<String, List<SchemaField>> testing = metastore.getCollections(PROJECT_NAME);
        assertEquals(testing.size(), 2);
        assertEquals(ImmutableSet.copyOf(testing.get("testcollection1")), schema);
        assertEquals(ImmutableSet.copyOf(testing.get("testcollection2")), schema);
    }

    @Test
    public void testCollectionFieldsOrdering() throws Exception {
        metastore.createProject(PROJECT_NAME);

        ImmutableSet.Builder<SchemaField> builder = ImmutableSet.builder();

        for (FieldType fieldType : FieldType.values()) {
            builder.add(new SchemaField(fieldType.name(), fieldType));
        }

        metastore.getOrCreateCollectionFields(PROJECT_NAME, "testcollection", builder.build());

        for (int i = 0; i < 100; i++) {
            assertEquals(metastore.getCollection(PROJECT_NAME, "testcollection"), builder.build());
            metastore.clearCache();
        }
    }

    @Test
    public void testDuplicateFields() throws Exception {
        metastore.createProject(PROJECT_NAME);

        ImmutableSet.Builder<SchemaField> builder = ImmutableSet.builder();

        for (FieldType fieldType : FieldType.values()) {
            builder.add(new SchemaField(fieldType.name(), fieldType));
        }

        metastore.getOrCreateCollectionFields(PROJECT_NAME, "testcollection",
                ImmutableSet.of(new SchemaField("test", LONG)));

        metastore.getOrCreateCollectionFields(PROJECT_NAME, "testcollection",
                ImmutableSet.of(new SchemaField("test", LONG)));

        assertEquals(ImmutableSet.copyOf(metastore.getCollection(PROJECT_NAME, "testcollection")),
                ImmutableSet.of(new SchemaField("test", LONG), new SchemaField("test", LONG)));
    }

    @Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = "Query failed \\(\\#[0-9_A-Za-z]+\\)\\: Multiple entries with same key: test\\=raptor\\:test\\:2\\:bigint and test\\=raptor\\:test\\:1\\:varchar")
    public void testInvalidDuplicateFieldNames() throws Exception {
        metastore.createProject(PROJECT_NAME);

        metastore.getOrCreateCollectionFields(PROJECT_NAME, "testcollection",
                ImmutableSet.of(new SchemaField("test", STRING), new SchemaField("test", LONG)));
    }

    @Test
    public void testAllSchemaTypes() throws Exception {
        metastore.createProject(PROJECT_NAME);

        ImmutableSet.Builder<SchemaField> builder = ImmutableSet.builder();

        for (FieldType fieldType : FieldType.values()) {
            builder.add(new SchemaField(fieldType.name(), fieldType));
        }

        metastore.getOrCreateCollectionFields(PROJECT_NAME, "testcollection", builder.build());

        assertEquals(metastore.getCollection(PROJECT_NAME, "testcollection"), builder.build());
    }

    /**
     * The schema change requests may be performed from any Rakam node in a cluster and they have to be consistent.
     **/
    @Test
    public void testConcurrentSchemaChanges() throws Exception {
        metastore.createProject("test");

        List<List<SchemaField>> collect = IntStream.range(0, 300).parallel().mapToObj(i ->
                metastore.getOrCreateCollectionFieldList("test", "test", ImmutableSet.of(new SchemaField("test" + i, FieldType.STRING))))
                .collect(Collectors.toList());

        Set<SchemaField> allSchemas = ImmutableSet.copyOf(collect.stream().sorted((o1, o2) -> o2.size() - o1.size()).findFirst().get());

        for (List<SchemaField> schemaFields : collect) {
            for (int i = 0; i < schemaFields.size(); i++) {
                assertTrue(allSchemas.contains(schemaFields.get(i)), String.format("%s not in %s", schemaFields.get(i), allSchemas));
            }
        }
    }
}