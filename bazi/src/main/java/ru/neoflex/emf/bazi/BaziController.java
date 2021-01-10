package ru.neoflex.emf.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.kie.api.io.Resource;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.QueryResults;
import org.kie.api.runtime.rule.QueryResultsRow;
import org.kie.internal.builder.DecisionTableInputType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.neoflex.emf.bazi.natalChart.*;
import ru.neoflex.emf.drools.DroolsSvc;
import ru.neoflex.emf.restserver.DBServerSvc;

import javax.annotation.PostConstruct;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@RestController()
@RequestMapping("/bazi")
public class BaziController {
    final
    DroolsSvc droolsSvc;
    final
    DBServerSvc dbServerSvc;

    private static final Logger logger = LoggerFactory.getLogger(BaziController.class);

    public BaziController(DroolsSvc droolsSvc, DBServerSvc dbServerSvc) {
        this.droolsSvc = droolsSvc;
        this.dbServerSvc = dbServerSvc;
    }

    public Integer daysBetween(Date d1, Date d2){
        return (int)( (d2.getTime() - d1.getTime()) / (1000 * 60 * 60 * 24));
    }

    @PostConstruct
    void init() {
        dbServerSvc.getDbServer().registerEPackage(NatalChartPackage.eINSTANCE);
        droolsSvc.getGlobals().add(new AbstractMap.SimpleEntry<>("dbServerSvc", dbServerSvc));
        droolsSvc.getResourceFactories().add(() -> {
            List<Resource> resources = new ArrayList<>();
            resources.add(DroolsSvc.createClassPathResource("baseRules.drl", null));
            resources.add(DroolsSvc.createDecisionTableResource("calendar.xls", DecisionTableInputType.XLS));

/*            //Удалить после тестирования дня
            DateFormat formatter = new SimpleDateFormat("dd.MM.yyyy");
            Date dayStart = null;
            try {
                dayStart = formatter.parse("01.01.0001");
            } catch (ParseException e) {
                e.printStackTrace();
            }
            Date dayEnd4 = null;
            Date dayEnd5 = null;
            Date dayEnd6 = null;
            Date dayEnd7 = null;
            Date dayEnd8 = null;
            try {
                dayEnd4 = formatter.parse("04.12.2021");
                dayEnd5 = formatter.parse("05.12.2021");
                dayEnd6 = formatter.parse("06.12.2021");
                dayEnd7 = formatter.parse("07.12.2021");
                dayEnd8 = formatter.parse("08.12.2021");
            } catch (ParseException e) {
                e.printStackTrace();
            }

            Integer days = daysBetween(dayStart ,dayEnd4);
            logger.info(String.valueOf(days), String.valueOf(days % 10 + 1));*/

//            try {
//                byte[] bazi = Files.readAllBytes(Paths.get(System.getProperty("user.dir"), "bazi", "rules", "bazi.drl"));
//                resources.add(DroolsSvc.createByteArrayResource("bazi.drl", null, bazi));
//            }
//            catch (IOException e) {
//                throw new IllegalArgumentException(e);
//            }
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
            InputParams parameters = NatalChartFactory.eINSTANCE.createInputParams();
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
                eObjects.getContents().removeIf(eObject -> !(eObject instanceof NatalChart));
                eObjects.load(null);
                return DBServerSvc.createJsonHelper().toJson(eObjects);
            }
            finally {
                kieSession.dispose();
            }
        });
    }


}
