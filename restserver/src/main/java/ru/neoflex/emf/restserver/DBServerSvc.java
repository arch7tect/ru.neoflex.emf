package ru.neoflex.emf.restserver;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.plugin.EcorePlugin;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xcore.XcoreStandaloneSetup;
import org.eclipse.emf.ecore.xmi.impl.EcoreResourceFactoryImpl;
import org.eclipse.xtext.resource.XtextResourceSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ru.neoflex.emf.base.HbResource;
import ru.neoflex.emf.base.HbServer;

import javax.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class DBServerSvc {
    @Autowired
    Environment env;
    private HbServer hbServer;

    {
        XcoreStandaloneSetup.doSetup();
    }

    @PostConstruct
    public void init() throws Exception {
        Properties props = new Properties();
        MutablePropertySources propSrcs = ((AbstractEnvironment) env).getPropertySources();
        StreamSupport.stream(propSrcs.spliterator(), false)
                .filter(ps -> ps instanceof EnumerablePropertySource)
                .map(ps -> ((EnumerablePropertySource) ps).getPropertyNames())
                .flatMap(Arrays::<String>stream)
                .forEach(propName -> props.setProperty(propName, env.getProperty(propName)));
        String dbName = props.getProperty("db-name", "restserverdb");
        hbServer = new HbServer(dbName, props);
        hbServer.registerDynamicPackages();
    }

    public HbServer getDbServer() {
        return hbServer;
    }

    public Resource uploadXcore(InputStream is, String fileName) {
        try {
            EPackage ePackage = loadXcorePackage(is, fileName);
            return saveEPackage(ePackage);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Resource saveEPackage(EPackage ePackage) throws Exception {
        Resource result = getDbServer().inTransaction(false, tx -> {
            ResourceSet rs = tx.getResourceSet();
            Resource resource = getDbServer().findBy(rs, EcorePackage.Literals.EPACKAGE, ePackage.getNsURI());
            if (resource.getContents().size() > 0) {
                getDbServer().setId(ePackage, getDbServer().getId(resource.getContents().get(0)));
                resource.unload();
            }
            resource.getContents().add(ePackage);
            resource.save(null);
            return resource;
        });
        getDbServer().getPackageRegistry().put(ePackage.getNsURI(), ePackage);
        return result;
    }

    public EPackage loadXcorePackage(InputStream is, String fileName) throws IOException {
        if (StringUtils.isEmpty(fileName)) {
            fileName = "file:///temp.xcore";
        } else {
            fileName = "file:///" + Paths.get(fileName).getFileName().toString().replaceAll("\\.[^.]*$", ".xcore");
        }
        XtextResourceSet xrs = createXtextResourceSet();
        Resource xresource = xrs.createResource(URI.createURI(fileName));
        xresource.load(is, null);
//        EcoreUtil.resolveAll(xresource);
        List<EObject> eObjects = xresource.getContents();
        EPackage ePackage = (EPackage) EcoreUtil.copy(eObjects.get(2));
        return ePackage;
    }

    public static XtextResourceSet createXtextResourceSet() {
        XtextResourceSet rs = new XtextResourceSet();
        rs.getURIResourceMap().put(
                URI.createURI("platform:/resource/org.eclipse.emf.ecore/model/Ecore.ecore"),
                EcorePackage.eINSTANCE.eResource()
        );
//        rs.getURIResourceMap().put(
//                URI.createURI("platform:/resource/org.eclipse.emf.ecore/model/Ecore.genmodel"),
//                GenModelPackage.eINSTANCE.eResource()
//        );
        String baseEcoreUrl = EcorePlugin.INSTANCE.getBaseURL().toExternalForm();
        rs.getURIConverter().getURIMap().put(
                URI.createURI("platform:/resource/org.eclipse.emf.ecore/"),
                URI.createURI(baseEcoreUrl)
        );
        return rs;
    }

    public Resource uploadEcore(InputStream is, String fileName) {
        try {
            EPackage ePackage = loadEcorePackage(is, fileName);
            return saveEPackage(ePackage);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public EPackage loadEcorePackage(InputStream is, String fileName) throws IOException {
        if (StringUtils.isEmpty(fileName)) {
            fileName = "file:///temp.ecore";
        } else {
            fileName = "file:///" + Paths.get(fileName).getFileName().toString().replaceAll("\\.[^.]*$", ".ecore");
        }
        ResourceSet ers = new ResourceSetImpl();
        ers.getResourceFactoryRegistry().getExtensionToFactoryMap().put("*", new EcoreResourceFactoryImpl());
        Resource eresource = ers.createResource(URI.createURI(fileName));
        eresource.load(is, null);
        List<EObject> eObjects = eresource.getContents();
        EPackage ePackage = (EPackage) EcoreUtil.copy(eObjects.get(0));
        return ePackage;
    }

    public static byte[] ePackages2Ecore(List<EPackage> ePackages) throws IOException {
        ResourceSet ers = new ResourceSetImpl();
        ers.getResourceFactoryRegistry().getExtensionToFactoryMap().put("ecore", new EcoreResourceFactoryImpl());
        Resource er = ers.createResource(URI.createURI("file:///model.ecore"));
        er.getContents().addAll(EcoreUtil.copyAll(ePackages));
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        er.save(os, null);
        byte[] ecore = os.toByteArray();
        return ecore;
    }

    public byte[] downloadEcore(String nsUri) {
        try {
            return getDbServer().inTransaction(true, tx -> {
                ResourceSet rs = tx.getResourceSet();
                List<EPackage> ePackages = getDbServer().findBy(rs, EcorePackage.Literals.EPACKAGE, nsUri)
                        .getContents().stream()
                        .map(eObject -> (EPackage) eObject)
                        .collect(Collectors.toList());
                ePackages.forEach(EcoreUtil::resolveAll);
                return ePackages2Ecore(ePackages);
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static JsonHelper createJsonHelper() {
        return new JsonHelper() {
            @Override
            protected void setId(Resource resource, EObject eObject, String id) {
                if (resource instanceof HbResource) {
                    HbResource hbResource = (HbResource) resource;
                    if (id != null) {
                        hbResource.getDbServer().setId(eObject, Long.parseLong(id));
                    }
                }
            }

        };

    }
}
