package ch.ninecode.gl

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Timestamp
import java.sql.Types
import java.util.Calendar
import java.util.HashMap

import org.apache.spark.graphx.VertexId
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel
import org.scalatest.fixture.FunSuite

import ch.ninecode.cim._
import ch.ninecode.model._

import javax.xml.bind.DatatypeConverter

class PowerFeedingSuite extends FunSuite
{
    val FILE_DEPOT = "src/test/resources/"

    type FixtureParam = SparkSession

    def withFixture (test: OneArgTest): org.scalatest.Outcome =
    {
        // create the fixture
        val start = System.nanoTime ()

        // create the configuration
        val configuration = new SparkConf (false)
        configuration.setAppName ("PowerFeedingSuite")
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
        configuration.registerKryoClasses (Array (classOf[PreEdge], classOf[Extremum], classOf[PostEdge]))
        // register topological classes
        configuration.registerKryoClasses (Array (classOf[CuttingEdge], classOf[TopologicalData]))
        // register GridLAB-D classes
        configuration.registerKryoClasses (Array (
            classOf[ch.ninecode.gl.PreNode],
            classOf[ch.ninecode.gl.PreEdge],
            classOf[ch.ninecode.gl.PV],
            classOf[ch.ninecode.gl.Transformer],
            classOf[ch.ninecode.gl.ThreePhaseComplexDataElement]))

        val session = SparkSession.builder ().config (configuration).getOrCreate () // create the fixture
        session.sparkContext.setLogLevel ("OFF") // Valid log levels include: ALL, DEBUG, ERROR, FATAL, INFO, OFF, TRACE, WARN

        val end = System.nanoTime ()
        println ("setup : " + (end - start) / 1e9 + " seconds")
        try
        {
            withFixture (test.toNoArgTest (session)) // "loan" the fixture to the test
        }
        finally session.stop () // clean up the fixture
    }

    def readFile (session: SparkSession, filename: String): DataFrame =
    {
        val files = filename.split (",")
        val options = new HashMap[String, String] ()
        options.put ("path", filename)
        options.put ("StorageLevel", "MEMORY_AND_DISK_SER")
        options.put ("ch.ninecode.cim.make_edges", "false")
        options.put ("ch.ninecode.cim.do_join", "false")
        options.put ("ch.ninecode.cim.do_topo", "true")
        options.put ("ch.ninecode.cim.do_topo_islands", "true")
        val element = session.read.format ("ch.ninecode.cim").options (options).load (files:_*)

        return (element)
    }

    test ("Basic")
    {
        session: SparkSession ⇒

        val begin = System.nanoTime ()

        val use_topological_nodes = true
        val root = if (true) "bkw_cim_export_haelig" else "bkw_cim_export_haelig_no_EEA7355" // Hälig*/
        
        val filename = FILE_DEPOT + root + ".rdf"

        val elements = readFile (session, filename)
        println (elements.count () + " elements")
        val read = System.nanoTime ()
        println ("read : " + (read - begin) / 1e9 + " seconds")

        // set up for execution
        val gridlabd = new GridLABD (session)
        gridlabd.HDFS_URI = "" // local
        gridlabd._StorageLevel = StorageLevel.MEMORY_AND_DISK_SER

        // prepare the initial graph
        val initial = gridlabd.prepare ()
        
        val _transformers = new Transformers ()
        val tdata = _transformers.getTransformerData (session)
        tdata.persist (gridlabd._StorageLevel)
        // ToDo: fix this 1kV multiplier on the voltages
        val niederspannug = tdata.filter ((td) => td.voltages (0) != 0.4 && td.voltages (1) == 0.4)
        val transformers = niederspannug.map ((t) => t.transformer.id).collect
        
        val pn = PreNode ("", 0.0) // just to access the vertex_id function
        val solars = gridlabd.getSolarInstallations (use_topological_nodes)
        val power_feeding = new PowerFeeding(initial)
        val trafo_with_eaa = transformers.par.map(trafo => 
              {
                val terminal = gridlabd.get ("Terminal").asInstanceOf[RDD[Terminal]].filter ((terminal) => terminal.ConductingEquipment == trafo).first
                val start_id = Array[VertexId] (pn.vertex_id (if (use_topological_nodes) terminal.TopologicalNode else terminal.ConnectivityNode))
                val (traced_nodes, traced_edges) = power_feeding.trace(start_id, trafo)
                val house_nodes = power_feeding.get_treshold_per_has(traced_nodes.values.filter(_.source_obj != ""))
                val traced_house_nodes_EEA = power_feeding.has_eea(house_nodes, solars)
                                
                val has = traced_house_nodes_EEA.map(node => 
                  {
                    val result = node._2 match
                    {
                      case Some (eea) => 
                        node._1.copy(has_eea = true)
                      case None => 
                        node._1
                    }
                    result
                  }).distinct
                
                Database.store_precalculation ("Threshold Precalculation", Calendar.getInstance ()) (trafo, has)
                
                (trafo, has.filter(_.has_eea).count > 0)
              })
            
        val trafo_string = trafo_with_eaa.filter(_._2).map(_._1).distinct.toArray.mkString("\n")
        gridlabd.writeInputFile("trafos_with_eea", "trafos.txt", trafo_string.getBytes (StandardCharsets.UTF_8))  
    }

}
