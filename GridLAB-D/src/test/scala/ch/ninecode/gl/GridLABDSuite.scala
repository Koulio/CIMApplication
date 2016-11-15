package ch.ninecode.gl

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Calendar
import java.util.HashMap
import java.util.Map
import javax.xml.bind.DatatypeConverter
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.util.Random

import org.apache.commons.io.FileUtils
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.SQLContext
import org.apache.spark.storage.StorageLevel

import org.scalatest.fixture

import ch.ninecode.cim._
import ch.ninecode.model._

class GridLABDSuite extends fixture.FunSuite
{
    case class ContextPair (_SparkContext: SparkContext, _SQLContext: SQLContext)

    val FILE_DEPOT = "/home/derrick/Documents/9code/nis/cim/cim_export/"

    type FixtureParam = ContextPair

    def withFixture (test: OneArgTest): org.scalatest.Outcome =
    {
        // create the fixture
        val start = System.nanoTime ()

        // create the configuration
        val configuration = new SparkConf (false)
        configuration.setAppName ("GridLABDSuite")
        configuration.setMaster ("local[2]")
        configuration.set ("spark.driver.memory", "2g")
        configuration.set ("spark.executor.memory", "4g")
        //configuration.set ("spark.executor.extraJavaOptions", "-XX:+UseCompressedOops")
        configuration.set ("spark.executor.extraJavaOptions", "-XX:+UseCompressedOops -XX:+PrintGCDetails -XX:+PrintGCTimeStamps")

        // register low level classes
        configuration.registerKryoClasses (Array (classOf[Element], classOf[BasicElement], classOf[Unknown]))
        // register CIM case classes
        CHIM.apply_to_all_classes { x => configuration.registerKryoClasses (Array (x.runtime_class)) }
        // register edge related classes
        configuration.registerKryoClasses (Array (classOf[PreEdge], classOf[Extremum], classOf[Edge]))
        // register topological classes
        configuration.registerKryoClasses (Array (classOf[CuttingEdge], classOf[TopologicalData]))
        // register GridLAB-D classes
        configuration.registerKryoClasses (Array (
            classOf[ch.ninecode.gl.ShortCircuitData],
            classOf[ch.ninecode.gl.PreNode],
            classOf[ch.ninecode.gl.PreEdge],
            classOf[ch.ninecode.gl.PV],
            classOf[ch.ninecode.gl.Transformer],
            classOf[ch.ninecode.gl.Solution],
            classOf[ch.ninecode.gl.ComplexDataElement]))

        val context = new SparkContext (configuration)
        context.setLogLevel ("WARN") // Valid log levels include: ALL, DEBUG, ERROR, FATAL, INFO, OFF, TRACE, WARN
        val sql_context = new SQLContext (context)

        val end = System.nanoTime ()
        println ("setup : " + (end - start) / 1e9 + " seconds")
        try
        {
            withFixture (test.toNoArgTest (ContextPair (context, sql_context))) // "loan" the fixture to the test
        }
        finally context.stop () // clean up the fixture
    }

    def readFile (context: SQLContext, filename: String): DataFrame =
    {
        val files = filename.split (",")
        val options = new HashMap[String, String] ().asInstanceOf[Map[String,String]]
        options.put ("StorageLevel", "MEMORY_AND_DISK_SER")
        options.put ("ch.ninecode.cim.make_edges", "false")
        options.put ("ch.ninecode.cim.do_join", "true")
        options.put ("ch.ninecode.cim.do_topo", "true")
        options.put ("ch.ninecode.cim.do_topo_islands", "true")
        val element = context.read.format ("ch.ninecode.cim").options (options).load (files:_*)
        val plan = element.queryExecution
        val test = plan.toString ()
        if (!test.contains ("InputPaths"))
            throw new Exception ("input file not found: " + filename + "\n" + test)

        return (element)
    }

    def store(house: String, power: Double, t1: Calendar, results: RDD[Solution]): Int =
    {
        // load the sqlite-JDBC driver using the current class loader
        Class.forName ("org.sqlite.JDBC")

        var connection: Connection = null
        try
        {
            // create a database connection
            connection = DriverManager.getConnection ("jdbc:sqlite:results.db")

            // create schema
            val statement = connection.createStatement ()
            statement.executeUpdate ("drop table if exists simulation")
            statement.executeUpdate ("create table simulation (id integer primary key autoincrement, house string, power double, time datetime)")
            statement.executeUpdate ("drop table if exists results")
            statement.executeUpdate ("create table results (id integer primary key autoincrement, simulation integer, node string, time datetime, vreal double, vimag double)")
            statement.close ()

            // insert the simulation
            val now = Calendar.getInstance ()
            val insert = connection.prepareStatement ("insert into simulation (id, house, power, time) values (?, ?, ?, ?)")
            insert.setNull (1, Types.INTEGER)
            insert.setString (2, house)
            insert.setDouble (3, power)
            insert.setTimestamp (4, new Timestamp (now.getTimeInMillis))
            insert.executeUpdate ()
            val resultset = statement.executeQuery ("select last_insert_rowid() id")
            resultset.next ()
            val id = resultset.getInt ("id")

            // insert the results
            val datainsert = connection.prepareStatement ("insert into results (id, simulation, node, time, vreal, vimag) values (?, ?, ?, ?, ?, ?)")
            for (solution ← results.collect) {
                val c = Complex.fromPolar (solution.voltA_mag, solution.voltA_angle)
                datainsert.setNull (1, Types.INTEGER)
                datainsert.setInt (2, id)
                datainsert.setString (3, solution.node)
                datainsert.setTimestamp (4, new Timestamp (t1.getTimeInMillis))
                datainsert.setDouble (5, c.re)
                datainsert.setDouble (6, c.im)
                datainsert.executeUpdate ()
            }
            return (id)
        }
        catch
        {
            // if the error message is "out of memory",
            // it probably means no database file is found
            case e: SQLException ⇒ println ("exception caught: " + e); return (-1)
        }
        finally
        {
            try
            {
                if (connection != null)
                    connection.close()
            }
            catch {
                // connection close failed
                case e: SQLException ⇒ println ("exception caught: " + e);
            }
        }

    }

    def store_rdd (connection: Connection, id: Int, rdd: RDD[ComplexDataElement]): Unit =
    {
        // insert the results
        connection.setAutoCommit (false)
        val node = rdd.name.substring (0, rdd.name.length() - "_voltage.csv".length ())
        val datainsert = connection.prepareStatement ("insert into results (id, simulation, node, time, vreal, vimag) values (?, ?, ?, ?, ?, ?)")
        for (dataitem ← rdd.collect)
        {
            datainsert.setNull (1, Types.INTEGER)
            datainsert.setInt (2, id)
            datainsert.setString (3, node)
            datainsert.setTimestamp (4, new Timestamp (dataitem.millis))
            datainsert.setDouble (5, dataitem.value.re)
            datainsert.setDouble (6, dataitem.value.im)
            datainsert.executeUpdate ()
        }
        connection.commit ()
    }

    def load_and_store (sql_context: SQLContext, gridlabd: GridLABD, id: Int)
    {
        // load the sqlite-JDBC driver using the current class loader
        Class.forName ("org.sqlite.JDBC")

        var connection: Connection = null
        try
        {
            // create a database connection
            connection = DriverManager.getConnection ("jdbc:sqlite:results.db")
            val outputs = gridlabd.list_files (gridlabd._TempFilePrefix)
            for (x <- outputs)
            {
                if (x.endsWith ("_voltage.csv"))
                {
                    val data = gridlabd.read_records (sql_context, x)
                    data.name = x.substring (x.lastIndexOf ("/") + 1)
                    store_rdd (connection, id, data)
                }
            }

        }
        catch
        {
            // if the error message is "out of memory",
            // it probably means no database file is found
            case e: SQLException ⇒ println ("exception caught: " + e)
        }
        finally
        {
            try
            {
                if (connection != null)
                    connection.close()
            }
            catch {
                // connection close failed
                case e: SQLException ⇒ println ("exception caught: " + e);
            }
        }
    }

    test ("Basic")
    {
        a: ContextPair ⇒

        val start = System.nanoTime ()

        val context: SparkContext = a._SparkContext
        val sql_context: SQLContext = a._SQLContext

        val filename =
//            FILE_DEPOT + "NIS_CIM_Export_sias_current_20160816_V9_Guemligen" + ".rdf"
//            FILE_DEPOT + "NIS_CIM_Export_sias_current_20160816_V9_Bubenei" + ".rdf"
//            FILE_DEPOT + "NIS_CIM_Export_sias_current_20160816_V8_Bruegg" + ".rdf"
//            FILE_DEPOT + "NIS_CIM_Export_sias_current_20160608_V9_Preview_CKW_with_filter_EWS_Jessenenstrasse" + ".rdf"
            FILE_DEPOT + "NIS_CIM_Export_sias_current_20160816_Kiental_V9" + ".rdf"

//        "," +
//        FILE_DEPOT + "ISU_CIM_Export_20160505" + ".rdf"
        val elements = readFile (sql_context, filename)

        val read = System.nanoTime ()

        // set up for execution
        val gridlabd = new GridLABD ()
        gridlabd._StorageLevel = StorageLevel.MEMORY_AND_DISK_SER
        gridlabd._TempFilePrefix = "./output/"
        gridlabd._CSV = FILE_DEPOT + "KS_Leistungen.csv"

        // clean up from any prior failed run
        FileUtils.deleteDirectory (new File (gridlabd._TempFilePrefix))

        val house = "HAS174735" // EWS: "HAK63498" Bubenei: "HAS97010", Brügg: "HAS76580" or "HAS6830" or "HAS78459", Gümligen: "HAS10002", Kiental: "HAS174735"
        val power = 30000

        // val t0 = Calendar.getInstance ()
        // or
        // val t1 = t0.clone ().asInstanceOf[Calendar]
        // t1.add (Calendar.MINUTE, 1)
        // parse ISO8601 format date
        // 2015-11-18 12:00:00
        val t0 = javax.xml.bind.DatatypeConverter.parseDateTime ("2015-11-18 12:00:00".replace (" ", "T"))
        val t1 = javax.xml.bind.DatatypeConverter.parseDateTime ("2015-11-19 12:00:00".replace (" ", "T"))

        val result = gridlabd.export (context, sql_context,
            "equipment=" + house +
            ",power=" + power +
            ",topologicalnodes=true" +
            ",start=" + DatatypeConverter.printDateTime (t0) +
            ",finish=" + DatatypeConverter.printDateTime (t1) +
            ",feeder=false")

        val process = System.nanoTime ()

        val file = Paths.get (house + ".glm")
        Files.write (file, result.getBytes (StandardCharsets.UTF_8))
        val results = gridlabd.solve (context, sql_context, house)
        val id = store (house, power, t1, results)
        load_and_store (sql_context: SQLContext, gridlabd: GridLABD, id)

        val write = System.nanoTime ()

        println ("read : " + (read - start) / 1e9 + " seconds")
        println ("process: " + (process - read) / 1e9 + " seconds")
        println ("write: " + (write - process) / 1e9 + " seconds")
        println ()

        // clean up this run
        // FileUtils.deleteDirectory (new File (gridlabd._TempFilePrefix))
    }

}
