package ru.neoflex.emf.sparksql;

import antlr.RecognitionException;
import antlr.TokenStreamException;
import antlr.collections.AST;
import com.fasterxml.jackson.databind.JsonNode;
import com.mchange.v2.c3p0.impl.NewProxyConnection;
import org.apache.spark.sql.catalyst.analysis.NamedRelation;
import org.apache.spark.sql.catalyst.analysis.UnresolvedFunction;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.BinaryOperator;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.plans.logical.Filter;
import org.apache.spark.sql.catalyst.plans.logical.Project;
import org.apache.spark.sql.catalyst.plans.logical.SubqueryAlias;
import org.apache.spark.sql.catalyst.trees.TreeNode;
import org.apache.spark.sql.execution.SparkSqlParser;
import org.apache.spark.sql.internal.SQLConf;
import org.eclipse.emf.ecore.resource.Resource;
import org.h2.command.Command;
import org.h2.engine.Session;
import org.h2.jdbc.JdbcConnection;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.hql.internal.ast.HqlParser;
import org.hibernate.hql.internal.ast.QueryTranslatorImpl;
import org.hibernate.hql.internal.ast.util.NodeTraverser;
import org.hibernate.hql.internal.ast.util.TokenPrinters;
import org.hibernate.jdbc.ReturningWork;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.neoflex.emf.restserver.DBServerSvc;
import scala.collection.Iterator;
import org.h2.command.Parser;

import javax.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Logger;

@RestController()
@RequestMapping("/sparksql")
public class SparksqlController {
    Logger logger = Logger.getLogger(SparksqlController.class.getName());
    final DBServerSvc dbServerSvc;

    public SparksqlController(DBServerSvc dbServerSvc) {
        this.dbServerSvc = dbServerSvc;
    }

    @PostConstruct
    void init() {
        dbServerSvc.getDbServer().registerEPackage(SparksqlPackage.eINSTANCE);
    }

    Node createNode(TreeNode treeNode) {
        Node result;
        if (treeNode instanceof Project) {
            result = createProjectNode((Project) treeNode);
        } else if (treeNode instanceof Attribute) {
            result = createAttributeNode((Attribute) treeNode);
        } else if (treeNode instanceof Literal) {
            result = createLiteralNode((Literal) treeNode);
        } else if (treeNode instanceof BinaryOperator) {
            result = createBinaryOperatorNode((BinaryOperator) treeNode);
        } else if (treeNode instanceof SubqueryAlias) {
            result = createSubqueryAliasNode((SubqueryAlias) treeNode);
        } else if (treeNode instanceof NamedRelation) {
            result = createNamedRelationNode((NamedRelation) treeNode);
        } else if (treeNode instanceof Filter) {
            result = createFilterNode((Filter) treeNode);
        } else if (treeNode instanceof UnresolvedFunction) {
            result = createUnresolvedFunctionNode((UnresolvedFunction) treeNode);
        } else if (treeNode instanceof NamedExpression) {
            result = createNamedExpressionNode((NamedExpression) treeNode);
        } else {
            result = SparksqlFactory.eINSTANCE.createNode();
        }
        result.setNodeName(treeNode.getClass().getSimpleName());
        result.setDescription(treeNode.toString());
        result.setLine(treeNode.origin().line().getOrElse(() -> 0) - 1);
        result.setStartPosition(treeNode.origin().startPosition().getOrElse(() -> null));
        Iterator it = treeNode.children().iterator();
        while (it.hasNext()) {
            TreeNode child = (TreeNode) it.next();
            result.getChildren().add(createNode(child));
        }
        return result;
    }

    private Node createUnresolvedFunctionNode(UnresolvedFunction treeNode) {
        UnresolvedFunctionNode result = SparksqlFactory.eINSTANCE.createUnresolvedFunctionNode();
        result.setName(treeNode.name().toString());
        for (Iterator it = treeNode.arguments().iterator(); it.hasNext(); ) {
            TreeNode t = (TreeNode) it.next();
            result.getArguments().add(createNode(t));
        }
        return result;
    }

    private Node createProjectNode(Project treeNode) {
        ProjectNode result = SparksqlFactory.eINSTANCE.createProjectNode();
        for (Iterator it = treeNode.projectList().iterator(); it.hasNext(); ) {
            TreeNode t = (TreeNode) it.next();
            result.getProjectList().add(createNode(t));
        }
        return result;
    }

    private Node createAttributeNode(Attribute treeNode) {
        AttributeNode result = SparksqlFactory.eINSTANCE.createAttributeNode();
        result.setName(treeNode.name());
        return result;
    }

    private Node createLiteralNode(Literal treeNode) {
        LiteralNode result = SparksqlFactory.eINSTANCE.createLiteralNode();
        result.setValue(treeNode.value());
        result.setDataType(treeNode.dataType().typeName());
        return result;
    }

    private Node createBinaryOperatorNode(BinaryOperator treeNode) {
        BinaryOperatorNode result = SparksqlFactory.eINSTANCE.createBinaryOperatorNode();
        result.setSymbol(treeNode.symbol());
        return result;
    }

    private Node createSubqueryAliasNode(SubqueryAlias treeNode) {
        SubqueryAliasNode result = SparksqlFactory.eINSTANCE.createSubqueryAliasNode();
        result.setAlias(treeNode.alias());
        return result;
    }

    private Node createNamedRelationNode(NamedRelation treeNode) {
        NamedRelationNode result = SparksqlFactory.eINSTANCE.createNamedRelationNode();
        result.setName(treeNode.name());
        return result;
    }

    private Node createFilterNode(Filter treeNode) {
        FilterNode result = SparksqlFactory.eINSTANCE.createFilterNode();
        result.setCondition(createNode(treeNode.condition()));
        return result;
    }

    private Node createNamedExpressionNode(NamedExpression treeNode) {
        NamedExpressionNode result = SparksqlFactory.eINSTANCE.createNamedExpressionNode();
        result.setName(treeNode.name());
        return result;
    }

    @PostMapping(value = "/parseSparkSQL", consumes = {"text/plain"})
    public JsonNode parseSparkSQL(ParsingType parsingType, @RequestBody String sql) throws Exception {
        SQLConf sqlConf = new SQLConf();
        SparkSqlParser parser = new SparkSqlParser(sqlConf);
        ParsingQuery parsingQuery = SparksqlFactory.eINSTANCE.createParsingQuery();
        if (parsingType != null) {
            parsingQuery.setParsingType(parsingType);
        }
        parsingQuery.setSql(sql);
        TreeNode plan = Objects.equals(parsingQuery.getParsingType(), ParsingType.PLAN) ?
                parser.parsePlan(sql) : parser.parseExpression(sql);
        Node planNode = createNode(plan);
        parsingQuery.setParsingResult(planNode);
        return dbServerSvc.getDbServer().inTransaction(false, tx -> {
            Resource resource = tx.createResource();
            resource.getContents().add(parsingQuery);
            resource.save(null);
            return DBServerSvc.createJsonHelper().toJson(resource);
        });
    }

    @PostMapping(value = "/parseHQL", consumes = {"text/plain"})
    public AST parseHQL(@RequestBody String hql) {
        final HqlParser parser = HqlParser.getInstance(hql);
        parser.setFilter(true);

        try {
            parser.statement();
        } catch (RecognitionException | TokenStreamException e) {
            throw new HibernateException("Unexpected error parsing HQL", e);
        }

        final AST hqlAst = parser.getAST();
        parser.getParseErrorHandler().throwQueryException();
        final NodeTraverser walker = new NodeTraverser(new QueryTranslatorImpl.JavaConstantConverter((SessionFactoryImplementor) dbServerSvc.getDbServer().getSessionFactory()));
        walker.traverseDepthFirst(hqlAst);

        logger.info(TokenPrinters.HQL_TOKEN_PRINTER.showAsString(hqlAst, "--- HQL AST ---"));

        return hqlAst;
    }

    @PostMapping(value = "/parseH2", consumes = {"text/plain"})
    public Command parseH2(@RequestBody String sql) throws Exception {
        return dbServerSvc.getDbServer().inTransaction(true, tx ->
                tx.getSession().doReturningWork(connection ->
                {
                    NewProxyConnection proxyConnection = (NewProxyConnection) connection;
                    org.h2.jdbc.JdbcConnection jdbcConnection = (JdbcConnection) proxyConnection.unwrap(JdbcConnection.class);
                    Parser parser = new Parser((Session) jdbcConnection.getSession());
                    Command command = parser.prepareCommand(sql);
                    command.prepareJoinBatch();
                    return command;
                }));
    }

    @PostMapping(value = "/queryNative", consumes = {"text/plain"})
    public Object queryNative(@RequestBody String sql) throws Exception {
        return dbServerSvc.getDbServer().inTransaction(true, tx -> {
            return tx.getSession().createSQLQuery(sql).list();
        });
    }
}
