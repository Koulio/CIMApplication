package ch.ninecode.cim.connector;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.importer.ZipImporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.ResourceAdapterArchive;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.resource.ResourceException;
import javax.resource.cci.Connection;
import javax.resource.cci.ConnectionFactory;
import javax.resource.cci.ConnectionMetaData;
import javax.resource.cci.Interaction;
import javax.resource.cci.MappedRecord;
import javax.resource.cci.Record;
import javax.resource.spi.ResourceAdapter;
import java.io.File;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * CIM Resource Adapter test suite
 */
@RunWith (Arquillian.class)
public class CIMResourceAdapterTest
{
    @SuppressWarnings ("unused")
    @Deployment
    public static ResourceAdapterArchive createDeployment ()
    {
        final ResourceAdapterArchive rar;

        // Note:
        // You can either build a simplified rar, or use the maven created rar.
        // The latter depends on a successful:
        //    mvn clean install

        if (true)
        {
            // build a simplified rar
            final JavaArchive jar = ShrinkWrap.create (JavaArchive.class, "CIMConnector.jar");
            jar.addPackage (java.lang.Package.getPackage ("ch.ninecode.cim.connector"));

            rar = ShrinkWrap.create (ResourceAdapterArchive.class, "CIMConnector.rar");
            rar.addAsLibrary (jar);
            rar.addAsManifestResource (new File ("src/main/resources/META-INF/ra.xml"), "ra.xml");
            System.out.println (rar.toString (true));
            // you can examine the contents of the simplified rar using the following line
            if (true)
                rar.as (ZipExporter.class).exportTo (new File ("target/CIMConnector.rar"), true);
        }
        else
            // use the rar generated by maven
            rar = ShrinkWrap
                .create (ZipImporter.class, "CIMConnector.rar")
                .importFrom (new File("target/CIMConnector-1.0-SNAPSHOT.rar"))
                .as (ResourceAdapterArchive.class);

        return rar;
    }

    /**
     * Build a connection specification used by all the tests.
     * @return
     */
    CIMConnectionSpec remoteConfig ()
    {
        CIMConnectionSpec ret;

        ret = new CIMConnectionSpec ();
        ret.setUserName ("derrick"); // not currently used
        ret.setPassword ("secret"); // not currently used
        ret.getProperties ().put ("spark.driver.memory", "1g");
        ret.getProperties ().put ("spark.executor.memory", "4g");
        ret.getJars ().add ("../../CIMScala/target/CIMScala-1.6.0-SNAPSHOT.jar"); // assumes CIMScala project is peer of CIMApplication

        return (ret);
    }

    @Test
    public void testResourceAdapter () throws Exception
    {
        final Properties properties = new Properties ();
        properties.setProperty (Context.INITIAL_CONTEXT_FACTORY, "org.apache.openejb.client.LocalInitialContextFactory");
        properties.setProperty ("openejb.deployments.classpath.include", ".*resource-injection.*");
        final InitialContext initialContext = new InitialContext (properties);
        final ConnectionFactory connectionFactory = (ConnectionFactory) initialContext.lookup ("java:openejb/Resource/CIMConnector"); // from id of connector element in ra.xml
        assertNotNull ("connectionFactory", connectionFactory);
        final Connection connection = connectionFactory.getConnection (remoteConfig ());
        assertNotNull ("connection", connection);
        connection.close ();
        final ResourceAdapter resourceAdapter = (ResourceAdapter) initialContext.lookup ("java:openejb/Resource/CIMResourceAdapter"); // from id of resourceadapter element in ra.xml
        assertNotNull ("resourceAdapter", resourceAdapter);
        assertNotNull ("YarnConfigurationPath", ((CIMResourceAdapter) resourceAdapter).getYarnConfigurationPath ());
    }

    @Test
    public void testMetadata () throws Exception
    {
        final Properties properties = new Properties ();
        properties.setProperty (Context.INITIAL_CONTEXT_FACTORY, "org.apache.openejb.client.LocalInitialContextFactory");
        properties.setProperty ("openejb.deployments.classpath.include", ".*resource-injection.*");
        final InitialContext initialContext = new InitialContext (properties);
        final ConnectionFactory connectionFactory = (ConnectionFactory) initialContext.lookup ("java:openejb/Resource/CIMConnector");
        assertNotNull ("connectionFactory", connectionFactory);
        final Connection connection = connectionFactory.getConnection (remoteConfig ());
        assertNotNull ("connection", connection);
        ConnectionMetaData meta = connection.getMetaData ();
        assertNotNull ("meta data", meta);
        assertEquals ("Spark", meta.getEISProductName ());
        assertNotNull ("product version", meta.getEISProductVersion ()); // assertEquals ("1.6.0", meta.getEISProductVersion ());
        assertNotNull ("user name", meta.getUserName ()); // assertEquals ("derrick", meta.getUserName ());
        connection.close ();
    }

    @SuppressWarnings ("unchecked")
    @Test
    public void testRead () throws Exception
    {
        long ELEMENTS = new Long (351980l);
        final Properties properties = new Properties ();
        properties.setProperty (Context.INITIAL_CONTEXT_FACTORY, "org.apache.openejb.client.LocalInitialContextFactory");
        properties.setProperty ("openejb.deployments.classpath.include", ".*resource-injection.*");
        final InitialContext initialContext = new InitialContext (properties);
        final ConnectionFactory connectionFactory = (ConnectionFactory) initialContext.lookup ("java:openejb/Resource/CIMConnector");
        assertNotNull ("connectionFactory", connectionFactory);
        final Connection connection = connectionFactory.getConnection (remoteConfig ());
        assertNotNull ("connection", connection);
        final CIMInteractionSpecImpl spec = new CIMInteractionSpecImpl ();
        spec.setFunctionName (CIMInteractionSpec.READ_FUNCTION);
        final MappedRecord input = connectionFactory.getRecordFactory ().createMappedRecord (CIMMappedRecord.INPUT);
        input.setRecordShortDescription ("record containing the file name with key filename");
        input.put ("filename", "hdfs://sandbox:9000/data/NIS_CIM_Export_NS_INITIAL_FILL.rdf");
        final MappedRecord output = connectionFactory.getRecordFactory ().createMappedRecord (CIMMappedRecord.OUTPUT);
        output.setRecordShortDescription ("record that will return key count");
        final Interaction interaction = connection.createInteraction ();
        assertTrue ("interaction returned false", interaction.execute (spec, input, output));
        assertFalse ("interaction returned empty", output.isEmpty ());
        assertEquals ("interaction returned wrong value", ELEMENTS, output.get ("count"));
        interaction.close ();
        connection.close ();
    }

    @SuppressWarnings ("unchecked")
    @Test
    public void testReadEnergyConsumer () throws Exception
    {
        final Properties properties = new Properties ();
        properties.setProperty (Context.INITIAL_CONTEXT_FACTORY, "org.apache.openejb.client.LocalInitialContextFactory");
        properties.setProperty ("openejb.deployments.classpath.include", ".*resource-injection.*");
        final InitialContext initialContext = new InitialContext (properties);
        final ConnectionFactory connectionFactory = (ConnectionFactory) initialContext.lookup ("java:openejb/Resource/CIMConnector");
        assertNotNull ("connectionFactory", connectionFactory);
        final Connection connection = connectionFactory.getConnection (remoteConfig ());
        assertNotNull ("connection", connection);
        final CIMInteractionSpecImpl spec = new CIMInteractionSpecImpl ();
        spec.setFunctionName (CIMInteractionSpec.GET_DATAFRAME_FUNCTION);
        final MappedRecord input = connectionFactory.getRecordFactory ().createMappedRecord (CIMMappedRecord.INPUT);
        input.setRecordShortDescription ("record containing the file name with key filename and sql query with key query");
        input.put ("filename", "hdfs://sandbox:9000/data/NIS_CIM_Export_NS_INITIAL_FILL.rdf");
        input.put ("query", "select s.sup.sup.sup.sup.mRID mRID, s.sup.sup.sup.sup.aliasName aliasName, s.sup.sup.sup.sup.name name, s.sup.sup.sup.sup.description description, p.xPosition, p.yPosition from EnergyConsumer s, PositionPoint p where s.sup.sup.sup.Location = p.Location and p.sequenceNumber = 0");
        final Interaction interaction = connection.createInteraction ();
        final Record output = interaction.execute (spec, input);
        assertNotNull ("output", output);
        if ((!output.getClass ().isAssignableFrom (CIMResultSet.class)))
            throw new ResourceException ("object of class " + output.getClass ().toGenericString () + " is not a ResultSet");
        else
        {
            CIMResultSet resultset = (CIMResultSet)output;
            assertTrue ("resultset is empty", resultset.next ());
            assertNotNull ("mRID", resultset.getString (1));
            assertTrue ("zero x coordinate", 0.0 != resultset.getDouble (5));
            assertTrue ("zero y coordinate", 0.0 != resultset.getDouble (6));
            resultset.close ();
        }
        interaction.close ();
        connection.close ();
    }

}
