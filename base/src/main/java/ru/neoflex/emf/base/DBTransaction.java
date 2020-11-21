package ru.neoflex.emf.base;

import org.eclipse.emf.common.util.Diagnostic;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceFactoryImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.Diagnostician;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.hibernate.Session;
import org.hibernate.Transaction;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class DBTransaction implements AutoCloseable, Serializable {
    protected final Session session;
    protected transient String message = "";
    protected transient String author;
    protected transient String email;
    private transient boolean readOnly;
    private transient DBServer dbServer;
    private transient ResourceSet resourceSet;
    private Transaction tx;

    public DBTransaction(boolean readOnly, DBServer dbServer) {
        this.readOnly = readOnly;
        this.dbServer = dbServer;
        session = getDbServer().getSessionFactory().openSession();
    }

    public void close() {
        session.close();
    }

    protected DBObject get(Long id) {
        return session.get(DBObject.class, id);
    }

    protected Stream<DBObject> findAll() {
        return session.createQuery("select o from DBObject o", DBObject.class).getResultStream();
    }

    protected Stream<DBObject> findByClass(String classUri) {
        return session.createQuery("select o from DBObject o where o.classUri = :classUri", DBObject.class)
                .setParameter("classUri", classUri)
                .getResultStream();
    }

    protected Stream<DBObject> findByClassAndQName(String classUri, String qName) {
        return session.createQuery("select o from DBObject o where o.classUri = :classUri and o.qName = :qName", DBObject.class)
                .setParameter("classUri", classUri)
                .setParameter("qName", qName)
                .getResultStream();
    }

    protected Stream<DBObject> findReferencedTo(Long id) {
        return session.createQuery("select o from DBObject o join o.references r where r.containment = false and r.refObject.id = :id", DBObject.class)
                .setParameter("id", id)
                .getResultStream();
    }

    protected void deleteRecursive(DBObject dbObject) {
        Set<String> deps = session.createQuery(
                "select o from DBObject o join o.references r " +
                        "where r.containment = false and r.refObject.id = :refdb_id"
                , DBObject.class).setParameter("refdb_id", dbObject.getId()).getResultStream()
                .map(o->String.valueOf(o.getId())).collect(Collectors.toSet());
        if (deps.size() > 0) {
            throw new IllegalArgumentException(String.format(
                    "Can not delete Resource, referenced by [%s]", String.join(", ", deps)));
        }
        for (DBReference r: dbObject.getReferences()) {
            if (r.getContainment() || r.getRefObject().isProxy()) {
                deleteRecursive(r.getRefObject());
            }
        }
        session.delete(dbObject);
    }

    public boolean truncate() {
        session.createQuery("select r from DBObject r", DBObject.class).getResultStream().forEach(session::delete);
        return true;
    }

    public void begin() {
        if (!isReadOnly()) {
            tx = session.beginTransaction();
        }
    }

    public void commit() {
        if (tx != null) {
            tx.commit();
            tx = null;
        }
    }

    protected DBObject getOrThrow(Long id) {
        DBObject dbObject = get(id);
        if (dbObject == null) {
            throw new IllegalArgumentException("Object not found: " + id);
        }
        return dbObject;
    }

    private void saveEObjectNonContainment(DBResource resource, DBObject dbObject, EObject eObject) {
        List<AbstractMap.SimpleEntry<EReference, List<EObject>>> refsNC = eObject.eClass().getEAllReferences().stream()
                .filter(sf -> !sf.isDerived() && !sf.isTransient() && !sf.isContainer() && !sf.isContainment() && eObject.eIsSet(sf))
                .map(sf -> new AbstractMap.SimpleEntry<>(sf,
                        sf.isMany() ? (List<EObject>) eObject.eGet(sf) : Collections.singletonList((EObject) eObject.eGet(sf))))
                .collect(Collectors.toList());
        Set<DBReference> toDeleteNC = dbObject.getReferences().stream().filter(r->!r.getContainment()).collect(Collectors.toSet());
        for (AbstractMap.SimpleEntry<EReference, List<EObject>> ref: refsNC) {
            String feature = ref.getKey().getName();
            for (EObject eRefObject: ref.getValue()) {
                int index = ref.getKey().isMany() ? ref.getValue().indexOf(eRefObject) : -1;
                if (eRefObject.eResource() instanceof DBResource) {
                    DBResource resource2 = (DBResource) eRefObject.eResource();
                    Long id2 = resource2.getID(eRefObject);
                    DBReference dbReference = dbObject.getReferences().stream()
                            .filter(r -> r.getFeature().equals(feature) && r.getIndex() == index && r.getRefObject().getId().equals(id2))
                            .findFirst().orElse(null);
                    if (dbReference != null) {
                        toDeleteNC.remove(dbReference);
                    }
                    else {
                        dbReference = new DBReference();
                        dbReference.setContainment(ref.getKey().isContainment());
                        dbObject.getReferences().add(dbReference);
                        dbReference.setFeature(feature);
                        dbReference.setIndex(index);
                        DBObject refDBObject;
                        if (id2 != null) {
                            refDBObject = getOrThrow(id2);
                        }
                        else {
                            refDBObject = new DBObject();
                            refDBObject.setClassUri(EcoreUtil.getURI(eRefObject.eClass()).toString());
                            refDBObject.setProxy(EcoreUtil.getURI(eRefObject).toString());
                            session.persist(refDBObject);
                        }
                        dbReference.setRefObject(refDBObject);
                    }
                }
            }
        }
        dbObject.getReferences().removeAll(toDeleteNC);
        getSession().save(dbObject);
        toDeleteNC.forEach(dbReference -> {
            if (dbReference.getRefObject().isProxy()) {
                deleteRecursive(dbReference.getRefObject());
            }
        });
        eObject.eClass().getEAllReferences().stream()
                .filter(sf -> !sf.isDerived() && !sf.isTransient() && !sf.isContainer() && sf.isContainment() && eObject.eIsSet(sf))
                .flatMap(sf -> (sf.isMany() ? (List<EObject>) eObject.eGet(sf) : Collections.singletonList((EObject) eObject.eGet(sf))).stream())
                .forEach(eRefObject -> {
                    DBResource resource2 = (DBResource) eRefObject.eResource();
                    Long id2 = resource2.getID(eRefObject);
                    DBObject dbObject2 = getOrThrow(id2);
                    saveEObjectNonContainment(resource, dbObject2, eRefObject);
                });
    }

    private DBObject saveEObjectContainment(DBResource resource, DBObject dbObject, EObject eObject) {
        if (dbObject == null) {
            dbObject = new DBObject();
            dbObject.setVersion(0);
        }
        dbObject.setVersion(dbObject.getVersion() + 1);
        dbObject.setClassUri(EcoreUtil.getURI(eObject.eClass()).toString());
        dbObject.setqName(getDbServer().getQName(eObject));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            List<AbstractMap.SimpleEntry<EAttribute, List>> attrs = eObject.eClass().getEAllAttributes().stream()
                    .filter(sf -> !sf.isDerived() && !sf.isTransient() && eObject.eIsSet(sf))
                    .map(sf -> new AbstractMap.SimpleEntry<>(sf,
                            sf.isMany() ? (List) eObject.eGet(sf) : Arrays.asList(eObject.eGet(sf))))
                    .collect(Collectors.toList());
            int count = attrs.stream().map(entry -> entry.getValue().size()).reduce(0, Integer::sum);
            oos.writeInt(count);
            for (AbstractMap.SimpleEntry<EAttribute, List> attr: attrs) {
                EDataType eDataType = attr.getKey().getEAttributeType();
                String feature = attr.getKey().getName();
                for (Object value: attr.getValue()) {
                    oos.writeUTF(feature);
                    oos.writeUTF(EcoreUtil.convertToString(eDataType, value));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        dbObject.setImage(baos.toByteArray());
        List<AbstractMap.SimpleEntry<EReference, List<EObject>>> refsC = eObject.eClass().getEAllReferences().stream()
                .filter(sf -> !sf.isDerived() && !sf.isTransient() && !sf.isContainer() && sf.isContainment() && eObject.eIsSet(sf))
                .map(sf -> new AbstractMap.SimpleEntry<>(sf,
                        sf.isMany() ? (List<EObject>) eObject.eGet(sf) : Collections.singletonList((EObject) eObject.eGet(sf))))
                .collect(Collectors.toList());
        Set<DBReference> toDeleteC = dbObject.getReferences().stream().filter(DBReference::getContainment).collect(Collectors.toSet());
        for (AbstractMap.SimpleEntry<EReference, List<EObject>> ref: refsC) {
            String feature = ref.getKey().getName();
            for (EObject eObject2: ref.getValue()) {
                EcoreUtil.resolveAll(eObject2);
                if (eObject2.eIsProxy()) {
                    throw new RuntimeException("Can't resolve " + ((InternalEObject)eObject2).eProxyURI().toString());
                }
                DBResource resource2 = (DBResource) eObject2.eResource();
                Long id2 = resource2.getID(eObject2);
                DBObject dbObject2 = id2 != null ? getOrThrow(id2) : saveEObjectContainment(resource, null, eObject2);
                int index = ref.getKey().isMany() ? ref.getValue().indexOf(eObject2) : -1;
                DBReference dbReference = dbObject.getReferences().stream()
                        .filter(r -> r.getFeature().equals(feature) && r.getIndex() == index && r.getRefObject().getId().equals(id2))
                        .findFirst().orElse(null);
                if (dbReference != null) {
                    toDeleteC.remove(dbReference);
                }
                else {
                    dbReference = new DBReference();
                    dbReference.setContainment(ref.getKey().isContainment());
                    dbObject.getReferences().add(dbReference);
                    dbReference.setFeature(feature);
                    dbReference.setIndex(index);
                    dbReference.setRefObject(dbObject2);
                }
            }
        }
        dbObject.getReferences().removeAll(toDeleteC);
        getSession().saveOrUpdate(dbObject);
        toDeleteC.forEach(dbReference -> deleteRecursive(dbReference.getRefObject()));
        resource.setID(eObject, dbObject.getId());
        resource.setVersion(eObject, dbObject.getVersion());
        return dbObject;
    }


    private EObject loadEObject(DBResource resource, DBObject dbObject) {
        String classUri = dbObject.getClassUri();
        EClass eClass = (EClass) getResourceSet().getEObject(URI.createURI(classUri), false);
        EObject eObject = EcoreUtil.create(eClass);
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(dbObject.getImage()))) {
            int count = ois.readInt();
            for (int i = 0; i < count; ++i) {
                String feature = ois.readUTF();
                String image = ois.readUTF();
                EStructuralFeature sf = eClass.getEStructuralFeature(feature);
                if (sf instanceof EAttribute) {
                    EAttribute eAttribute = (EAttribute) sf;
                    EDataType eDataType = eAttribute.getEAttributeType();
                    Object value = EcoreUtil.createFromString(eDataType, image);
                    if (sf.isMany()) {
                        ((List) eObject.eGet(sf)).add(value);
                    }
                    else {
                        eObject.eSet(sf, value);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        List<DBReference> references = new ArrayList<>(dbObject.getReferences());
        references.sort(Comparator.comparingInt(value -> value.getIndex()));
        for (DBReference dbReference: references) {
            EStructuralFeature sf = eClass.getEStructuralFeature(dbReference.getFeature());
            if (sf instanceof EReference) {
                DBObject dbRef = dbReference.getRefObject();
                EReference eReference = (EReference) sf;
                EObject refObject;
                if (eReference.isContainment()) {
                    refObject = loadEObject(resource, dbRef);
                }
                else {
                    EClass refClass = (EClass) getResourceSet().getEObject(URI.createURI(dbRef.getClassUri()), false);
                    refObject = EcoreUtil.create(refClass);
                    if (dbRef.getProxy() != null) {
                        ((InternalEObject) refObject).eSetProxyURI(URI.createURI(dbRef.getProxy()));
                    }
                    else {
                        ((InternalEObject) refObject).eSetProxyURI(getDbServer().createURI(dbRef.getId()).appendFragment(String.valueOf(dbRef.getId())));
                    }
                }
                if (sf.isMany()) {
                    ((List)eObject.eGet(sf)).add(refObject);
                }
                else {
                    eObject.eSet(sf, refObject);
                }
            }
        }
        resource.setID(eObject, dbObject.getId());
        resource.setVersion(eObject, dbObject.getVersion());
        return eObject;
    }

    protected Resource createResource(ResourceSet rs, DBObject dbObject) {
        URI uri = getDbServer().createURI(dbObject.getId(), dbObject.getVersion());
        DBResource resource = (DBResource) rs.createResource(uri);
        EObject eObject = loadEObject(resource, dbObject);
        resource.getContents().add(eObject);
        return resource;
    }

    public Stream<Resource> findAll(ResourceSet rs) {
        return findAll()
                .map(dbResource -> createResource(rs, dbResource));
    }

    public Stream<Resource> findByClass(ResourceSet rs, EClass eClass) {
        return getDbServer().getConcreteDescendants(eClass).stream()
                .flatMap(eClassDesc -> findByClass(EcoreUtil.getURI(eClassDesc).trimQuery().toString())
                        .map(dbResource -> createResource(rs, dbResource)));
    }

    public Stream<Resource> findByClassAndQName(ResourceSet rs, EClass eClass, String qName) {
        return getDbServer().getConcreteDescendants(eClass).stream()
                .flatMap(eClassDesc -> findByClassAndQName(EcoreUtil.getURI(eClass).trimQuery().toString(), qName)
                        .map(dbResource -> createResource(rs, dbResource)));
    }

    public Stream<DBObject> findByClassAndQName(EClass eClass, String qName) {
        return getDbServer().getConcreteDescendants(eClass).stream()
                .flatMap(eClassDesc -> findByClassAndQName(EcoreUtil.getURI(eClass).trimQuery().toString(), qName));
    }

    private DBObject getParent(DBObject dbObject) {
        return session.createQuery("select o from DBObject o join o.references r where r.refObject.id = :refdb_id", DBObject.class)
                .setParameter("refdb_id", dbObject.getId())
                .uniqueResult();
    }

    private DBObject getContainer(DBObject dbObject) {
        while (true) {
            DBObject parent = getParent(dbObject);
            if (parent == null) {
                return dbObject;
            }
            dbObject = parent;
        }
    }

    public Stream<Resource> findReferencedTo(Resource resource) {
        Set<Long> exclude = resource.getContents().stream().map(eObject -> getDbServer().getId(eObject))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Iterable<EObject> iterable = resource::getAllContents;
        Set<DBObject> topRefs = StreamSupport.stream(iterable.spliterator(), false)
                .map(eObject -> getDbServer().getId(eObject)).filter(Objects::nonNull)
                .flatMap(this::findReferencedTo)
                .collect(Collectors.toList()).stream()
                .map(this::getContainer).filter(dbObject -> !exclude.contains(dbObject.getId()))
                .collect(Collectors.toSet());
        return topRefs.stream().map(dbObject -> createResource(resource.getResourceSet(), dbObject));
    }

    public void save(DBResource resource) {
        List<EObject> contents = new ArrayList<>(resource.getContents());
        EcoreUtil.resolveAll(resource);
        for (EObject eObject : resource.getContents()) {
            Diagnostic diagnostic = Diagnostician.INSTANCE.validate(eObject);
            if (diagnostic.getSeverity() == Diagnostic.ERROR ||
                    diagnostic.getSeverity() == Diagnostic.WARNING) {
                String message = getDiagnosticMessage(diagnostic);
                throw new RuntimeException(message);
            }
        }
        List<String> sameResources = contents.stream()
                .flatMap(eObject -> {
                    String qName = getDbServer().getQName(eObject);
                    return qName != null ?
                            findByClassAndQName(eObject.eClass(), qName)
                                    .filter(dbObject -> !dbObject.getId().equals(resource.getID(eObject))) :
                            Stream.empty();
                })
                .map(dbObject -> String.valueOf(dbObject.getId()))
                .collect(Collectors.toList());
        if (sameResources.size() > 0) {
            throw new IllegalArgumentException(String.format(
                    "Duplicate object names in resources (%s)",
                    String.join(", ", sameResources)));
        }
        Map<Long, EObject> oldECache = new HashMap<>();
        Map<Long, DBObject> oldDbCache = new HashMap<>();
        DBResource oldResource = (DBResource) getResourceSet().createResource(resource.getURI());
        for (EObject eObject : contents) {
            Long id = resource.getID(eObject);
            EObject oldObject = null;
            if (id != null) {
                Integer version = resource.getVersion(eObject);
                if (version == null) {
                    throw new IllegalArgumentException(String.format("Version for updated resource %s not defined", id));
                }
                DBObject dbObject = getOrThrow(id);
                if (!dbObject.getVersion().equals(version)) {
                    throw new IllegalArgumentException(String.format(
                            "Version (%d) for updated resource %d is not equals to the version in the DB (%d)",
                            version, id, dbObject.getVersion()));
                }
                oldDbCache.put(id, dbObject);
                oldObject = loadEObject(oldResource, dbObject);
                oldResource.getContents().add(oldObject);
                oldECache.put(id, oldObject);
            }
            dbServer.getEvents().fireBeforeSave(oldObject, eObject);
        }
        for (EObject eObject : contents) {
            Long id = resource.getID(eObject);
            DBObject dbObject = saveEObjectContainment(resource, oldDbCache.get(id), eObject);
            oldDbCache.put(dbObject.getId(), dbObject);
        }
        for (EObject eObject : contents) {
            Long id = resource.getID(eObject);
            saveEObjectNonContainment(resource, oldDbCache.get(id), eObject);
            dbServer.getEvents().fireAfterSave(oldECache.get(id), eObject);
        }
        contents.stream()
                .filter(eObject -> eObject instanceof EPackage)
                .map(eObject -> (EPackage) eObject)
                .forEach(ePackage -> getDbServer().getPackageRegistry().put(ePackage.getNsURI(), ePackage));
    }

    public static String getDiagnosticMessage(Diagnostic diagnostic) {
        String message = diagnostic.getMessage();
        for (Iterator i = diagnostic.getChildren().iterator(); i.hasNext(); ) {
            Diagnostic childDiagnostic = (Diagnostic) i.next();
            message += "\n" + childDiagnostic.getMessage();
        }
        return message;
    }

    public void load(DBResource resource) {
        resource.unload();
        Long id = dbServer.getId(resource.getURI());
        DBObject dbObject = getOrThrow(id);
        EObject eObject = loadEObject(resource, dbObject);
        resource.getContents().add(eObject);
        dbServer.getEvents().fireAfterLoad(eObject);
    }

    public void delete(URI uri) {
        Long id = dbServer.getId(uri);
        if (id == null) {
            throw new IllegalArgumentException("Id for deleted object not defined");
        }
        Integer version = dbServer.getVersion(uri);
        if (version == null) {
            throw new IllegalArgumentException(String.format("Version for deleted object %s not defined", id));
        }
        DBObject dbObject = getOrThrow(id);
        Integer oldVersion = dbObject.getVersion();
        if (!version.equals(oldVersion)) {
            throw new IllegalArgumentException(String.format(
                    "Version (%d) for deleted object %d is not equals to the version in the DB (%d)",
                    version, id, oldVersion));
        }
        ResourceSet rs = getResourceSet();
        DBResource oldResource = (DBResource) rs.createResource(uri);
        EObject eObject = loadEObject(oldResource, dbObject);
        oldResource.getContents().add(eObject);
        dbServer.getEvents().fireBeforeDelete(eObject);
        deleteRecursive(dbObject);
        oldResource.getContents().stream()
                .filter(o -> o instanceof EPackage)
                .map(o -> (EPackage) o)
                .forEach(ePackage -> getDbServer().getPackageRegistry().remove(ePackage.getNsURI()));
    }

    public ResourceSet getResourceSet() {
        if (resourceSet == null) {
            resourceSet = createResourceSet();
        }
        return resourceSet;
    }

    public DBResource createResource(URI uri) {
        return (DBResource) getResourceSet().createResource(uri);
    }

    private ResourceSet createResourceSet() {
        ResourceSetImpl result = new ResourceSetImpl();
        result.setPackageRegistry(getDbServer().getPackageRegistry());
        result.setURIResourceMap(new HashMap<>());
        result.getResourceFactoryRegistry()
                .getProtocolToFactoryMap()
                .put(dbServer.getScheme(), new ResourceFactoryImpl() {
                    @Override
                    public Resource createResource(URI uri) {
                        return dbServer.createResource(uri);
                    }
                });
        result.getURIConverter()
                .getURIHandlers()
                .add(0, new DBHandler(this));
        return result;
    }

    public DBServer getDbServer() {
        return dbServer;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public String getTenantId() {
        return getDbServer().getTenantId();
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void rollback() {
        if (tx != null) {
            tx.rollback();
            tx = null;
        }
    }

    public Session getSession() {
        return session;
    }
}
