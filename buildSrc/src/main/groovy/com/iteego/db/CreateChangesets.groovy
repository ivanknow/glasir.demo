package com.iteego.db

import org.gradle.api.tasks.TaskAction
import org.gradle.api.DefaultTask
import groovy.xml.MarkupBuilder
import org.gradle.api.logging.LogLevel
import com.iteego.glasir.build.api.AtgModule



public class CreateChangesets extends DefaultTask
{
  def tableNamePattern = /([^"\s']+)/
  //alter table dps_contact_info modify (phone_number varchar2(40))...
  def alterTablePattern = /(?is)alter\s+table\s+${tableNamePattern}\s+modify\s+\((.+)\)/
  //create or replace procedure dms_queue_flag...
  def createProcedurePattern = /(?is)\s*create(?:\s+or\s+replace)\s+?procedure\s+${tableNamePattern}/
  //alter session set NLS_LENGTH_SEMANTICS='CHAR'...
  def alterSessionPattern = /(?is)alter\s+session\s+set/

  /**
   * Used in sql -> liquibase conversions.
   */
  public static String DBTYPE_ORACLE = "ORACLE"
  public static String DBTYPE_H2 = "H2"

  // these 2 could be grouped by a single setting:
  def String mainModuleName = "env.Main.store.dev.oracle"
  def String instanceType = "production"

  // We will be using these server instance types below.
  def selectedInstanceTypes = [
    instanceType
  ]

  def selectedProducts = [
      //"commerce"
      //"endeca",
      "platform",
      //"siteadmin",
      "store"
  ]


  // Addons are important in the selection of data imports and repository loader actions.
  def selectedAddons = [
      "dcs-csr",
      "international",
      "merch",
      "previewOnManagement",
      "prodLock",
      "queryConsoleOnManagement",
      "storefront-full-setup",
      //"storefront_no_publishing",
      "switchingdatasource"
    ]

  @TaskAction
  public void run() {
    logger.log( LogLevel.INFO, "Executing TaskAction for class CreateChangesets" )

    def glasir = project.glasir
    def fullModuleList = glasir.moduleMap[mainModuleName].fullBuildOrder

    Map<String, Node> namedDatasourceMap = new HashMap<String,Node>()
    Map<String, Object> instanceTypeMap = new HashMap<String, Object>()

    // Map datasource name to sql files.
    Map<String, List<String>> sqlFiles = new HashMap<String, List<String>>()

    // It is not certain that we want to group imports by datasource, maybe it should just be a list.
    Map<String, List<ImportAndModule>> importNodes = new HashMap<String, List<ImportAndModule>>()
    List<ImportAndModule> flatImportNodes = new ArrayList<ImportAndModule>()

    List<groovy.util.Node> products = new ArrayList<Node>()

    println "Install Units: ${glasir.installUnits}"
    println "Top level module list: " + glasir.modules.collect({it.name}).join(",") + "\n"


    // Read product.xml files for products.
    //fullModuleList.findAll {
    glasir.modules.findAll {
      project.file("${it.dir}/cim/product.xml").exists()
    }.each { module ->
      def productxml = new File("${module.dir}/cim/product.xml")
      println "Scanning file $productxml"
      def parser = new groovy.util.XmlParser(false, false)
      // Set these two features to skip dtd verification.
      parser.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      parser.setFeature("http://xml.org/sax/features/namespaces", false)
      def root = parser.parse(productxml) // root should be the "database-initializer" node.

      if (root?.name() != "product" ) {
        println "Root element not recognized. Expected \"product\" Found \"${root.name()}\""
      } else {
        products.add( root )
        def productId = root.@id
        def requiredAddonsForThisProduct = root.'requires-addon-id'
        def requiredProductsForThisProduct = root.'product-id-required'
        def addons = root.'product-addon-group'

        println "Product \"$productId\" "
        if( requiredProductsForThisProduct ) println " required products: " + requiredProductsForThisProduct.collect{it.@id}.join(", ")
        if( requiredAddonsForThisProduct ) println " required addons  : " + requiredAddonsForThisProduct.collect{it.@id}.join(", ")

        def serverInstanceTypes = root.'server-instance-type'
        if( serverInstanceTypes ) println " instance types   : " + serverInstanceTypes.collect{it.@id}.join(", ")
        serverInstanceTypes.each { serverInstanceType ->
          def sitId = serverInstanceType.@id
          //println "  Server instance type \"$sitId\""
          instanceTypeMap[sitId] = serverInstanceType
        }

        root.'named-datasource'.each { nds ->
          namedDatasourceMap[ nds.@id ] = nds
        }
      }
    }

    def fromDbType = "oracle" // Used when selecting ddl files for the sql import.
    def toDbType = "h2"
    println "From DB type '$fromDbType' to '$toDbType'."

    // Todo: parse product.xml files instead to see what datasources go where.
    if(selectedInstanceTypes[0]=="management") {
      namedDatasourceMap[ "all" ] = namedDatasourceMap[ "management" ]
    }

    // These are some addons. Availability of addons depends on product selection.
    // (find glasir.demo/packages -print0|grep -zZ -i product\.xml|xargs -0 grep '<product\-addon id\=')
    //id="clicktoconnect"
    //id="cybersource"
    //id="dcs-csr"
    //id="externalPreviewServer"
    //id="fulfillment"
    //id="fulfillment_using_atg"
    //id="indexByProduct"
    //id="indexBySku"
    //id="international"
    //id="lock"
    //id="merch"
    //id="nonswitchingdatasource"
    //id="preview"
    //id="previewOnManagement"
    //id="prodLock"
    //id="pubLock"
    //id="queryConsoleOnManagement"
    //id="queryConsoleOnProduction"
    //id="reporting"
    //id="search"
    //id="staging"
    //id="storefront-basic-setup"
    //id="storefront-full-setup"
    //id="storefront_demo"
    //id="storefront_no_publishing"
    //id="switchingdatasource"

    /**
     * Parsed from product.xml files.
     */
    List<String> requiredDataSources = calculateRequiredDatasources( glasir.project, products, mainModuleName, selectedAddons )

    /**
     * This is where we put the resulting files that glasir.db will later consume.
     */
    File outputMainDirectory = new File( "./importRootDir-$mainModuleName-${selectedInstanceTypes.join("_")}")

    /**
     * Where .sql files go after they have been transformed into XML.
     */
    File outputDbDirectory = new File( outputMainDirectory, "db" )
    outputDbDirectory.deleteDir()

    /**
     * Where .sdl and .properties and .xml files go for data-import and repository-loader.
     */
    File outputImportDirectory = new File( outputMainDirectory, "import" )
    outputImportDirectory.deleteDir()

    println "Selected addons: ${selectedAddons.join(", ")}"
    println "Selected instance types: ${selectedInstanceTypes.join(", ")}"

    // Read dbinit.xml files for the modules.
    //glasir.modules.each { module ->
    fullModuleList.each { module ->
      File cimDir = new File(module.dir as File, "cim")
      if (cimDir.exists()) {
        println "--------------------------"
        File dbinit = new File(cimDir, "dbinit.xml")
        if (dbinit.exists()) {
          println "Module      : ${module}"
          println "Install Unit: ${module.installUnit}"
          println "dbinit.xml  : $dbinit"


          def root = null
          try {
            // Using the constructor arguments "validating" and "namespaceAware" should work. Doesn't.
            def parser = new groovy.util.XmlParser(false, false)
            // Set these two features to skip dtd verification.
            parser.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            parser.setFeature("http://xml.org/sax/features/namespaces", false)
            root = parser.parse(dbinit) // root should be the "database-initializer" node.


            if (root == null) {
              println "Root element not recognized. Expected \"database-initializer\" Found \"$root\""
            } else {
              // This tag name is used in both dbinit.dtd and product.dtd but they are different tags.
              def serverInstanceTypes = root.'server-instance-type'
              serverInstanceTypes.each { serverInstanceType ->
                if (selectedInstanceTypes.contains(serverInstanceType.@id)) {
                  println "Looking at server instance type \"${serverInstanceType.@id}\""

                  def dataSources = serverInstanceType.datasource
                  dataSources.each { datasource ->
                    String datasourceId = datasource.@id
                    if( requiredDataSources.contains( datasourceId ) ) {
                      println "  For data source $datasourceId"
                      def requiredSchemas = datasource.schema
                      requiredSchemas.each { requiredSchema ->
                        def requiredSchemaId = requiredSchema.@id
                        println "    Use schema ${requiredSchemaId}"

                        def schema = root.schema.find { it.@id == requiredSchemaId }
                        if (schema) {
                          schema.children().each { child ->
                            if (child.name() == "sql") {
                              //<!ELEMENT sql (path+)>
                              //<!ELEMENT path (requires-addon-id*,create,drop)>
                              scanSqlElement( selectedAddons, namedDatasourceMap, module, child, sqlFiles, datasourceId, dbinit)
                            }
                            else if (child.name() == "data-import") {
                              // DTD: <!ELEMENT data-import (requires-addon-id*,incompatible-addon-id*,repository-path,import-file-path,user?,workspace?,comment?)>
                              scanDataImportElement( selectedAddons, module, child, importNodes, flatImportNodes, datasourceId, dbinit )
                            }
                            else if (child.name() == "repository-loader") {
                              // DTD: <!ELEMENT repository-loader (requires-addon-id*,cleanup-src-module?,cleanup-file-path?,files+,file-mapping,folder-mapping)>
                              // DTD: <!ELEMENT files (src-module,(config-path|file-path),file-pattern)>
                              scanRepoLoaderElement
                            }
                            else {
                              println "ERROR! UNKNOWN SCHEMA CHILD NAME '${child.name()}'. EXPECTED sql, data-import or repository-loader."
                            }

                          } // schema.each
                        } else {
                          println "ERROR! MISSING SCHEMA '$requiredSchemaId' FOR DATASOURCE '${datasource.@id}' IN SERVER INSTANCE TYPE '${serverInstanceType.@id}' IN FILE '$dbinit'."
                        }
                      } // if data source has schemas
                    } else {
                      logger.info "Skipping data source \"$datasourceId\"."
                    }
                  }//for each data source
                } else {
                  println "Ignoring server instance type \"${serverInstanceType.@id}\""
                }
              }//for each server instance type

            }
          }
          catch (Throwable e) {
            println "Failed to read file: \"$dbinit\". Message: $e"
          }
        } else {
          println "(File not found: \"dbinit.xml\" for module \"${module.name}\")"
        }
      }//if directory cim exists
    }//for each module

    println ""
    println "Named JNDI data sources:\n ${namedDatasourceMap.collect{ "${it.key} -> ${it.value.jndi.text()}" }.join("\n ") }"
    println ""

    println "*******"
    println "* SQL *"
    println "*******"
    // Translate default JNDI names from ATG to Glasir.
    def iteegoDataDirectories = ['ATGProductionDS':'atg.core', 'ATGSwitchingDS_A':'atg.cat.a', 'ATGSwitchingDS_B':'atg.cat.b', 'ATGPublishingDS':'atg.pub']

    sqlFiles.keySet().eachWithIndex { jndiNameATG, jndiIndex ->
      def jndiName = iteegoDataDirectories[jndiNameATG] ?: jndiNameATG
      println "Datasource JNDI name: '$jndiName', dbType: '$toDbType'"
      File dbSubDir = new File( outputDbDirectory, toDbType )
      println "Create dbSubDir  : '$dbSubDir'"
      dbSubDir.mkdirs()
      File jndiSubDir = new File( dbSubDir, jndiName )
      println "Create jndiSubDir: '$jndiSubDir'"
      jndiSubDir.mkdirs()
      sqlFiles[ jndiNameATG ].eachWithIndex { templatedPath, index ->
        File actualFromFile = new File( templatedPath.replace( "\${database}", fromDbType ) )
        String toFileName = "${((jndiIndex+1)*100 + index).toString().padLeft(5,'0')}.${actualFromFile.name}.xml"
        File toFile = new File( jndiSubDir, toFileName )
        if( !fromDbType=="oracle") {
          throw new Exception("Only 'oracle' is supported as source database type at this time.")
        }
        createChangeSetFromSql( DBTYPE_ORACLE, DBTYPE_H2, actualFromFile, toFile, toFileName, "glasir", "all" )
        //println "$toFileName <- ${actualFromFile.toString()}"
      }
    }


    // Get data files from jar files or directories, copy them to specific places, create the imports.txt file.
    List<String> importCommands = new ArrayList<String>()

    importCommands.add( "Top level module list: $mainModuleName" )
    importCommands.add( "" )

    //importNodes.keySet().each { key ->
      //println "\nDatasource name: $key"

      //importNodes[key].each { entry ->
    println "flat: " + flatImportNodes
      flatImportNodes.each { entry ->
        if( entry.node.name() == "data-import" ) {
          String repoPath = entry.node.'repository-path'.text()
          String filePath = entry.node.'import-file-path'.text() // Relative to the ATG root, presumably.
          String startups = entry.node.@'start-up-module'
          startups = startups?.replaceAll( " ", "" )
          if( !startups ) {
            startups = entry.definingModule.name
            println "WARNING: data-import: start-up-module is empty, using defining module name: '$startups'. Import-file-path='$filePath'. Repository-path='$repoPath'."
          }

          File fromPath = new File( filePath )
          File baseDir = glasir.model.atgRootDir
          File fromFile = new File( baseDir, filePath )
          File toDir = new File( outputImportDirectory, fromPath.parent )
          File toFile = new File( outputImportDirectory, fromPath.toString() )
          if( !fromFile.exists() ) {
            logger.error "data-import: file not found: '$fromFile'"
          }
          logger.debug "data-import: copy from '$fromFile' to '$toFile'."
          if( !toDir.exists() ) {
            def mkdirs = toDir.mkdirs()
            if( !mkdirs ) {
              logger.error "data-import: mkdirs() failed for path '$toDir'."
              throw new Exception("FAIL: mkdirs $toDir")
            }
          }
          if( toFile.exists() ) {
            def rmFile = toFile.delete()
            if( !rmFile ) {
              logger.error "data-import: delete failed for file '$toFile'."
            }
          }

          // Copy
          // Todo: Copy file in a way that can not cause out-of-memory exceptions in the JVM.
          toFile << fromFile.bytes

          // Add the command to the import list.
          String command = "DataImport: Repository:$repoPath Path:$filePath Module:${startups}"
          if( !importCommands.contains( command ) ) {
            importCommands.add command
          }

        } else if( entry.node.name() == "repository-loader" ) {

          def repoloader = entry.node
          List<String> files = new ArrayList<String>()
          repoloader.files.each { repoFile ->
            String srcModuleName = repoFile.'src-module'.text()
            def srcModule = glasir.module( srcModuleName )
            String filePattern = repoFile.'file-pattern'.text()
  //          println "src-module : $srcModuleName"
  //          println "resolved to: $srcModule"
  //          println "with config path: ${srcModule.manifest.atgConfigPath}"

            // Create <outputDirectory>/import/$moduleName/
            File moduleToDir = new File( outputImportDirectory, srcModuleName )
            File fileToDir = null
            File fromFile = null

            if (repoFile.'config-path') {
              String configPath = repoFile.'config-path'.text()

              // Create <outputDirectory>/import/$moduleName/$configPath/
              //fileToDir = new File( moduleToDir, configPath )

              // Get the file (it can be in a jar file)
              File config = new File( srcModule.dir as File, srcModule.manifest.atgConfigPath as String )
              if( config.isFile() ) {
                if( config.name.toLowerCase().endsWith(".jar") ) {
                  //println " copy from zipTree '$config', include '$configPath/$filePattern', into '$moduleToDir'"
                  project.copy {
                    from project.zipTree( config )
                    include( "$configPath/$filePattern" )
                    into moduleToDir
                  }
                } else {
                  println "ERROR: THE CONFIG-PATH FOR MODULE $srcModuleName IS A FILE BUT NOT A JAR."
                }
              } else {
                println " copy from fileTree '$config', include '$configPath/$filePattern', into '$moduleToDir'"
                project.copy {
                  from project.fileTree( config )
                  include( "$configPath/$filePattern" )
                  into moduleToDir
                }
              }

              //println "  ==> Repository loader with config-path $configPath. Module ${module.name}. File ${dbinit}. Schema ${requiredSchemaId}"
              def fs = "${System.properties['file.separator']}"
              files.add("$srcModuleName$fs$configPath$fs$filePattern")
            }
            else if (repoFile.'file-path') {
              def filePath = repoFile.'file-path'.text()
              println "  ==> Repository loader with file-path $filePath. Module ${module.name}. File ${dbinit}. Schema ${requiredSchemaId}"
              files.add("${repoFile.'file-path'.text()}${System.properties['file.separator']}${repoFile.'file-pattern'.text()}")
            }

          }

          // Add the repoloader definition in the glasir.db format
          String command = "RepoLoader:files=\"${files.join(" & ")}\" fileMapping=\"${repoloader.'file-mapping'.text()}\" folderMapping=\"${repoloader.'folder-mapping'.text()}\""
          importCommands.add command
        }

      }
    //}

    File commandFile = new File( outputImportDirectory, "imports.txt" )
    logger.info "Creating import definition file: '$commandFile'."
    commandFile.text = importCommands.join( System.properties["line.separator"] as String )
    println "---------------------------------------------------------------------"
    println "IMPORT FILE: $commandFile"
    println "---------------------------------------------------------------------"
  }


  public static List<File> findProductXmlFiles( project, List<AtgModule> modules ) {
    List<File> result = new ArrayList<File>()
    modules.findAll { module ->
      project.file("${module.dir}/cim/product.xml").exists()
    }.each { module ->
      def file = new File("${module.dir}/cim/product.xml")
      result.add( file )
    }
    return result
  }

  public static List<groovy.util.Node> loadXmlFromProductFiles( project, List<File> productFiles ) {
    List<groovy.util.Node> productNodes = new ArrayList<groovy.util.Node>()
    productFiles.each { productxml ->
      project.logger.info "Parsing XML from product file \"$productxml\""
      def parser = new groovy.util.XmlParser(false, false)
      // Set these two features to skip dtd verification.
      parser.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      parser.setFeature("http://xml.org/sax/features/namespaces", false)
      def root = parser.parse(productxml) // root should be the "database-initializer" node.

      if (root?.name() != "product" ) {
        println "Root element not recognized. Expected \"product\" Found \"${root.name()}\""
      } else {
        productNodes.add( root )
      }
    }
    return productNodes
  }

  public static List<AtgProduct> scanProductValues( project, ArrayList<Node> productNodes ) {
    List<AtgProduct> result = new ArrayList<AtgProduct>()
    productNodes.each { product ->
      AtgProduct ap = new AtgProduct()
      ap.parse( product )
      result.add( ap )
    }
    return result
  }


  /**
   * Create an entry in the imports list for data import (repository assets).
   * The import will be run by glasir.db during ATG startup after repository components are started.
   * @param selectedAddons The list of selected ATG addons.
   * @param module The module under whose "cim" directory this dbinit.xml file was found.
   * @param dataimport The current "repository-loader" XML node.
   * @param importNodes Import nodes mapped by datasource ID.
   * @param flatImportNodes Flat list of imports.
   * @param datasourceId The ID of the current "datasource" XML node.
   * @param dbinit The file we are reading XML from.
   */
  protected void scanDataImportElement( List<String> selectedAddons, com.iteego.glasir.build.api.AtgModule module, Node dataimport, Map<String, List<ImportAndModule>> importNodes, List<ImportAndModule> flatImportNodes, String datasourceId, File dbinit ) {
    def repoPath = dataimport.'repository-path'.text()
    def filePath = dataimport.'import-file-path'.text() // Relative to the ATG root, presumably.
    def startups = dataimport.@'start-up-module'

    String s = "DataImport: Repository:$repoPath Path:$filePath Module:${startups} (defined in ${module.name})"
    if (dataimport.'requires-addon-id')
      logger.debug "  ---==> Data Import $filePath requires addons '${dataimport.'requires-addon-id'.collect {it.@id}}'"
    if (dataimport.'incompatible-addon-id')
      logger.debug "  ---==> Data Import $filePath is incompatible with addons '${dataimport.'incompatible-addon-id'.collect {it.@id}}'"
    if (dataimport.user)
      logger.debug "  ---==> Data Import $filePath requires user '${dataimport.user.text()}'"
    if (dataimport.workspace)
      logger.debug "  ---==> Data Import $filePath requires workspace '${dataimport.workspace.text()}'"

    def missing = false
    def blocked = false
    if (dataimport.'requires-addon-id' || dataimport.'incompatible-addon-id') {
      missing = dataimport.'requires-addon-id'.any { !selectedAddons.contains(it.@id) }
      blocked = dataimport.'incompatible-addon-id'.any { selectedAddons.contains(it.@id) }
    }

    if(!missing && !blocked) {
      if(!importNodes.containsKey( datasourceId ) ) {
        importNodes[datasourceId] = new ArrayList<ImportAndModule>()
      }

      importNodes[datasourceId].add new ImportAndModule( node:dataimport, definingModule: module )

      def x = flatImportNodes.count { (it.definingModule.dir == module.dir) && (it.node.'import-file-path'.text() == dataimport.'import-file-path'.text()) }
      if( x == 0 ) {
        flatImportNodes.add( new ImportAndModule( node:dataimport, definingModule: module ) )
      } else {
        logger.info "  DataImport: Duplicate found in file '$dbinit'. Node: $dataimport"
      }
      logger.info "  DataImport: module.dir=${module.dir}, node=${dataimport.'import-file-path'.text()}"
    }
  }

  /**
   * Create an entry in the imports list for repository loader import (file assets).
   * The import will be run by glasir.db during ATG startup after repository components are started.
   * @param selectedAddons The list of selected ATG addons.
   * @param module The module under whose "cim" directory this dbinit.xml file was found.
   * @param repoloader The current "repository-loader" XML node.
   * @param importNodes Import nodes mapped by datasource ID.
   * @param flatImportNodes Flat list of imports.
   * @param datasourceId The ID of the current "datasource" XML node.
   * @param dbinit The file we are reading XML from.
   */
  protected void scanRepoLoaderElement( List<String> selectedAddons, com.iteego.glasir.build.api.AtgModule module, Node repoloader, Map<String, List<ImportAndModule>> importNodes, List<ImportAndModule> flatImportNodes, String datasourceId, File dbinit ) {
    def missing = false
    if (repoloader.'requires-addon-id') {
      logger.info( "        ---==> Repository loader requires addons '${repoloader.'requires-addon-id'.collect {it.@id}}'" )
      missing = repoloader.'requires-addon-id'.any { !selectedAddons.contains(it.@id) }
    }//if repository loader requires specific addons
    if( !missing ) {
      if( !importNodes.containsKey(datasourceId) ) {
        importNodes[ datasourceId ] = new ArrayList<ImportAndModule>()
      }
      importNodes[ datasourceId ].add new ImportAndModule( node:repoloader, definingModule: module )

      // todo: check for duplicates before inserting
      flatImportNodes.add( new ImportAndModule( node:repoloader, definingModule: module ) )
    }//if !missing
  }

  /**
   * Create a Liquibase-compatible file to be imported by glasir.db in ATG startup
   * before repositories have been started.
   * @param selectedAddons The list of selected ATG addons.
   * @param namedDatasourceMap Mapping datasource names to JNDI names.
   * @param module The module under whose "cim" directory this dbinit.xml file was found.
   * @param child The current "sql" XML node.
   * @param sqlFiles Map JNDI names to lists of sql imports.
   * @param datasourceId The ID of the current "datasource" XML node.
   * @param dbinit The file we are reading XML from.
   */
  protected void scanSqlElement( List<String> selectedAddons, Map namedDatasourceMap, com.iteego.glasir.build.api.AtgModule module, Node child, Map<String, List<String>> sqlFiles, String datasourceId, File dbinit) {
    child.path.each { sqlPath ->
      def missing = false
      if (sqlPath.'requires-addon-id') {
        logger.debug "  Create SQL: ${sqlPath.create.text()}"
        logger.debug "  Drop SQL  : ${sqlPath.drop.text()}"
        logger.info  "  ---==> Sql path '${sqlPath.create.text()}' requires addons '${sqlPath.'requires-addon-id'.collect {it.@id}}'"
        missing = sqlPath.'requires-addon-id'.any { !selectedAddons.contains(it.@id) }
      }//if repository loader requires specific addons

      if (!missing) {
        def jndiName = namedDatasourceMap[datasourceId]?.jndi?.text()
        if (jndiName) {
          if (!sqlFiles.containsKey(jndiName)) {
            sqlFiles[jndiName] = new ArrayList<String>()
          }
          def combinedPath = new File(module.dir as File, sqlPath.create.text() as String)
          logger.debug "  SQL: $combinedPath"
          if (!sqlFiles[jndiName].contains(combinedPath.absolutePath)) {
            sqlFiles[jndiName].add(combinedPath.absolutePath)
          }
        } else {
          logger.warn("Found use of undefined datasource name: '$datasourceId' in file '$dbinit'.")
        }
      }//if not missing any required addons
    }
  }


  /**
  * Turn some sql into Liquibase change sets that can be run in some other database.
  * @param sourceDbType Use one of the types defined in this class, for example DBTYPE_ORACLE.
  * @param targetDbType Use one of the types defined in this class, for example DBTYPE_H2.
  * @param inputSql The file where the source sql is.
  * @param xmlFileOut This file will be created or overwritten.
  * @param id Changeset ID, should be very unique.
  * @param author Changeset Author, not very important.
  * @param context Changeset Context, not really used when this documentation was written.
  */
  def createChangeSetFromSql(String sourceDbType, String targetDbType, File inputSql, File xmlFileOut, String id, String author, String context) {
    def writer = new StringWriter()
    def indentPrinter = new IndentPrinter(writer, '  ', true)

    def xml = new MarkupBuilder(indentPrinter)
    xml.doubleQuotes = true

    def schemaLocation = ["http://www.liquibase.org/xml/ns/dbchangelog",
        "http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-2.0.xsd",
        "http://www.liquibase.org/xml/ns/dbchangelog-ext",
        "http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-ext.xsd"].join(' ')

    xml.mkp.xmlDeclaration(version: '1.0', encoding: 'UTF-8')

    String sqlData = null
    if( sourceDbType==DBTYPE_ORACLE && targetDbType==DBTYPE_H2 ) {
      sqlData = massageOracleSqlToWorkInH2(inputSql.text)
    } else {
      throw new Exception( "createChangeSetFromSql does not support source type $sourceDbType and target type $targetDbType" )
    }

    xml.databaseChangeLog('xmlns': "http://www.liquibase.org/xml/ns/dbchangelog",
        'xmlns:xsi': "http://www.w3.org/2001/XMLSchema-instance",
        'xmlns:ext': "http://www.liquibase.org/xml/ns/dbchangelog-ext",
        'xsi:schemaLocation': schemaLocation) {

      changeSet(id: id, author: author, context: context) {
        preConditions {
          not {
            tableExists( tableName:'bogus_precondition_table_name' )
          }
        }

        sql {
          mkp.yieldUnescaped "<![CDATA[\n$sqlData\n]]>\n  "
        }

        rollback()
      }
    }

    xmlFileOut.text = writer.toString()
    println "XML change set written to $xmlFileOut"
  }

  /**
  * Remove stored procedures.
  * Rewrite alter table from "alter table X modify (...)" to "alter table X alter ...".
  * Remove alter session statements.
  **/
  String massageOracleSqlToWorkInH2(String oracleSqlText) {
    StringBuilder result = new StringBuilder()

    removeCreateProcedureStatements(oracleSqlText).tokenize(';').each { statement ->
      def cleanLines = statement.readLines().collect { it.trim() }.findAll { it }
      //statement with only newlines, skip it
      if (!cleanLines) return
      result << "\n\n"

      def cleanStatement = cleanLines.findAll { !it.startsWith('--')}.join('\n')
      //println "STATEMENT: '$statement'"

      def m = (cleanStatement =~ alterTablePattern)
      if (m) {
        result << "alter table ${m[0][1]} alter column ${m[0][2]};"
        return
      }

      if (cleanStatement =~ alterSessionPattern) return

      result << "${statement.trim()};"
    }

    result
  }

  String removeCreateProcedureStatements(String sqlData) {
    boolean skipLines = false
    StringBuffer tmp = new StringBuffer()
    sqlData.readLines().each { line ->
      if (line =~ createProcedurePattern) {
        //println "Match found on '$line'"
        skipLines = true
        return
      }

      if (line == '/') {
        skipLines = false
        return
      }

      if (skipLines) return

      tmp << line << '\n'
    }

    tmp
  }

  public static List<String> calculateRequiredDatasources( project, List<Node> productNodes, String mainModuleName, List<String> addonNames ) {
    def products = CreateChangesets.scanProductValues( project, productNodes )

    List<Modification> instanceModifications = []
    List<Modification> instanceTypeModifications = []

    products.each { product ->
      // check addons
      product.productAddonGroups.each { group ->
        group.addons.each { addon ->
          addon.each {
            project.logger.debug( "product ${product.id}, group: ${group.id}, addon: ${addon.id}" )
            if( addonNames.contains( addon.id ) ) {
              addon.instanceModifications.each { instanceModifications.addAll( it.modifications ) }
              addon.instanceTypeModifications.each { instanceTypeModifications.addAll( it.modifications ) }
            }
          }
        }
      }

      // check combinations
      product.productAddonCombos.each { combo ->
        if( !combo.addonCombinations.any { !addonNames.contains(it) } ) {
          combo.instanceTypeModifications.each { instanceTypeModifications.addAll( it.modifications ) }
        }
      }
    }

    List<String> result = []

    println "Server Instance Modifications (${instanceModifications.size()})"
    def dsMods = instanceModifications.findAll { it instanceof AddNamedDatasource || it instanceof RemoveNamedDatasource }
    dsMods.each { dsmod ->
      //println "  $dsmod"
      if( dsmod instanceof AddNamedDatasource && !result.contains(dsmod.addedDatasourceId) ) {
        result.add( dsmod.addedDatasourceId )
      }
      if( dsmod instanceof RemoveNamedDatasource && result.contains(dsmod.removedDatasourceId) ) {
        result.remove( dsmod.removedDatasourceId )
      }
    }

    println "Server Instance Type Modifications (${instanceTypeModifications.size()})"
    dsMods = instanceTypeModifications.findAll { it instanceof AddNamedDatasource || it instanceof RemoveNamedDatasource }
    dsMods.each { dsmod ->
      //println "  $dsmod"
      if( dsmod instanceof AddNamedDatasource && !result.contains(dsmod.addedDatasourceId) ) {
        result.add( dsmod.addedDatasourceId )
      }
      if( dsmod instanceof RemoveNamedDatasource && result.contains(dsmod.removedDatasourceId) ) {
        result.remove( dsmod.removedDatasourceId )
      }
    }

    return result
  }
}


public class ListAvailableProducts extends DefaultTask {
  def glasir = project.glasir
  boolean full = false

  @TaskAction
  public void run() {
    def files = CreateChangesets.findProductXmlFiles( glasir.project, glasir.modules )
    def nodes = CreateChangesets.loadXmlFromProductFiles( glasir.project, files )
    def products = CreateChangesets.scanProductValues( glasir.project, nodes )

    if( full ) {
      products.each { product ->
        StringWriter w = new StringWriter()
        product.print( w, 0 )
        println "************************************************"
        println w.toString()
        println ""
      }
    } else {
      println "Products:\n${products.collect { p -> "${p.id} (${p.productAddonGroups.collect {it.id}.join(', ')})"}.join('\n') }"
    }
  }
}


public class ListAvailableAddons extends DefaultTask {
  def glasir = project.glasir

  @TaskAction
  public void run() {
    def files = CreateChangesets.findProductXmlFiles( glasir.project, glasir.modules )
    def nodes = CreateChangesets.loadXmlFromProductFiles( glasir.project, files )
    def products = CreateChangesets.scanProductValues( glasir.project, nodes )

    println "product, addon-groups, addons"
    println ""
    products.each { product ->
      println "${product.id}"
      product.productAddonGroups.each { addonGroup ->
        println "  ${addonGroup.id}"
        addonGroup.addons.each { addon ->
          println "    ${addon.id}"
        }
      }
      println ""
    }
  }
}


public class ListDatasources extends DefaultTask {
  def mainModuleName = "env.Main.store.dev.oracle"

  def selectedProducts = [
      //"commerce"
      //"endeca",
      "platform",
      //"siteadmin",
      "store"
  ]

  def selectedAddons = [
      "dcs-csr",
      "international",
      "merch",
      "previewOnManagement",
      "prodLock",
      "queryConsoleOnManagement",
      "storefront-full-setup",
      //"storefront_no_publishing",
      "switchingdatasource"
    ]

  def glasir = project.glasir
  def fullModuleList = glasir.moduleMap[mainModuleName].fullBuildOrder

  /**
   * Use empty 'selectedProducts' list to include all products.
   */
  @TaskAction
  public void run() {
    logger.info "List Data Sources."
    logger.info "  main module:     $mainModuleName"
    logger.info "  selected addons: $selectedAddons"
    def files = CreateChangesets.findProductXmlFiles( glasir.project, glasir.modules )
    def nodes = CreateChangesets.loadXmlFromProductFiles( glasir.project, files )
    def products = CreateChangesets.scanProductValues( glasir.project, nodes )

    Map<String,List<ServerInstanceModification>> instanceModifications = [:]
    Map<String,List<InstanceTypeModification>> instanceTypeModifications = [:]


    products.each { product ->      
      if( !selectedProducts || selectedProducts.contains( product.id ) ) {
        println "Include product: ${product.id}"

        // check addons
        product.productAddonGroups.each { addonGroup ->
          addonGroup.addons.each { addon ->
            addon.each {
              logger.info( "product ${product.id}, addonGroup: ${addonGroup.id}, addon: ${addon.id}" )
              if( !selectedAddons || selectedAddons.contains( addon.id ) ) {
                addon.instanceModifications.each { im ->
                  def key = im.modifiedId
                  if( !instanceModifications.containsKey(key) ) instanceModifications[key] = new ArrayList<ServerInstanceModification>()
                  instanceModifications[key].add( im )
                }

                addon.instanceTypeModifications.each { itm ->
                  def key = itm.modifiedId
                  if( !instanceTypeModifications.containsKey(key) ) instanceTypeModifications[key] = new ArrayList<InstanceTypeModification>()
                  instanceTypeModifications[key].add( itm )
                }
              }
            }
          }
        }

        // check combinations
        product.productAddonCombos.each { combo ->
          if( selectedAddons && (combo.addonCombinations.any{ !selectedAddons.contains(it) }) ) {
            // Skip it if any of the addons in the combination is not in the list of selected addons.
            logger.debug "Skip addon combination '${combo.id}', missing some addons."
          } else {
            logger.debug "Using addon combination '${combo.id}'."
            combo.instanceTypeModifications.each { itm ->
              def key = itm.modifiedId
              if( !instanceTypeModifications.containsKey(key) ) instanceTypeModifications[key] = new ArrayList<InstanceTypeModification>()
              instanceTypeModifications[key].add( itm )
            }
            //instanceTypeModifications.addAll combo.instanceTypeModifications
            //combo.instanceTypeModifications.each { instanceTypeModifications.addAll( it.modifications ) }
          }
        }
      } else {
        println "Skip product: ${product.id}"
      }
    }

    println ""
    println "Server Instance Modifications (${instanceModifications.size()}) -----------------------"
    instanceTypeModifications.each { key, list ->
      println " Instance: '$key'"
      def shownMod = []
      list.each { mod ->
        shownMod.addAll( mod.modifications.findAll { m -> m instanceof AddNamedDatasource || m instanceof RemoveNamedDatasource } )
      }
      if( shownMod ) {
        println "  Data Source Modifications: "
        println "  $shownMod"
      }
    }

    println ""
    println "Server Instance Type Modifications (${instanceTypeModifications.size()}) -----------------------"
    instanceTypeModifications.each { key, list ->
      println " Instance Type: '$key'"
      def shownMod = []
      list.each { mod ->
        shownMod.addAll( mod.modifications.findAll { m -> m instanceof AddNamedDatasource || m instanceof RemoveNamedDatasource } )
      }
      if( shownMod ) {
        println "  Data Source Modifications: "
        println "  $shownMod"
      }
    }


  }
}



interface AtgXmlInterface {
  AtgXmlInterface parse( groovy.util.Node node )
  void print( java.io.Writer writer, int indentationLevel )
}

class AtgXmlBase implements AtgXmlInterface {
  public AtgXmlInterface parse( Node node ) {
    throw new Exception( "Please implement parse for class ${this.class.name}" )
  }

  @Override public void print( java.io.Writer writer, int indentationLevel ) {
    writer.write( " " * indentationLevel + this.class.simpleName + "\n" )
  }

  @Override public String toString() {
    StringWriter w = new StringWriter()
    print( w, 2 )
    return w.toString()
  }
}

class AtgNamedDatasource extends AtgXmlBase {
  String id
  String title
  String jndi

  @Override
  public AtgXmlInterface parse( Node node ) {
    this.id = node.@id
    this.title = node.title?.text()
    this.jndi = node.jndi?.text()
    return this
  }
}

class Modification extends AtgXmlBase {}

class ModificationList extends AtgXmlBase {
  /**
   * "Server Instance Id" or "Service Instance Type Id"
   */
  String modifiedId

  List< Modification > modifications = new ArrayList< Modification >()

  @Override public AtgXmlInterface parse( Node node ) {
    modifiedId = node.@id
    node.children().each { Node child ->
      switch( child.name().toLowerCase() ) {
        case "add-server-instance":
          modifications.add new AddServerInstance( addedServerInstanceId: child.@id )
          break
        case "remove-server-instance":
          modifications.add new RemoveServerInstance( removedServerInstanceId: child.@id )
          break
        case "add-named-datasource":
          modifications.add new AddNamedDatasource( addedDatasourceId: child.@id )
          break
        case "remove-named-datasource":
          modifications.add new RemoveNamedDatasource( removedDatasourceId: child.@id )
          break
        case "append-module":
          modifications.add new AppendModuleName( appendedModuleName: child.@name )
          break
        case "prepend-module":
          modifications.add new PrependModuleName( prependedModuleName: child.@name )
          break
        case "remove-module":
          modifications.add new RemoveModuleName( removedModuleName: child.@name )
          break
        case "add-appassembler-option":
          // ignore
          break
        default :
          throw new Exception( "Unknown node: '${child.name()}' parsing node '$node'" )
      }
    }
    return this
  }

}

class InstanceTypeModification extends ModificationList {
  @Override public void print( java.io.Writer writer, int indentationLevel ) {
    def i = " " * indentationLevel
    writer.write( "${i}Modifications (instance type \"$modifiedId\"):\n" )
    modifications.each { mod ->
      mod.print( writer, indentationLevel + 4 )
    }
  }
}

class ServerInstanceModification extends ModificationList {
  @Override public void print( java.io.Writer writer, int indentationLevel ) {
    def i = " " * indentationLevel
    writer.write( "${i}Modifications (instance \"$modifiedId\"):\n" )
    modifications.each { mod ->
      mod.print( writer, indentationLevel + 4 )
    }
  }

}


class AddServerInstance extends Modification {
  String addedServerInstanceId

  @Override public void print( java.io.Writer writer, int indentationLevel ) {
    def i = " " * indentationLevel
    writer.write( "${i}Add Server Instance: $addedServerInstanceId\n" )
  }
}
class RemoveServerInstance extends Modification {
  String removedServerInstanceId

  @Override public void print( java.io.Writer writer, int indentationLevel ) {
    def i = " " * indentationLevel
    writer.write( "${i}Remove Server Instance: $removedServerInstanceId\n" )
  }
}

class AppendModuleName extends Modification {
  String appendedModuleName

  @Override public void print( java.io.Writer writer, int indentationLevel ) {
    def i = " " * indentationLevel
    writer.write( "${i}Append Module Name: $appendedModuleName\n" )
  }
}
class RemoveModuleName extends Modification {
  String removedModuleName

  @Override public void print( java.io.Writer writer, int indentationLevel ) {
    def i = " " * indentationLevel
    writer.write( "${i}Remove Module Name: $removedModuleName\n" )
  }
}
class PrependModuleName extends Modification {
  String prependedModuleName

  @Override public void print( java.io.Writer writer, int indentationLevel ) {
    def i = " " * indentationLevel
    writer.write( "${i}Prepend Module Name: $prependedModuleName\n" )
  }
}

class AddNamedDatasource extends Modification {
  String addedDatasourceId

  @Override public void print( java.io.Writer writer, int indentationLevel ) {
    def i = " " * indentationLevel
    writer.write( "${i}Add Named Data Source: $addedDatasourceId\n" )
  }
}
class RemoveNamedDatasource extends Modification {
  String removedDatasourceId

  @Override public void print( java.io.Writer writer, int indentationLevel ) {
    def i = " " * indentationLevel
    writer.write( "${i}Remove Named Data Source: $removedDatasourceId\n" )
  }
}


class AtgServerInstanceType extends AtgXmlBase {
  String id
  String detail
  String title
  String extendsInstanceType
  List<Modification> modifications = new ArrayList<Modification>()
  List<String> serverInstances = new ArrayList<String>()

  @Override public AtgXmlInterface parse( Node node ) {
    this.id = node.@id
    node.children().each { Node child ->
      switch( child.name().toLowerCase() ) {
        case "detail":
          detail = child.text()
          break
        case "add-server-instance":
          serverInstances.add( child.@id )
          break
        case "append-module":
          modifications.add( new AppendModuleName( appendedModuleName: child.@'name' as String ) )
          break
        case "add-named-datasource":
          modifications.add( new AddNamedDatasource( addedDatasourceId: child.@id ) )
          break
        case "config-directory":
          // todo: handle this
          break
        case "optional-config-directory":
          // todo: handle or ignore
          break
        case "title":
          title = child.text()
          break
        default :
          throw new Exception( "Unknown node: ${child.name()}" )
      }
    }
    return this
  }
}

class AtgServerInstance extends AtgXmlBase {
  String id
  String type
  String title
  String detail
  String earFileName
  String serverInstanceName
  List<Modification> modifications = new ArrayList<Modification>()

  @Override public AtgXmlInterface parse( Node node ) {
    this.id = node.@id
    this.type = node.@type
    node.children().each { Node child ->
      switch( child.name().toLowerCase() ) {
        case "config-directory":
          // ignored
          break
        case "optional-config-directory":
          // ignored
          break
        case "detail":
          detail = child.text()
          break
        case "ear-file-name":
          earFileName = child.text()
          break
        case "server-instance-name":
          serverInstanceName = child.text()
          break
        case "title":
          title = child.text()
          break
        case "append-module":
          modifications.add( new AppendModuleName( appendedModuleName: child.@'name' as String ) )
          break
        case "add-named-datasource":
          modifications.add( new AddNamedDatasource( addedDatasourceId: child.@id ) )
          break
        case "post-deployment-option":
          //ignored
          break
        default :
          throw new Exception( "Unknown node: ${child.name()}" )
      }
    }
    return this
  }
}

class AtgProduct extends AtgXmlBase {
  String id
  String title
  String detail
  List<String> incompatibleAddons = new ArrayList<String>()
  List<String> requiredAddons = new ArrayList<String>()
  List<String> requiredProducts = new ArrayList<String>()
  List<String> incompatibleProducts = new ArrayList<String>()
  List<AddNamedDatasource> namedDatasources = new ArrayList<AddNamedDatasource>()
  List<AtgProductAddonGroup> productAddonGroups = new ArrayList<AtgProductAddonGroup>()
  List<AtgProductAddonCombo> productAddonCombos = new ArrayList<AtgProductAddonCombo>()
  List<AtgServerInstance> serverInstances = new ArrayList<AtgServerInstance>()
  List<AtgServerInstanceType> serverInstanceTypes = new ArrayList<AtgServerInstanceType>()

  @Override public void print( java.io.Writer writer, int indentationLevel ) {
    def i = " " * indentationLevel
    writer.write( "${i}PRODUCT: \"$id\"\n" )
    if( title )
      writer.write( "${i}  Title                : $title\n" )
    if( detail )
      writer.write( "${i}  Detail               : $detail\n" )
    if( requiredProducts )
      writer.write( "${i}  Required products    : $requiredProducts\n" )
    if( incompatibleProducts)
      writer.write( "${i}  Incompatible products: $incompatibleProducts\n" )
    if( requiredAddons )
      writer.write( "${i}  Required addons      : $requiredAddons\n" )
    if( incompatibleAddons )
      writer.write( "${i}  Incompatible addons  : $incompatibleAddons\n" )
    if( namedDatasources )
      writer.write( "${i}  Data Sources         : ${namedDatasources.collect {it.addedDatasourceId}.join(", ")}\n" )
    if( productAddonGroups ) {
      writer.write( "${i}  Addon Groups\n" )
      productAddonGroups.each { it.print( writer, indentationLevel+4 ) }
    }
    if( productAddonCombos ) {
      writer.write( "${i}  Addon Combos\n" )
      productAddonCombos.each { it.print( writer, indentationLevel+4 ) }
    }
  }


  @Override public AtgXmlInterface parse( Node product ) {
    this.id = product.@id
    product.children().each { Node child ->
      switch( child.name().toLowerCase() ) {
        case "detail":
          detail = child.text()
          break
        case "named-datasource":
          namedDatasources.add( new AddNamedDatasource( addedDatasourceId: child.@id ) )
          break
        case "product-addon-combo":
          productAddonCombos.add( new AtgProductAddonCombo().parse( child ) as AtgProductAddonCombo )
          break
        case "product-addon-group":
          productAddonGroups.add( new AtgProductAddonGroup().parse( child ) as AtgProductAddonGroup )
          break
        case "product-id-required":
          requiredProducts.add( child.@id )
          break
        case "incompatible-product-id":
          incompatibleProducts.add( child.@id )
          break
        case "incompatible-addon-id":
          incompatibleAddons.add( child.@id )
          break
        case "requires-addon-id":
          requiredAddons.add( child.@id )
          break
        case "server-instance":
          serverInstances.add( new AtgServerInstance().parse( child ) as AtgServerInstance )
          break
        case "server-instance-type":
          serverInstanceTypes.add( new AtgServerInstanceType().parse( child ) as AtgServerInstanceType )
          break
        case "title":
          title = child.text()
          break
        default :
          throw new Exception( "Unknown node: ${child.name()}" )
      }
    }
    return this
  }
}

class AtgProductAddon extends AtgXmlBase {
  String id
  String title
  String detail
  List<ModificationList> instanceTypeModifications = new ArrayList<ModificationList>()
  List<ModificationList> instanceModifications = new ArrayList<ModificationList>()

  @Override public void print( java.io.Writer writer, int indentationLevel ) {
    def i = " " * indentationLevel
    writer.write( "${i}Product Addon: $id\n" )
    if( title ) writer.write( "${i}  Title  : $title\n" )
    if( detail ) writer.write( "${i}  Detail : $detail\n" )
    if( instanceTypeModifications ) {
      writer.write( "${i}  Instance Type Modifications : \n" )
      instanceTypeModifications.each { it.print( writer, indentationLevel+4 ) }
    }
  }


  @Override public AtgXmlInterface parse( Node node ) {
    this.id = node.@id
    node.children().each { Node child ->
      switch( child.name().toLowerCase() ) {
        case "detail":
          detail = child.text()
          break
        case "modify-server-instance-type":
          instanceTypeModifications.add( new InstanceTypeModification().parse( child ) as ModificationList )
          break
        case "modify-server-instance":
          instanceModifications.add( new ServerInstanceModification().parse( child ) as ModificationList )
          break
        case "title":
          title = child.text()
          break
        default :
          throw new Exception( "Unknown node: ${child.name()}" )
      }
    }
    return this
  }
}

class AtgProductAddonGroup extends AtgXmlBase {
  String id
  String title
  List<String> requiredAddonIds = new ArrayList<String>()
  List<String> requiredProductIds = new ArrayList<String>()
  List<AtgProductAddon> addons = new ArrayList<AtgProductAddon>()

  @Override public void print( java.io.Writer writer, int indentationLevel ) {
    def i = " " * indentationLevel
    writer.write( "${i}Product Addon Group: \"$id\"\n" )
    if( title )
      writer.write( "${i}  Title             : $title\n" )
    if(requiredProductIds)
      writer.write( "${i}  Required products : $requiredProductIds\n" )
    if(requiredAddonIds)
      writer.write( "${i}  Required addons   : $requiredAddonIds\n" )
    if(addons) {
      writer.write( "${i}  Product Addons\n" )
      addons.each { it.print( writer, indentationLevel+4 ) }
    }
  }

  @Override public AtgXmlInterface parse( Node node ) {
    this.id = node.@id
    node.children().each { Node child ->
      switch( child.name().toLowerCase() ) {
        case "product-addon":
          addons.add( new AtgProductAddon().parse( child ) as AtgProductAddon )
          break
        case "product-id-required":
          requiredProductIds.add( child.@id )
          break
        case "requires-product-addon":
          requiredAddonIds.add( child.@id )
          break
        case "title":
          title = child.text()
          break
        default :
          throw new Exception( "Unknown node: ${child.name()}" )
      }
    }
    return this
  }

}

class AtgProductAddonCombo extends AtgXmlBase {
  String id
  List<String> addonCombinations = new ArrayList<String>()
  List<ModificationList> instanceTypeModifications = new ArrayList<ModificationList>()

  @Override public AtgXmlInterface parse( Node node ) {
    this.id = node.@id
    node.children().each { Node child ->
      switch( child.name().toLowerCase() ) {
        case "modify-server-instance-type":
          instanceTypeModifications.add( new InstanceTypeModification().parse( child ) as ModificationList )
          break
        case "combo-product-addon":
          addonCombinations.add( child.@id )
          break
        default :
          throw new Exception( "Unknown node: ${child.name()}" )
      }
    }
    return this
  }

  @Override public void print( java.io.Writer writer, int indentationLevel ) {
    def i = " " * indentationLevel
    writer.write( "${i}Product Addon Combo: \"$id\"\n" )
    if( addonCombinations )
      writer.write( "${i}  Combination : ${addonCombinations}\n" )
    if( instanceTypeModifications ) {
      writer.write( "${i}  Instance Type Modifications :\n" )
      instanceTypeModifications.each { it.print( writer, indentationLevel+4 ) }
    }
  }

}






















