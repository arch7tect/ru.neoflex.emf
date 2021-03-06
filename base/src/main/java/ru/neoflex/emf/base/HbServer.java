package ru.neoflex.emf.base;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.emf.codegen.ecore.genmodel.GenModelPackage;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.impl.EPackageRegistryImpl;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceFactoryImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataBuilder;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.spi.MetadataImplementor;
import org.hibernate.c3p0.internal.C3P0ConnectionProvider;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.hibernate.internal.SessionFactoryImpl;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.tool.hbm2ddl.SchemaUpdate;
import org.hibernate.tool.schema.TargetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class HbServer implements AutoCloseable {
    public static final String CONFIG_DEFAULT_SCHEMA = "hb.defaultSchema";
    public static final String CONFIG_DRIVER = "hb.driver";
    public static final String CONFIG_URL = "hb.url";
    public static final String CONFIG_USER = "hb.user";
    public static final String CONFIG_PASS = "hb.pass";
    public static final String CONFIG_DIALECT = "hb.dialect";
    public static final String CONFIG_SHOW_SQL = "hb.show_sql";
    public static final String CONFIG_MIN_POOL_SIZE = "hb.min_pool_size";
    public static final String CONFIG_MAX_POOL_SIZE = "hb.max_pool_size";
    private static final Logger logger = LoggerFactory.getLogger(HbServer.class);
    protected static final ThreadLocal<String> tenantId = new InheritableThreadLocal<>();
    private static final ThreadLocal<Map<EObject, Long>> eObjectToIdMap = new ThreadLocal<>();
    protected final SessionFactory sessionFactory;
    private final String dbName;
    private final Events events = new Events();
    private final Set<String> updatedSchemas = new HashSet<>();
    private Set<EAttribute> eKeys;
    private Function<EAttribute, Boolean> indexedAttributeDelegate = eAttribute -> eAttribute.getEAttributeType() == EcorePackage.eINSTANCE.getEString();
    private final Properties config;
    private final EPackage.Registry packageRegistry = new EPackageRegistryImpl(EPackage.Registry.INSTANCE);
    private final Map<EClass, List<EClass>> descendants = new HashMap<>();
    private ServiceRegistry serviceRegistry;


    public static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Map<Object, Boolean> seen = new ConcurrentHashMap<>();
        return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }

    public Predicate<EObject> distinctById() {
        Map<Object, Boolean> seen = new ConcurrentHashMap<>();
        return t -> seen.putIfAbsent(getId(t), Boolean.TRUE) == null;
    }

    public Function<EAttribute, Boolean> getIndexedAttributeDelegate() {
        return indexedAttributeDelegate;
    }

    public boolean isIndexed(EAttribute eAttribute) {
        return getEKeys().contains(eAttribute) || getIndexedAttributeDelegate().apply(eAttribute);
    }

    public void setIndexedAttributeDelegate(Function<EAttribute, Boolean> indexedAttributeDelegate) {
        this.indexedAttributeDelegate = indexedAttributeDelegate;
    }

    public HbServer(String dbName, Properties config) {
        this.dbName = dbName;
        this.config = config;
        packageRegistry.put(EcorePackage.eNS_URI, EcorePackage.eINSTANCE);
        packageRegistry.put(GenModelPackage.eNS_URI, GenModelPackage.eINSTANCE);
        Configuration configuration = getConfiguration();
        serviceRegistry = new StandardServiceRegistryBuilder()
                .applySettings(configuration.getProperties()).build();
        sessionFactory = configuration.buildSessionFactory(serviceRegistry);
        String defaultSchema = getConfig().getProperty(CONFIG_DEFAULT_SCHEMA, "");
        setSchema(defaultSchema);
    }

    public Map<EObject, Long> getEObjectToIdMap() {
        if (eObjectToIdMap.get() == null) {
            eObjectToIdMap.set(new WeakIdentityHashMap<>());
        }
        return eObjectToIdMap.get();
    }

    public Long getId(EObject eObject) {
        return getEObjectToIdMap().get(eObject);
    }

    public void setId(EObject eObject, Long id) {
        getEObjectToIdMap().put(eObject, id);
    }

    public List<EPackage> loadDynamicPackages() throws Exception {
        return inTransaction(true, tx -> {
            ResourceSet resourceSet = tx.getResourceSet();
            return findBy(resourceSet, EcorePackage.Literals.EPACKAGE).getContents().stream()
                    .map(eObject -> (EPackage)eObject).collect(Collectors.toList());
        });
    }

    public void registerDynamicPackages() throws Exception {
        loadDynamicPackages().forEach(this::registerEPackage);
    }

    public EPackage.Registry getPackageRegistry() {
        return packageRegistry;
    }

    public void registerEPackage(EPackage ePackage) {
        getPackageRegistry().put(ePackage.getNsURI(), ePackage);
        for (EClassifier eClassifier : ePackage.getEClassifiers()) {
            if (eClassifier instanceof EClass) {
                EClass eClass = (EClass) eClassifier;
                if (!eClass.isAbstract()) {
                    for (EClass superType : eClass.getEAllSuperTypes()) {
                        getConcreteDescendants(superType).add(eClass);
                    }
                }
            }
        }
        eKeys = null;
    }

    public List<EClass> getConcreteDescendants(EClass eClass) {
        return descendants.computeIfAbsent(eClass, (c) -> new ArrayList<EClass>() {
            {
                if (!eClass.isAbstract()) {
                    add(eClass);
                }
            }
        });
    }

    public String getTenantId() {
        return tenantId.get();
    }

    public void setTenantId(String tenantId) {
        HbServer.tenantId.set(tenantId);
    }

    public Long getId(URI uri) {
        if (uri.segmentCount() >= 1) {
            return Long.parseLong(uri.segment(0));
        }
        return null;
    }

    public boolean canHandle(URI uri) {
        return getScheme().equals(uri.scheme()) && Objects.equals(uri.authority(), getDbName());
    }

    public Long getVersion(URI uri) {
        String query = uri.query();
        if (query == null || !query.contains("rev=")) {
            return null;
        }
        String versionStr = query.split("rev=", -1)[1];
        return StringUtils.isEmpty(versionStr) ? null : Long.parseLong(versionStr);
    }

    public Events getEvents() {
        return events;
    }

    protected Resource createResource(URI uri) {
        if (uri == null) {
            uri = createURI();
        }
        return new HbResource(uri);
    }

    public SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    public void setSchema(String schema) {
        setTenantId(schema);
        synchronized (updatedSchemas) {
            if (!updatedSchemas.contains(schema)) {
                Configuration configuration = getConfiguration();
                configuration.getProperties().put(Environment.DEFAULT_SCHEMA, schema);
                ServiceRegistry serviceRegistry = new StandardServiceRegistryBuilder()
                        .applySettings(configuration.getProperties()).build();
                MetadataSources metadataSources = new MetadataSources(serviceRegistry);
                metadataSources.addAnnotatedClass(DBObject.class);
                MetadataBuilder metadataBuilder = metadataSources.getMetadataBuilder();
                MetadataImplementor metadata = (MetadataImplementor) metadataBuilder.build();
                SchemaUpdate schemaUpdate = new SchemaUpdate();
                schemaUpdate.execute(EnumSet.of(TargetType.DATABASE), metadata);
                updatedSchemas.add(getTenantId());
            }
        }
    }

    protected Configuration getConfiguration() {
        Configuration configuration = new Configuration();
        Properties settings = new Properties();
        settings.put(Environment.DRIVER, getConfig().getProperty(CONFIG_DRIVER, "org.h2.Driver"));
        settings.put(Environment.URL, getConfig().getProperty(CONFIG_URL, "jdbc:h2:" + System.getProperty("user.home") + "/.h2home/" + this.getDbName()));
        settings.put(Environment.USER, getConfig().getProperty(CONFIG_USER, "sa"));
        settings.put(Environment.PASS, getConfig().getProperty(CONFIG_PASS, ""));
        settings.put(Environment.DIALECT, getConfig().getProperty(CONFIG_DIALECT, "org.hibernate.dialect.H2Dialect"));
        settings.put(Environment.CURRENT_SESSION_CONTEXT_CLASS, "thread");
        settings.put(Environment.HBM2DDL_AUTO, "none");
        settings.put(Environment.SHOW_SQL, getConfig().getProperty(CONFIG_SHOW_SQL, "false"));
        settings.put(Environment.C3P0_MIN_SIZE, getConfig().getProperty(CONFIG_MIN_POOL_SIZE, "1"));
        settings.put(Environment.C3P0_MAX_SIZE, getConfig().getProperty(CONFIG_MAX_POOL_SIZE, "50"));
        settings.put(Environment.MULTI_TENANT, "SCHEMA");
        settings.put(Environment.MULTI_TENANT_IDENTIFIER_RESOLVER, DBTenantIdentifierResolver.class.getName());
        settings.put(Environment.MULTI_TENANT_CONNECTION_PROVIDER, HBDBConnectionProvider.class.getName());
        configuration.setProperties(settings);
        configuration.addAnnotatedClass(DBObject.class);
        return configuration;
    }

    public Session createSession() {
        return sessionFactory.openSession();
    }

    protected HbTransaction createDBTransaction(boolean readOnly) {
        return new HbTransaction(readOnly, this);
    }

    private String createURIString(Long id, Long version) {
        return getScheme() + "://" + dbName + "/" + (id != null ? id : "") + (version != null ? ("?rev=" + version) : "");
    }

    public URI createURI() {
        return createURI((Long)null);
    }

    public URI createURI(EObject eObject) {
        EObject root = EcoreUtil.getRootContainer(eObject);
        return createURI(getId(root), eObject.eResource().getTimeStamp()).appendFragment(String.valueOf(getId(eObject)));
    }

    public URI createURI(Long id) {
        return URI.createURI(createURIString(id, null));
    }

    public URI createURI(Long id, Long version) {
        return URI.createURI(createURIString(id, version));
    }

    public URI createURI(String sql) {
        try {
            return createURI().appendQuery(String.format("query=%s", URLEncoder.encode(sql, "utf-8")));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public Resource findAll() {
        return findAll(createResourceSet());
    }

    public Resource findAll(ResourceSet rs) {
        URI uri = createURI().appendQuery("query=select t from DBObject t where t.proxy is null and t.container is null");
        Resource resource = rs.createResource(uri);
        safeLoad(resource, null);
        return resource;
    }

    public Resource findBy(EClass eClass) {
        return findBy(createResourceSet(), eClass);
    }

    public Resource findReferencedTo(Long id) {
        return findReferencedTo(createResourceSet(), id);
    }

    private void safeLoad(Resource resource, Map<String, Object> options) {
        try {
            resource.load(options);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Resource findReferencedTo(ResourceSet rs, Long id) {
        URI uri = createURI("select o from DBObject o join o.references r where r.refObject.id = :id");
        Resource resource = rs.createResource(uri);
        Map<String, Object> options = new HashMap<>();
        options.put("id", id);
        options.put(HbHandler.OPTION_GET_ROOT_CONTAINER, true);
        safeLoad(resource, options);
        return resource;
    }

    public Resource findReferencedTo(ResourceSet rs, Resource resource) {
        Set<Long> exclude = resource.getContents().stream()
                .map(this::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        //noinspection NullableProblems
        Iterable<EObject> iterable = resource::getAllContents;
        List<EObject> eObjects = StreamSupport.stream(iterable.spliterator(), false)
                .map(this::getId).filter(Objects::nonNull)
                .flatMap(id -> findReferencedTo(rs, id).getContents().stream())
                .collect(Collectors.toList()).stream()
                .filter(eObject -> !exclude.contains(getId(eObject)))
                .filter(distinctById())
                .collect(Collectors.toList());
        Resource result = rs.createResource(createURI());
        result.getContents().addAll(eObjects);
        return result;
    }

    public static String safeEncode(String s) {
        try {
            return URLEncoder.encode(s, "utf-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String safeDecode(String s) {
        try {
            return URLDecoder.decode(s, "utf-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public Resource findBy(ResourceSet rs, EClass eClass) {
        Map<String, Object> options = new HashMap<>();
        URI uri = getQueryUri(eClass, options,
                "select o from DBObject o where o.classUri=:classUri%d");
        Resource resource = rs.createResource(uri);
        safeLoad(resource, options);
        return resource;
    }

    public Resource findBy(EClass eClass, EStructuralFeature sf, String value) {
        return findBy(createResourceSet(), eClass, sf, value);
    }

    public Resource findBy(ResourceSet rs, EClass eClass, EStructuralFeature sf, String value) {
        Map<String, Object> options = new HashMap<>();
        URI uri = getQueryUri(eClass, options,
                "select o from DBObject o join o.attributes a " +
                        "on a.feature = :feature and a.value = :value " +
                        "where o.classUri = :classUri%d"
                        );
        Resource resource = rs.createResource(uri);
        options.put("feature", sf.getName());
        options.put("value", value);
        safeLoad(resource, options);
        return resource;
    }

    private URI getQueryUri(EClass eClass, Map<String, Object> options, String pattern) {
        List<EClass> eClasses = getConcreteDescendants(eClass);
        List<String> qParts = new ArrayList<>();
        for (int i = 0; i < eClasses.size(); ++i) {
            EClass theClass = eClasses.get(i);
            String sql = safeEncode(String.format(pattern, i));
            qParts.add(String.format("query%d=%s", i, sql));
            options.put(String.format("classUri%d", i), EcoreUtil.getURI(theClass).toString());
        }
        URI uri = createURI().appendQuery(String.join("&", qParts));
        return uri;
    }

    public Resource findBy(EClass eClass, String value) {
        return findBy(createResourceSet(), eClass, value);
    }

    public Resource findBy(ResourceSet rs, EClass eClass, String value) {
        EStructuralFeature sf = getQNameSF(eClass);
        if (sf == null) {
            throw new IllegalArgumentException(String.format("Can't find id feature for %s", EcoreUtil.getURI(eClass).toString()));
        }
        return findBy(rs, eClass, sf, value);
    }

    public void importResourceSet(ResourceSet rs, ResourceSet extRS) throws IOException {
        for (Resource extR: extRS.getResources()) {
            List<Long> ids = extR.getContents().stream().map(eObject -> {
                String name = getQName(eObject);
                if (name == null) return null;
                Resource r = findBy(eObject.eClass(), name);
                if (r.getContents().size() == 0) return null;
                Long id = getId(r.getContents().get(0));
                return id;
            }).collect(Collectors.toList());
            URI uri = createURI();
            Resource resource = rs.createResource(uri);
            List<EObject> newContent = new ArrayList<>(EcoreUtil.copyAll(extR.getContents()));
            for (int i = 0; i < newContent.size(); ++i) {
                Long id = ids.get(i);
                if (id != null) {
                    setId(newContent.get(i), id);
                }
            }
            resource.getContents().addAll(newContent);
            resource.save(null);
        }
    }

    public EAttribute getQNameSF(EClass eClass) {
        EAttribute sf;
        if (EcorePackage.Literals.EPACKAGE.isSuperTypeOf(eClass)) {
            sf = EcorePackage.Literals.EPACKAGE__NS_URI;
        } else {
            sf = eClass.getEIDAttribute();
        }
        return sf;
    }

    public String getQName(EObject eObject) {
        EAttribute sf = getQNameSF(eObject.eClass());
        return sf != null ? EcoreUtil.convertToString(sf.getEAttributeType(), eObject.eGet(sf)) : null;
    }

    public String getScheme() {
        return "hbdb";
    }

    public Properties getConfig() {
        return config;
    }

    public String getDbName() {
        return dbName;
    }

    @Override
    public void close() {
        C3P0ConnectionProvider connectionProvider = (C3P0ConnectionProvider) serviceRegistry.getService(MultiTenantConnectionProvider.class);
        connectionProvider.stop();
        sessionFactory.close();
    }

    public Set<EAttribute> getEKeys() {
        if (eKeys == null) {
            eKeys = new HashSet<>();
            packageRegistry.values().stream().flatMap(o -> {
                //noinspection NullableProblems
                Iterable<EObject> iterable = ((EPackage) o)::eAllContents;
                    return StreamSupport.stream(iterable.spliterator(), false)
                            .filter(eObject -> eObject instanceof EReference && !((EReference) eObject).getEKeys().isEmpty());
            }).forEach(eObject -> eKeys.addAll(((EReference) eObject).getEKeys()));
        }
        return eKeys;
    }

    public interface TxFunction<R> extends Serializable {
        R call(HbTransaction tx) throws Exception;
    }

    public <R> R inTransaction(boolean readOnly, TxFunction<R> f) throws Exception {
        return inTransaction(() -> createDBTransaction(readOnly), f);
    }

    public ResourceSet createResourceSet() {
        return createResourceSet(null);
    }

    public HbResource createResource() {
        return (HbResource) createResourceSet().createResource(createURI());
    }

    public ResourceSet createResourceSet(HbTransaction tx) {
        ResourceSetImpl result = new ResourceSetImpl();
        result.setPackageRegistry(getPackageRegistry());
        result.setURIResourceMap(new HashMap<>());
        result.getResourceFactoryRegistry()
                .getProtocolToFactoryMap()
                .put(getScheme(), new ResourceFactoryImpl() {
                    @Override
                    public Resource createResource(URI uri) {
                        return HbServer.this.createResource(uri);
                    }
                });
        result.getURIConverter()
                .getURIHandlers()
                .add(0, new HbHandler(this, tx));
        return result;
    }

    public <R> R inTransaction(Supplier<HbTransaction> txSupplier, TxFunction<R> f) throws Exception {
        try (HbTransaction tx = txSupplier.get()) {
            tx.begin();
            try {
                R result = f.call(tx);
                tx.commit();
                return result;
            } catch (Throwable e) {
                tx.rollback();
                throw e;
            }
        }
    }

    public static class DBTenantIdentifierResolver implements CurrentTenantIdentifierResolver {
        @Override
        public String resolveCurrentTenantIdentifier() {
            return HbServer.tenantId.get();
        }

        @Override
        public boolean validateExistingCurrentSessions() {
            return false;
        }
    }

    public static class HBDBConnectionProvider extends C3P0ConnectionProvider implements MultiTenantConnectionProvider {
        @Override
        public Connection getAnyConnection() throws SQLException {
            return super.getConnection();
        }

        @Override
        public void releaseAnyConnection(Connection connection) throws SQLException {
            super.closeConnection(connection);
        }

        @Override
        public Connection getConnection(String tenantIdentifier) throws SQLException {
            final Connection connection = getAnyConnection();
            try {
                if (StringUtils.isNoneEmpty(tenantIdentifier)) {
//                    connection.createStatement().execute( setSchemaQuery.apply(tenantIdentifier) );
                    connection.setSchema(tenantIdentifier);
                }
            } catch (SQLException e) {
                throw new HibernateException(
                        "Could not alter JDBC connection to specified schema [" + tenantIdentifier + "]", e);
            }
            return connection;
        }

        @Override
        public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
            super.closeConnection(connection);
        }
    }
}
