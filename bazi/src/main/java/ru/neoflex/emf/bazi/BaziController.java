package ru.neoflex.emf.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.kie.api.io.Resource;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.QueryResults;
import org.kie.api.runtime.rule.QueryResultsRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.neoflex.emf.restserver.DBServerSvc;
import ru.neoflex.emf.restserver.JsonHelper;
import ru.neoflex.nfcore.bazi.natalChart.*;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestController()
@RequestMapping("/bazi")
public class BaziController {
    final
    DroolsSvc droolsSvc;
    final
    DBServerSvc dbServerSvc;

    public BaziController(DroolsSvc droolsSvc, DBServerSvc dbServerSvc) {
        this.droolsSvc = droolsSvc;
        this.dbServerSvc = dbServerSvc;
    }

    @PostConstruct
    void init() {
        droolsSvc.getGlobals().add(new AbstractMap.SimpleEntry<>("dbServerSvc", dbServerSvc));
        droolsSvc.getResourceFactories().add(() -> {
            List<Resource> resources = new ArrayList<>();
            resources.add(DroolsSvc.createClassPathResource("baseRules.drl", null));
            try {
                byte[] bazi = Files.readAllBytes(Paths.get(System.getProperty("user.dir"), "bazi", "rules", "bazi.drl"));
                resources.add(DroolsSvc.createByteArrayResource("bazi.drl", null, bazi));
            }
            catch (IOException e) {
                throw new IllegalArgumentException(e);
            }
            return resources;
        });
        droolsSvc.setDebug(true);
    }

    @PostMapping("/refreshRules")
    void refreshRules() {
        droolsSvc.disposeContainer();
    }

    @GetMapping("/natalChart")
    JsonNode getNatalChart(String name,
                           Integer minutes,
                           Integer hour,
                           Integer day,
                           Integer month,
                           Integer year,
                           Integer UTC,
                           String placeOfBirth,
                           Sex sex,
                           boolean joinedRatHour,
                           TimeCategory timeCategory
    ) throws Exception {
        return dbServerSvc.getDbServer().inTransaction(false, tx -> {
            Parameters parameters = NatalChartFactory.eINSTANCE.createParameters();
            parameters.setName(name);
            parameters.setMinutes(minutes);
            parameters.setHour(hour);
            parameters.setDay(day);
            parameters.setMonth(month);
            parameters.setYear(year);
            parameters.setUTC(UTC);
            parameters.setPlaceOfBirth(placeOfBirth);
            parameters.setSex(sex);
            parameters.setJoinedRatHour(joinedRatHour);
            parameters.setTimeCategory(timeCategory);
            KieSession kieSession = droolsSvc.createSession();
            try {
                kieSession.setGlobal("tx", tx);
                kieSession.insert(parameters);
                kieSession.fireAllRules();
                QueryResults queryResults = kieSession.getQueryResults("EObjects");
                org.eclipse.emf.ecore.resource.Resource eObjects = tx.createResource();
                for (QueryResultsRow row: queryResults) {
                    Object o = row.get("$eObject");
                    if (o instanceof EObject) {
                        EObject eObject = (EObject) o;
                        if (EcoreUtil.getRootContainer(eObject) == eObject) {
                            eObjects.getContents().add(eObject);
                        }
                    }
                }
                eObjects.save(null);
                org.eclipse.emf.ecore.resource.Resource natalCharts = tx.createResource();
                natalCharts.getContents().addAll(eObjects.getContents().stream()
                        .filter(eObject -> eObject instanceof NatalChart).collect(Collectors.toList()));
                return JsonHelper.resourceToJson(natalCharts);
            }
            finally {
                kieSession.dispose();
            }
        });
    }


}