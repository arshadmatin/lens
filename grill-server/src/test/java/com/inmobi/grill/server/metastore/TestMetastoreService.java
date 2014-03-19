package com.inmobi.grill.server.metastore;

import java.util.*;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.*;
import javax.ws.rs.core.*;
import javax.xml.bind.JAXBElement;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

import com.inmobi.grill.api.APIResult;
import com.inmobi.grill.api.GrillSessionHandle;
import com.inmobi.grill.api.StringList;
import com.inmobi.grill.api.APIResult.Status;
import com.inmobi.grill.api.metastore.*;
import com.inmobi.grill.server.GrillJerseyTest;
import com.inmobi.grill.server.GrillServices;
import com.inmobi.grill.server.metastore.CubeMetastoreServiceImpl;
import com.inmobi.grill.server.metastore.JAXBUtils;
import com.inmobi.grill.server.metastore.MetastoreApp;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.ql.cube.metadata.Cube;
import org.apache.hadoop.hive.ql.cube.metadata.CubeDimensionTable;
import org.apache.hadoop.hive.ql.cube.metadata.CubeFactTable;
import org.apache.hadoop.hive.ql.cube.metadata.HDFSStorage;
import org.apache.hadoop.hive.ql.cube.metadata.UpdatePeriod;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;

import static org.testng.Assert.*;

import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

public class TestMetastoreService extends GrillJerseyTest {
  public static final Logger LOG = LogManager.getLogger(TestMetastoreService.class);
  private ObjectFactory cubeObjectFactory;
  protected String mediaType = MediaType.APPLICATION_XML;
  protected MediaType medType = MediaType.APPLICATION_XML_TYPE;
  protected String dbPFX = "TestMetastoreService_";
  CubeMetastoreServiceImpl metastoreService;
  GrillSessionHandle grillSessionId;

  @BeforeTest
  public void setUp() throws Exception {
    super.setUp();
    BasicConfigurator.configure();
    cubeObjectFactory = new ObjectFactory();
    metastoreService = (CubeMetastoreServiceImpl)GrillServices.get().getService("metastore");
    grillSessionId = metastoreService.openSession("foo", "bar", new HashMap<String, String>());

  }

  @AfterTest
  public void tearDown() throws Exception {
    metastoreService.closeSession(grillSessionId);
    super.tearDown();
  }

  protected int getTestPort() {
    return 8082;
  }

  @Override
  protected Application configure() {
    return new MetastoreApp();
  }

  @Override
  protected void configureClient(ClientConfig config) {
    config.register(MultiPartFeature.class);
  }

  @Test
  public void testSetDatabase() throws Exception {
    WebTarget dbTarget = target().path("metastore").path("databases/current");
    String dbName = "test_set_db";
    try {
      dbTarget.queryParam("sessionid", grillSessionId).request(mediaType).put(Entity.xml(dbName), APIResult.class);
      fail("Should get 404");
    } catch (NotFoundException e) {
      // expected
    }

    // create
    APIResult result = target().path("metastore").path("databases")
        .queryParam("sessionid", grillSessionId).request(mediaType).post(Entity.xml(dbName), APIResult.class);
    assertNotNull(result);
    assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

    // set
    result = dbTarget.queryParam("sessionid", grillSessionId).request(mediaType).put(Entity.xml(dbName), APIResult.class);
    assertNotNull(result);
    assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

    String current = dbTarget.queryParam("sessionid", grillSessionId).request(mediaType).get(String.class);
    assertEquals(current, dbName);
  }

  @Test
  public void testCreateDatabase() throws Exception {
    final String newDb = dbPFX + "new_db";
    WebTarget dbTarget = target().path("metastore").path("databases");

    APIResult result = dbTarget.queryParam("sessionid", grillSessionId).request(mediaType).post(Entity.xml(newDb), APIResult.class);
    assertNotNull(result);
    assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

    // Create again
    result = dbTarget.queryParam("sessionid", grillSessionId).queryParam("ignoreifexist", false).request(mediaType).post(Entity.xml(newDb), APIResult.class);
    assertEquals(result.getStatus(), APIResult.Status.FAILED);
    LOG.info(">> Result message " + result.getMessage());

    // Drop
    dbTarget.path(newDb).queryParam("sessionid", grillSessionId).request().delete();
  }

  @Test
  public void testDropDatabase() throws Exception {
    final String dbName = dbPFX + "del_db";
    final WebTarget dbTarget = target().path("metastore").path("databases");
    // First create the database
    APIResult create = dbTarget.queryParam("sessionid", grillSessionId).request(mediaType).post(Entity.xml(dbName), APIResult.class);
    assertEquals(create.getStatus(), APIResult.Status.SUCCEEDED);

    // Now drop it
    APIResult drop = dbTarget.path(dbName)
        .queryParam("cascade", "true")
        .queryParam("sessionid", grillSessionId).request(mediaType).delete(APIResult.class);
    assertEquals(drop.getStatus(), APIResult.Status.SUCCEEDED);
  }

  @Test
  public void testGetAllDatabases() throws Exception {
    final String[] dbsToCreate = {"db_1", "db_2", "db_3"};
    final WebTarget dbTarget = target().path("metastore").path("databases");

    for (String name : dbsToCreate) {
      dbTarget.queryParam("sessionid", grillSessionId).request(mediaType).post(Entity.xml(name));
    }


    StringList allDbs = target().path("metastore").path("databases")
        .queryParam("sessionid", grillSessionId).request(mediaType)
        .get(StringList.class);
    System.out.println("ALL DBs:" + allDbs.getElements());
    assertEquals(allDbs.getElements().size(), 4);

    List<String> expected = new ArrayList<String>(Arrays.asList(dbsToCreate));
    // Default is always there
    expected.add("default");

    assertEquals(allDbs.getElements(), expected);

    for (String name : dbsToCreate) {
      dbTarget.path(name).queryParam("cascade", "true").queryParam("sessionid", grillSessionId).request().delete();
    }
  }

  private void createDatabase(String dbName) throws Exception {
    WebTarget dbTarget = target().path("metastore").path("databases");

    APIResult result = dbTarget.queryParam("sessionid", grillSessionId).request(mediaType).post(Entity.xml(dbName), APIResult.class);
    assertNotNull(result);
    assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);
  }

  private void createStorage(String storageName) throws Exception {
    WebTarget target = target().path("metastore").path("storages");

    XStorage xs = new XStorage();
    xs.setName(storageName);
    xs.setClassname(HDFSStorage.class.getCanonicalName());
    XProperties props = cubeObjectFactory.createXProperties();
    XProperty prop = cubeObjectFactory.createXProperty();
    prop.setName("prop1.name");
    prop.setValue("prop1.value");
    props.getProperties().add(prop);
    xs.setProperties(props);

    APIResult result = target.queryParam("sessionid", grillSessionId).request(mediaType).post(Entity.xml(cubeObjectFactory.createXStorage(xs)), APIResult.class);
    assertNotNull(result);
    assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);
  }

  private void dropStorage(String storageName) throws Exception {
    WebTarget target = target().path("metastore").path("storages").path(storageName);

    APIResult result = target
        .queryParam("sessionid", grillSessionId).request(mediaType).delete(APIResult.class);
    assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);
  }

  private void dropDatabase(String dbName) throws Exception {
    WebTarget dbTarget = target().path("metastore").path("databases").path(dbName);

    APIResult result = dbTarget.queryParam("cascade", "true")
        .queryParam("sessionid", grillSessionId).request(mediaType).delete(APIResult.class);
    assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);
  }

  private void setCurrentDatabase(String dbName) throws Exception {
    WebTarget dbTarget = target().path("metastore").path("databases/current");
    APIResult result = dbTarget.queryParam("sessionid", grillSessionId).request(mediaType).put(Entity.xml(dbName), APIResult.class);
    assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);
  }

  private String getCurrentDatabase() throws Exception {
    WebTarget dbTarget = target().path("metastore").path("databases/current");
    Invocation.Builder builder = dbTarget.queryParam("sessionid", grillSessionId).request(mediaType);
    String response = builder.get(String.class);
    return response;
  }

  private XCube createTestCube(String cubeName) throws Exception {
    GregorianCalendar c = new GregorianCalendar();
    c.setTime(new Date());
    final XMLGregorianCalendar startDate = DatatypeFactory.newInstance().newXMLGregorianCalendar(c);
    c.add(GregorianCalendar.DAY_OF_MONTH, 7);
    final XMLGregorianCalendar endDate = DatatypeFactory.newInstance().newXMLGregorianCalendar(c);


    XCube cube = cubeObjectFactory.createXCube();
    cube.setName(cubeName);
    cube.setWeight(100.0);
    XDimensions xdims = cubeObjectFactory.createXDimensions();

    XDimension xd1 = cubeObjectFactory.createXDimension();
    xd1.setName("dim1");
    xd1.setType("string");
    xd1.setStartTime(startDate);
    // Don't set endtime on this dim to validate null handling on server side
    xd1.setCost(10.0);

    XDimension xd2 = cubeObjectFactory.createXDimension();
    xd2.setName("dim2");
    xd2.setType("int");
    // Don't set start time on this dim to validate null handling on server side
    xd2.setEndTime(endDate);
    xd2.setCost(5.0);

    xdims.getDimensions().add(xd1);
    xdims.getDimensions().add(xd2);
    cube.setDimensions(xdims);


    XMeasures measures = cubeObjectFactory.createXMeasures();

    XMeasure xm1 = new XMeasure();
    xm1.setName("msr1");
    xm1.setType("double");
    xm1.setCost(10.0);
    // Don't set start time and end time to validate null handling on server side.
    //xm1.setStarttime(startDate);
    //xm1.setEndtime(endDate);
    xm1.setDefaultAggr("sum");

    XMeasure xm2 = new XMeasure();
    xm2.setName("msr2");
    xm2.setType("int");
    xm2.setCost(10.0);
    xm2.setStartTime(startDate);
    xm2.setEndTime(endDate);
    xm2.setDefaultAggr("max");

    measures.getMeasures().add(xm1);
    measures.getMeasures().add(xm2);
    cube.setMeasures(measures);

    XProperties properties = cubeObjectFactory.createXProperties();
    XProperty xp1 = cubeObjectFactory.createXProperty();
    xp1.setName("foo");
    xp1.setValue("bar");
    properties.getProperties().add(xp1);

    cube.setProperties(properties);
    return cube;
  }

  @Test
  public void testCreateCube() throws Exception {
    final String DB = dbPFX + "test_create_cube";
    String prevDb = getCurrentDatabase();
    createDatabase(DB);
    setCurrentDatabase(DB);
    try {
      final XCube cube = createTestCube("testCube1");
      final WebTarget target = target().path("metastore").path("cubes");
      APIResult result = target.queryParam("sessionid", grillSessionId).request(mediaType).post(Entity.xml(cubeObjectFactory.createXCube(cube)), APIResult.class);
      assertNotNull(result);
      assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

      StringList cubes = target().path("metastore/cubes").queryParam("sessionid", grillSessionId).request(mediaType).get(StringList.class);
      boolean foundcube = false;
      for (String c : cubes.getElements()) {
        if (c.equalsIgnoreCase("testCube1")) {
          foundcube = true;
          break;
        }
      }

      assertTrue(foundcube);

    }
    finally {
      dropDatabase(DB);
      setCurrentDatabase(prevDb);
    }
  }

  @Test
  public void testGetCube() throws Exception {
    final String DB = dbPFX + "test_get_cube";
    String prevDb = getCurrentDatabase();
    createDatabase(DB);
    setCurrentDatabase(DB);

    try {
      final XCube cube = createTestCube("testGetCube");
      // Create this cube first
      WebTarget target = target().path("metastore").path("cubes");
      JAXBElement<XCube> element = cubeObjectFactory.createXCube(cube);
      APIResult result =
          target.queryParam("sessionid", grillSessionId).request(mediaType).post(Entity.xml(element), APIResult.class);
      assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

      // Now get
      target = target().path("metastore").path("cubes").path("testGetCube");
      JAXBElement<XCube> actualElement =
          target.queryParam("sessionid", grillSessionId).request(mediaType).get(new GenericType<JAXBElement<XCube>>() {});
      XCube actual = actualElement.getValue();
      assertNotNull(actual);

      assertTrue(cube.getName().equalsIgnoreCase(actual.getName()));
      assertNotNull(actual.getMeasures());
      assertEquals(actual.getMeasures().getMeasures().size(), cube.getMeasures().getMeasures().size());
      assertEquals(actual.getDimensions().getDimensions().size(), cube.getDimensions().getDimensions().size());
      assertEquals(actual.getWeight(), 100.0d);

    } finally {
      dropDatabase(DB);
      setCurrentDatabase(prevDb);
    }
  }

  @Test
  public void testDropCube() throws Exception {
    final String DB = dbPFX + "test_drop_cube";
    String prevDb = getCurrentDatabase();
    createDatabase(DB);
    setCurrentDatabase(DB);

    try {
      final XCube cube = createTestCube("test_drop_cube");
      // Create this cube first
      WebTarget target = target().path("metastore").path("cubes");
      JAXBElement<XCube> element = cubeObjectFactory.createXCube(cube);
      APIResult result =
          target.queryParam("sessionid", grillSessionId).request(mediaType).post(Entity.xml(element), APIResult.class);
      assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

      target = target().path("metastore").path("cubes").path("test_drop_cube").queryParam("cascade", "true");
      result = target.queryParam("sessionid", grillSessionId).request(mediaType).delete(APIResult.class);
      assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

      // Now get should give 404
      try {
        target = target().path("metastore").path("cubes").path("test_drop_cube");
        JAXBElement<XCube> got =
            target.queryParam("sessionid", grillSessionId).request(mediaType).get(new GenericType<JAXBElement<XCube>>() {});
        fail("Should have thrown 404");
      } catch (NotFoundException ex) {
        ex.printStackTrace();
      }
    } finally {
      dropDatabase(DB);
      setCurrentDatabase(prevDb);
    }
  }

  @Test
  public void testUpdateCube() throws Exception {
    final String cubeName = "test_update";
    final String DB = dbPFX + "test_update_cube";
    String prevDb = getCurrentDatabase();
    createDatabase(DB);
    setCurrentDatabase(DB);

    try {
      final XCube cube = createTestCube(cubeName);
      // Create this cube first
      WebTarget target = target().path("metastore").path("cubes");
      JAXBElement<XCube> element = cubeObjectFactory.createXCube(cube);
      APIResult result =
          target.queryParam("sessionid", grillSessionId).request(mediaType).post(Entity.xml(element), APIResult.class);
      assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

      // Update something
      cube.setWeight(200.0);
      // Add a measure and dimension
      XMeasure xm2 = new XMeasure();
      xm2.setName("msr3");
      xm2.setType("double");
      xm2.setCost(20.0);
      xm2.setDefaultAggr("sum");
      cube.getMeasures().getMeasures().add(xm2);

      XDimension xd2 = cubeObjectFactory.createXDimension();
      xd2.setName("dim3");
      xd2.setType("string");
      xd2.setCost(55.0);
      cube.getDimensions().getDimensions().add(xd2);

      XProperty xp = new XProperty();
      xp.setName("foo2");
      xp.setValue("bar2");
      cube.getProperties().getProperties().add(xp);


      element = cubeObjectFactory.createXCube(cube);
      result = target.path(cubeName)
          .queryParam("sessionid", grillSessionId).request(mediaType).put(Entity.xml(element), APIResult.class);
      assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

      JAXBElement<XCube> got =
          target.path(cubeName)
          .queryParam("sessionid", grillSessionId).request(mediaType).get(new GenericType<JAXBElement<XCube>>() {});
      XCube actual = got.getValue();
      assertEquals(actual.getWeight(), 200.0);
      assertEquals(actual.getDimensions().getDimensions().size(), 3);
      assertEquals(actual.getMeasures().getMeasures().size(), 3);

      Cube hcube = JAXBUtils.hiveCubeFromXCube(actual);
      assertTrue(hcube.getMeasureByName("msr3").getAggregate().equals("sum"));
      assertEquals(hcube.getMeasureByName("msr3").getCost(), 20.0);
      assertNotNull(hcube.getDimensionByName("dim3"));
      assertEquals(hcube.getProperties().get("foo2"), "bar2");

    } finally {
      dropDatabase(DB);
      setCurrentDatabase(prevDb);
    }
  }

  @Test
  public void testStorage() throws Exception {
    final String DB = dbPFX + "test_storage";
    String prevDb = getCurrentDatabase();
    createDatabase(DB);
    setCurrentDatabase(DB);
    try {
      createStorage("store1");
      final WebTarget target = target().path("metastore").path("storages");

      StringList storages = target.queryParam("sessionid", grillSessionId).request(mediaType).get(StringList.class);
      boolean foundStorage = false;
      for (String c : storages.getElements()) {
        if (c.equalsIgnoreCase("store1")) {
          foundStorage = true;
          break;
        }
      }

      assertTrue(foundStorage);

      XStorage store1 = target.path("store1").queryParam("sessionid", grillSessionId).request(mediaType).get(XStorage.class);
      assertEquals(store1.getName(), "store1");
      assertEquals(store1.getClassname(), HDFSStorage.class.getCanonicalName());
      assertTrue(store1.getProperties().getProperties().size() >= 1);
      assertTrue(JAXBUtils.mapFromXProperties(store1.getProperties()).containsKey("prop1.name"));
      assertEquals(JAXBUtils.mapFromXProperties(store1.getProperties()).get("prop1.name"), "prop1.value");

      // alter storage
      XProperty prop = cubeObjectFactory.createXProperty();
      prop.setName("prop2.name");
      prop.setValue("prop2.value");
      store1.getProperties().getProperties().add(prop);

      APIResult result = target.path("store1")
          .queryParam("sessionid", grillSessionId).queryParam("storage", "store1")
          .request(mediaType).put(Entity.xml(cubeObjectFactory.createXStorage(store1)), APIResult.class);
      assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

      store1 = target.path("store1").queryParam("sessionid", grillSessionId).request(mediaType).get(XStorage.class);
      assertEquals(store1.getName(), "store1");
      assertEquals(store1.getClassname(), HDFSStorage.class.getCanonicalName());
      assertTrue(store1.getProperties().getProperties().size() >= 2);
      assertTrue(JAXBUtils.mapFromXProperties(store1.getProperties()).containsKey("prop1.name"));
      assertEquals(JAXBUtils.mapFromXProperties(store1.getProperties()).get("prop1.name"), "prop1.value");
      assertTrue(JAXBUtils.mapFromXProperties(store1.getProperties()).containsKey("prop2.name"));
      assertEquals(JAXBUtils.mapFromXProperties(store1.getProperties()).get("prop2.name"), "prop2.value");
      
      // drop the storage
      dropStorage("store1");
    }
    finally {
      dropDatabase(DB);
      setCurrentDatabase(prevDb);
    }
  }

  private DimensionTable createDimTable(String table) {
    DimensionTable dt = cubeObjectFactory.createDimensionTable();
    dt.setName(table);
    dt.setWeight(15.0);

    Columns cols = cubeObjectFactory.createColumns();

    Column c1 = cubeObjectFactory.createColumn();
    c1.setName("col1");
    c1.setType("string");
    c1.setComment("Fisrt column");
    cols.getColumns().add(c1);
    Column c2 = cubeObjectFactory.createColumn();
    c2.setName("col2");
    c2.setType("int");
    c2.setComment("Second column");
    cols.getColumns().add(c2);
    dt.setColumns(cols);

    XProperty p1 = cubeObjectFactory.createXProperty();
    p1.setName("foodim");
    p1.setValue("bardim");
    XProperties properties = cubeObjectFactory.createXProperties();
    properties.getProperties().add(p1);
    dt.setProperties(properties);

    DimensionReferences refs = cubeObjectFactory.createDimensionReferences();
    DimensionReference drf = cubeObjectFactory.createDimensionReference();
    drf.setDimensionColumn("col1");
    XTablereference tref1 = cubeObjectFactory.createXTablereference();
    tref1.setDestColumn("dim2id");
    tref1.setDestTable("dim2");
    XTablereference tref2 = cubeObjectFactory.createXTablereference();
    tref2.setDestColumn("dim3id");
    tref2.setDestTable("dim3");
    drf.getTableReferences().add(tref1);
    drf.getTableReferences().add(tref2);
    refs.getDimReferences().add(drf);
    dt.setDimensionsReferences(refs);

    UpdatePeriods periods = cubeObjectFactory.createUpdatePeriods();

    UpdatePeriodElement ue1 = cubeObjectFactory.createUpdatePeriodElement();
    ue1.setStorageName("test");
    ue1.getUpdatePeriods().add("HOURLY");
    periods.getUpdatePeriodElement().add(ue1);
    dt.setStorageDumpPeriods(periods);
    return dt;
  }

  private XStorageTableDesc createStorageTableDesc(String name) {
    XStorageTableDesc xs1 = cubeObjectFactory.createXStorageTableDesc();
    xs1.setCollectionDelimiter(",");
    xs1.setEscapeChar("\\");
    xs1.setFieldDelimiter("");
    xs1.setFieldDelimiter("\t");
    xs1.setLineDelimiter("\n");
    xs1.setMapKeyDelimiter("\r");
    xs1.setTableLocation("/tmp/" + name);
    xs1.setExternal(true);
    Columns partCols = cubeObjectFactory.createColumns();
    Column dt = cubeObjectFactory.createColumn();
    dt.setName("dt");
    dt.setType("string");
    dt.setComment("default partition column");
    partCols.getColumns().add(dt);
    xs1.setPartCols(partCols);
    return xs1;
  }

  private XStorageTableElement createStorageTblElement(String storageName, String table, String... updatePeriod) {
    XStorageTableElement tbl = cubeObjectFactory.createXStorageTableElement();
    tbl.setStorageName(storageName);
    if (updatePeriod != null) {
      for (String p : updatePeriod) {
        tbl.getUpdatePeriods().add(p);
      }
    }
    tbl.setTableDesc(createStorageTableDesc(table));
    return tbl;
  }

  private DimensionTable createDimension(String dimName) throws Exception {
    DimensionTable dt = createDimTable(dimName);
    XStorageTables storageTables = cubeObjectFactory.createXStorageTables();
    storageTables.getStorageTables().add(createStorageTblElement("test", dimName, "HOURLY"));
    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, medType));
    mp.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("dimtable").fileName("dimtable").build(),
        cubeObjectFactory.createDimensionTable(dt), medType));
    mp.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("storagetables").fileName("storagetables").build(),
        cubeObjectFactory.createXStorageTables(storageTables), medType));
    APIResult result = target()
        .path("metastore")
        .path("dimensions")
        .request(mediaType)
        .post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), APIResult.class);
    assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);
    return dt;
  }

  @Test
  public void testCreateAndDropDimensionTable() throws Exception {
    final String table = "test_create_dim";
    final String DB = dbPFX + "test_dim_db";
    String prevDb = getCurrentDatabase();
    createDatabase(DB);
    setCurrentDatabase(DB);
    createStorage("test");

    try {
      createDimension(table);

      // Drop the table now
      APIResult result =
          target().path("metastore/dimensions").path(table)
          .queryParam("cascade", "true")
          .queryParam("sessionid", grillSessionId).request(mediaType).delete(APIResult.class);
      assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

      // Drop again, should get 404 now
      try {
        result = target().path("metastore/dimensions").path(table)
            .queryParam("cascade", "true")
            .queryParam("sessionid", grillSessionId).request(mediaType).delete(APIResult.class);
        fail("Should have got 404");
      } catch (NotFoundException e404) {
        LOG.info("correct");
      }

    } finally {
      setCurrentDatabase(prevDb);
      dropDatabase(DB);
    }
  }

  @Test 
  public void testGetAndUpdateDimensionTable() throws Exception {
    final String table = "test_get_dim";
    final String DB = dbPFX + "test_get_dim_db";
    String prevDb = getCurrentDatabase();
    createDatabase(DB);
    setCurrentDatabase(DB);
    createStorage("test");

    try {
      DimensionTable dt1 = createDimension(table);

      JAXBElement<DimensionTable> dtElement = target().path("metastore/dimensions").path(table)
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(new GenericType<JAXBElement<DimensionTable>>() {});
      DimensionTable dt2 = dtElement.getValue();
      assertTrue (dt1 != dt2);
      assertEquals(dt2.getName(), table);
      assertEquals(dt2.getDimensionsReferences().getDimReferences().size(), 
          dt1.getDimensionsReferences().getDimReferences().size());
      assertEquals(dt2.getWeight(), dt1.getWeight());
      Map<String, String> props = JAXBUtils.mapFromXProperties(dt2.getProperties());
      assertTrue(props.containsKey("foodim"));
      assertEquals(props.get("foodim"), "bardim");


      // Update a property
      props.put("foodim", "bardim1");
      dt2.setProperties(JAXBUtils.xPropertiesFromMap(props));
      dt2.setWeight(200.0);
      // Add a column
      Column c = cubeObjectFactory.createColumn();
      c.setName("col3");
      c.setType("string");
      c.setComment("Added column");
      dt2.getColumns().getColumns().add(c);

      // Update the table
      APIResult result = target().path("metastore/dimensions")
          .path(table)
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .put(Entity.xml(cubeObjectFactory.createDimensionTable(dt2)), APIResult.class);
      assertEquals(result.getStatus(), Status.SUCCEEDED);

      // Get the updated table
      JAXBElement<DimensionTable> dtElement2 = target().path("metastore/dimensions").path(table)
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(new GenericType<JAXBElement<DimensionTable>>() {});
      DimensionTable dt3 = dtElement2.getValue();
      assertEquals(dt3.getWeight(), 200.0);

      Columns cols = dt3.getColumns();
      List<Column> colList = cols.getColumns();
      boolean foundCol = false;
      for (Column col : colList) {
        if (col.getName().equals("col3") && col.getType().equals("string") && 
            "Added column".equalsIgnoreCase(col.getComment())) {
          foundCol = true;
          break;
        }
      }
      assertTrue(foundCol);
      Map<String, String> updProps = JAXBUtils.mapFromXProperties(dt3.getProperties());
      assertEquals(updProps.get("foodim"), "bardim1");

      // Drop table
      result =
          target().path("metastore/dimensions").path(table)
          .queryParam("cascade", "true")
          .queryParam("sessionid", grillSessionId).request(mediaType).delete(APIResult.class);
      assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);
    } finally {
      setCurrentDatabase(prevDb);
      dropDatabase(DB);
    }
  }

  @Test
  public void testGetDimensionStorages() throws Exception {
    final String table = "test_get_storage";
    final String DB = dbPFX + "test_get_dim_storage_db";
    String prevDb = getCurrentDatabase();
    createDatabase(DB);
    setCurrentDatabase(DB);
    createStorage("test");

    try {
      DimensionTable dt1 = createDimension(table);
      StringList storages = target().path("metastore").path("dimensions")
          .path(table).path("storages")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(StringList.class);
      assertEquals(storages.getElements().size(), 1);
      assertTrue(storages.getElements().contains("test"));
    } finally {
      setCurrentDatabase(prevDb);
      dropDatabase(DB);
    }
  }

  @Test
  public void testAddAndDropDimensionStorages() throws Exception {
    final String table = "test_add_drop_storage";
    final String DB = dbPFX + "test_add_drop_dim_storage_db";
    String prevDb = getCurrentDatabase();
    createDatabase(DB);
    setCurrentDatabase(DB);
    createStorage("test");
    createStorage("test2");
    createStorage("test3");
    try {
      DimensionTable dt1 = createDimension(table);

      XStorageTableElement sTbl = createStorageTblElement("test2", table, "DAILY");
      APIResult result = target().path("metastore/dimensions").path(table).path("/storages")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .post(Entity.xml(cubeObjectFactory.createXStorageTableElement(sTbl)), APIResult.class);
      assertEquals(result.getStatus(), Status.SUCCEEDED);

      StringList storages = target().path("metastore").path("dimensions")
          .path(table).path("storages")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(StringList.class);
      assertEquals(storages.getElements().size(), 2);
      assertTrue(storages.getElements().contains("test"));
      assertTrue(storages.getElements().contains("test2"));

      // Check get table also contains the storage
      JAXBElement<DimensionTable> dt = target().path("metastore/dimensions").path(table)
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(new GenericType<JAXBElement<DimensionTable>>() {});
      DimensionTable dimTable = dt.getValue();
      CubeDimensionTable cdim = JAXBUtils.cubeDimTableFromDimTable(dimTable);
      assertTrue(cdim.getStorages().contains("test"));
      assertTrue(cdim.getStorages().contains("test2"));
      assertEquals(cdim.getSnapshotDumpPeriods().get("test2"), UpdatePeriod.DAILY);
      assertEquals(cdim.getSnapshotDumpPeriods().get("test"), UpdatePeriod.HOURLY);

      result = target().path("metastore/dimensions/").path(table).path("storages").path("test")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .delete(APIResult.class);
      assertEquals(result.getStatus(), Status.SUCCEEDED);

      storages = target().path("metastore").path("dimensions")
          .path(table).path("storages")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(StringList.class);
      assertEquals(storages.getElements().size(), 1);
      assertFalse(storages.getElements().contains("test"));
      assertTrue(storages.getElements().contains("test2"));

      // Check get table also contains the storage
      dt = target().path("metastore/dimensions").path(table)
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(new GenericType<JAXBElement<DimensionTable>>() {});
      dimTable = dt.getValue();
      cdim = JAXBUtils.cubeDimTableFromDimTable(dimTable);
      assertFalse(cdim.getStorages().contains("test"));
      assertTrue(cdim.getStorages().contains("test2"));
      assertEquals(cdim.getSnapshotDumpPeriods().get("test2"), UpdatePeriod.DAILY);

      // add another storage without dump period
      sTbl = createStorageTblElement("test3", table, null);
      result = target().path("metastore/dimensions").path(table).path("/storages")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .post(Entity.xml(cubeObjectFactory.createXStorageTableElement(sTbl)), APIResult.class);
      assertEquals(result.getStatus(), Status.SUCCEEDED);

      storages = target().path("metastore").path("dimensions")
          .path(table).path("storages")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(StringList.class);
      assertEquals(storages.getElements().size(), 2);
      assertTrue(storages.getElements().contains("test2"));
      assertTrue(storages.getElements().contains("test3"));

      // Check get table also contains the storage
      dt = target().path("metastore/dimensions").path(table)
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(new GenericType<JAXBElement<DimensionTable>>() {});
      dimTable = dt.getValue();
      cdim = JAXBUtils.cubeDimTableFromDimTable(dimTable);
      assertTrue(cdim.getStorages().contains("test2"));
      assertTrue(cdim.getStorages().contains("test3"));
      assertNull(cdim.getSnapshotDumpPeriods().get("test3"));
    } finally {
      setCurrentDatabase(prevDb);
      dropDatabase(DB);
    }
  }

  @Test
  public void testAddDropAllDimStorages() throws Exception {
    final String table = "testAddDropAllDimStorages";
    final String DB = dbPFX + "testAddDropAllDimStorages_db";
    String prevDb = getCurrentDatabase();
    createDatabase(DB);
    setCurrentDatabase(DB);
    createStorage("test");
    createStorage("test2");

    try {
      DimensionTable dt1 = createDimension(table);
      XStorageTableElement sTbl = createStorageTblElement("test2", table, "DAILY");
      APIResult result = target().path("metastore/dimensions").path(table).path("/storages")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .post(Entity.xml(cubeObjectFactory.createXStorageTableElement(sTbl)), APIResult.class);
      assertEquals(result.getStatus(), Status.SUCCEEDED);

      result = target().path("metastore/dimensions/").path(table).path("storages")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .delete(APIResult.class);
      assertEquals(result.getStatus(), Status.SUCCEEDED);


      JAXBElement<DimensionTable> dt = target().path("metastore/dimensions").path(table)
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(new GenericType<JAXBElement<DimensionTable>>() {});
      DimensionTable dimTable = dt.getValue();
      CubeDimensionTable cdim = JAXBUtils.cubeDimTableFromDimTable(dimTable);
      assertTrue(cdim.getStorages().isEmpty());
      assertTrue(cdim.getSnapshotDumpPeriods().isEmpty());
    } finally {
      setCurrentDatabase(prevDb);
      dropDatabase(DB);
    }
  }

  private FactTable createFactTable(String factName, String[] storages, String[] updatePeriods) {
    FactTable f = cubeObjectFactory.createFactTable();
    f.setName(factName);
    f.setWeight(10.0);
    f.setCubeName("testCube");

    Columns cols = cubeObjectFactory.createColumns();
    Column c1 = cubeObjectFactory.createColumn();
    c1.setName("c1");
    c1.setType("string");
    c1.setComment("col1");
    cols.getColumns().add(c1);

    Column c2 = cubeObjectFactory.createColumn();
    c2.setName("c2");
    c2.setType("string");
    c2.setComment("col1");
    cols.getColumns().add(c2);

    f.setColumns(cols);

    Map<String, String> properties = new HashMap<String, String>();
    properties.put("foo", "bar");
    f.setProperties(JAXBUtils.xPropertiesFromMap(properties));

    UpdatePeriods upd = cubeObjectFactory.createUpdatePeriods();

    for (int i = 0; i < storages.length; i++) {
      UpdatePeriodElement uel = cubeObjectFactory.createUpdatePeriodElement();
      uel.setStorageName(storages[i]);
      uel.getUpdatePeriods().add(updatePeriods[i]);
      upd.getUpdatePeriodElement().add(uel);
    }

    f.setStorageUpdatePeriods(upd);
    return f;
  }

  @Test
  public void testCreateFactTable() throws Exception {
    final String table = "testCreateFactTable";
    final String DB = dbPFX + "testCreateFactTable_DB";
    String prevDb = getCurrentDatabase();
    createDatabase(DB);
    setCurrentDatabase(DB);
    createStorage("S1");
    createStorage("S2");
    try {

      FactTable f = createFactTable(table, new String[] {"S1", "S2"},  new String[] {"HOURLY", "DAILY"});
      XStorageTables storageTables = cubeObjectFactory.createXStorageTables();
      storageTables.getStorageTables().add(createStorageTblElement("S1", table, "HOURLY"));
      storageTables.getStorageTables().add(createStorageTblElement("S2", table, "DAILY"));
      final FormDataMultiPart mp = new FormDataMultiPart();
      mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
          grillSessionId, medType));
      mp.bodyPart(new FormDataBodyPart(
          FormDataContentDisposition.name("fact").fileName("fact").build(),
          cubeObjectFactory.createFactTable(f), medType));
      mp.bodyPart(new FormDataBodyPart(
          FormDataContentDisposition.name("storagetables").fileName("storagetables").build(),
          cubeObjectFactory.createXStorageTables(storageTables), medType));
      APIResult result = target()
          .path("metastore")
          .path("facts")
          .request(mediaType)
          .post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), APIResult.class);
      assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

      // Get all fact names, this should contain the fact table
      StringList factNames = target().path("metastore/facts")
          .queryParam("sessionid", grillSessionId).request(mediaType).get(StringList.class);
      assertTrue(factNames.getElements().contains(table.toLowerCase()));

      // Get the created table
      JAXBElement<FactTable> gotFactElement = target().path("metastore/facts").path(table)
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(new GenericType<JAXBElement<FactTable>>() {});
      FactTable gotFact = gotFactElement.getValue();
      assertTrue(gotFact.getName().equalsIgnoreCase(table));
      assertEquals(gotFact.getWeight(), 10.0);
      CubeFactTable cf = JAXBUtils.cubeFactFromFactTable(gotFact);

      // Check for a column
      boolean foundC1 = false;
      for (FieldSchema fs : cf.getColumns()) {
        if (fs.getName().equalsIgnoreCase("c1") && fs.getType().equalsIgnoreCase("string")) {
          foundC1 = true;
          break;
        }
      }

      assertTrue(foundC1);
      assertEquals(cf.getProperties().get("foo"), "bar");
      assertTrue(cf.getStorages().contains("S1"));
      assertTrue(cf.getStorages().contains("S2"));
      assertTrue(cf.getUpdatePeriods().get("S1").contains(UpdatePeriod.HOURLY));
      assertTrue(cf.getUpdatePeriods().get("S2").contains(UpdatePeriod.DAILY));

      // Do some changes to test update
      cf.addUpdatePeriod("S2", UpdatePeriod.MONTHLY);
      cf.alterWeight(20.0);
      cf.alterColumn(new FieldSchema("c2", "int", "changed to int"));

      FactTable update = JAXBUtils.factTableFromCubeFactTable(cf);

      // Update
      result = target().path("metastore").path("facts").path(table)
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .put(Entity.xml(cubeObjectFactory.createFactTable(update)), APIResult.class);
      assertEquals(result.getStatus(), Status.SUCCEEDED);

      // Get the updated table
      gotFactElement = target().path("metastore/facts").path(table)
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(new GenericType<JAXBElement<FactTable>>() {});
      gotFact = gotFactElement.getValue();
      CubeFactTable ucf = JAXBUtils.cubeFactFromFactTable(gotFact);

      assertEquals(ucf.weight(), 20.0);
      assertTrue(ucf.getUpdatePeriods().get("S2").contains(UpdatePeriod.MONTHLY));

      boolean foundC2 = false;
      for (FieldSchema fs : cf.getColumns()) {
        if (fs.getName().equalsIgnoreCase("c2") && fs.getType().equalsIgnoreCase("int")) {
          foundC2 = true;
          break;
        }
      }
      assertTrue(foundC2);

      // Finally, drop the fact table
      result = target().path("metastore").path("facts").path(table)
          .queryParam("cascade", "true")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .delete(APIResult.class);

      assertEquals(result.getStatus(), Status.SUCCEEDED);

      // Drop again, this time it should give a 404
      try {
        result = target().path("metastore").path("facts").path(table)
            .queryParam("cascade", "true")
            .queryParam("sessionid", grillSessionId).request(mediaType)
            .delete(APIResult.class);
        fail("Expected 404");
      } catch (NotFoundException nfe) {
        // PASS
      }
    } finally {
      setCurrentDatabase(prevDb);
      dropDatabase(DB);
    }
  }

  @Test
  public void testFactStorages() throws Exception {
    final String table = "testFactStorages";
    final String DB = dbPFX + "testFactStorages_DB";
    String prevDb = getCurrentDatabase();
    createDatabase(DB);
    setCurrentDatabase(DB);
    createStorage("S1");
    createStorage("S2");
    createStorage("S3");

    try {
      String [] storages = {"S1", "S2"};
      String [] updatePeriods = {"HOURLY", "DAILY"};
      FactTable f = createFactTable(table, storages, updatePeriods);
      XStorageTables storageTables = cubeObjectFactory.createXStorageTables();
      storageTables.getStorageTables().add(createStorageTblElement("S1", table, "HOURLY"));
      storageTables.getStorageTables().add(createStorageTblElement("S2", table, "DAILY"));
      final FormDataMultiPart mp = new FormDataMultiPart();
      mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
          grillSessionId, medType));
      mp.bodyPart(new FormDataBodyPart(
          FormDataContentDisposition.name("fact").fileName("fact").build(),
          cubeObjectFactory.createFactTable(f), medType));
      mp.bodyPart(new FormDataBodyPart(
          FormDataContentDisposition.name("storagetables").fileName("storagetables").build(),
          cubeObjectFactory.createXStorageTables(storageTables), medType));
      APIResult result = target()
          .path("metastore")
          .path("facts")
          .request(mediaType)
          .post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), APIResult.class);
      assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

      // Test get storages
      StringList storageList = target().path("metastore/facts").path(table).path("storages")
          .queryParam("sessionid", grillSessionId).request(mediaType).get(StringList.class);
      assertEquals(storageList.getElements().size(), 2);
      assertTrue(storageList.getElements().contains("S1"));
      assertTrue(storageList.getElements().contains("S2"));

      XStorageTableElement sTbl = createStorageTblElement("S3", table, "HOURLY", "DAILY", "MONTHLY");
      result = target().path("metastore/facts").path(table).path("storages")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .post(Entity.xml(cubeObjectFactory.createXStorageTableElement(sTbl)), APIResult.class);
      assertEquals(result.getStatus(), Status.SUCCEEDED);

      // Get the fact storage
      StringList got = target().path("metastore/facts").path(table).path("storages")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(StringList.class);
      assertNotNull(got);
      assertEquals(got.getElements().size(), 3);
      assertTrue(got.getElements().contains("S1"));
      assertTrue(got.getElements().contains("S2"));
      assertTrue(got.getElements().contains("S3"));

      JAXBElement<FactTable> gotFactElement = target().path("metastore/facts").path(table)
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(new GenericType<JAXBElement<FactTable>>() {});
      FactTable gotFact = gotFactElement.getValue();
      CubeFactTable ucf = JAXBUtils.cubeFactFromFactTable(gotFact);

      assertTrue(ucf.getUpdatePeriods().get("S3").contains(UpdatePeriod.MONTHLY));
      assertTrue(ucf.getUpdatePeriods().get("S3").contains(UpdatePeriod.DAILY));
      assertTrue(ucf.getUpdatePeriods().get("S3").contains(UpdatePeriod.HOURLY));

      // Drop new storage
      result = target().path("metastore/facts").path(table).path("storages").path("S3")
          .queryParam("sessionid", grillSessionId).request(mediaType).delete(APIResult.class);
      assertEquals(result.getStatus(), Status.SUCCEEDED);

      // Now S3 should not be available
      storageList = null;
      storageList = target().path("metastore/facts").path(table).path("storages")
          .queryParam("sessionid", grillSessionId).request(mediaType).get(StringList.class);
      assertEquals(storageList.getElements().size(), 2);
      assertFalse(storageList.getElements().contains("S3"));
    } finally {
      setCurrentDatabase(prevDb);
      dropDatabase(DB);
    }
  }

  private XPartition createPartition(String cubeTableName, Date partDate) {
    XPartition xp = cubeObjectFactory.createXPartition();
    xp.setLocation("file:///tmp/part/test_part");
    xp.setCubeTableName(cubeTableName);
    XTimePartSpec timeSpec = cubeObjectFactory.createXTimePartSpec();
    XTimePartSpecElement timePart = cubeObjectFactory.createXTimePartSpecElement();
    timePart.setKey("dt");
    timePart.setValue(JAXBUtils.getXMLGregorianCalendar(partDate));
    timeSpec.getPartSpecElement().add(timePart);
    xp.setTimePartitionSpec(timeSpec);
    xp.setUpdatePeriod("HOURLY");
    return xp;
  }

  @Test
  public void testFactStoragePartitions() throws Exception {
    final String table = "testFactStoragePartitions";
    final String DB = dbPFX + "testFactStoragePartitions_DB";
    String prevDb = getCurrentDatabase();
    createDatabase(DB);
    setCurrentDatabase(DB);
    createStorage("S1");
    createStorage("S2");

    try {
      String [] storages = {"S1", "S2"};
      String [] updatePeriods = {"HOURLY", "DAILY"};
      FactTable f = createFactTable(table, storages, updatePeriods);
      XStorageTables storageTables = cubeObjectFactory.createXStorageTables();
      storageTables.getStorageTables().add(createStorageTblElement("S1", table, "HOURLY"));
      storageTables.getStorageTables().add(createStorageTblElement("S2", table, "DAILY"));
      final FormDataMultiPart mp = new FormDataMultiPart();
      mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
          grillSessionId, medType));
      mp.bodyPart(new FormDataBodyPart(
          FormDataContentDisposition.name("fact").fileName("fact").build(),
          cubeObjectFactory.createFactTable(f), medType));
      mp.bodyPart(new FormDataBodyPart(
          FormDataContentDisposition.name("storagetables").fileName("storagetables").build(),
          cubeObjectFactory.createXStorageTables(storageTables), medType));
      APIResult result = target()
          .path("metastore")
          .path("facts")
          .request(mediaType)
          .post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), APIResult.class);
      assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

      // Add a partition
      final Date partDate = new Date();
      XPartition xp = createPartition(table, partDate);
      APIResult partAddResult = target().path("metastore/facts/").path(table).path("storages/S2/partitions")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .post(Entity.xml(cubeObjectFactory.createXPartition(xp)), APIResult.class);
      assertEquals(partAddResult.getStatus(), Status.SUCCEEDED);

      JAXBElement<PartitionList> partitionsElement = target().path("metastore/facts").path(table).path("storages/S2/partitions")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(new GenericType<JAXBElement<PartitionList>>() {});

      PartitionList partitions = partitionsElement.getValue();
      assertNotNull(partitions);
      assertEquals(partitions.getXPartition().size(), 1);

      // Drop the partitions
      APIResult dropResult = target().path("metastore/facts").path(table).path("storages/S2/partitions")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .delete(APIResult.class);

      assertEquals(dropResult.getStatus(), Status.SUCCEEDED);

      // Verify partition was dropped
      partitionsElement = target().path("metastore/facts").path(table).path("storages/S2/partitions")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(new GenericType<JAXBElement<PartitionList>>() {});

      partitions = partitionsElement.getValue();
      assertNotNull(partitions);
      assertEquals(partitions.getXPartition().size(), 0);

      // Add again
      partAddResult = target().path("metastore/facts/").path(table).path("storages/S2/partitions")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .post(Entity.xml(cubeObjectFactory.createXPartition(xp)), APIResult.class);
      assertEquals(partAddResult.getStatus(), Status.SUCCEEDED);

      // Verify partition was added
      partitionsElement = target().path("metastore/facts").path(table).path("storages/S2/partitions")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(new GenericType<JAXBElement<PartitionList>>() {});

      partitions = partitionsElement.getValue();
      assertNotNull(partitions);
      assertEquals(partitions.getXPartition().size(), 1);

      // Drop again by values
      String val[] = new String[] {UpdatePeriod.HOURLY.format().format(partDate)};
      dropResult = target().path("metastore/facts").path(table).path("storages/S2/partition")
          .queryParam("values", StringUtils.join(val, ","))
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .delete(APIResult.class);
      assertEquals(dropResult.getStatus(), Status.SUCCEEDED);

      // Verify partition was dropped
      partitionsElement = target().path("metastore/facts").path(table).path("storages/S2/partitions")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(new GenericType<JAXBElement<PartitionList>>() {});

      partitions = partitionsElement.getValue();
      assertNotNull(partitions);
      assertEquals(partitions.getXPartition().size(), 0);
    } finally {
      setCurrentDatabase(prevDb);
      dropDatabase(DB);
    }
  }

  @Test
  public void testDimStoragePartitions() throws Exception {
    final String table = "testDimStoragePartitions";
    final String DB = dbPFX + "testDimStoragePartitions_DB";
    String prevDb = getCurrentDatabase();
    createDatabase(DB);
    setCurrentDatabase(DB);
    createStorage("S1");
    createStorage("S2");

    try {
      createDimension(table);
      // Add a partition
      final Date partDate = new Date();
      XPartition xp = createPartition(table, partDate);
      APIResult partAddResult = target().path("metastore/dimensions/").path(table).path("storages/test/partitions")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .post(Entity.xml(cubeObjectFactory.createXPartition(xp)), APIResult.class);
      assertEquals(partAddResult.getStatus(), Status.SUCCEEDED);

      JAXBElement<PartitionList> partitionsElement = target().path("metastore/dimensions").path(table).path("storages/test/partitions")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(new GenericType<JAXBElement<PartitionList>>() {});

      PartitionList partitions = partitionsElement.getValue();
      assertNotNull(partitions);
      assertEquals(partitions.getXPartition().size(), 1);

      // Drop the partitions
      APIResult dropResult = target().path("metastore/dimensions").path(table).path("storages/test/partitions")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .delete(APIResult.class);

      assertEquals(dropResult.getStatus(), Status.SUCCEEDED);

      // Verify partition was dropped
      partitionsElement = target().path("metastore/dimensions").path(table).path("storages/test/partitions")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(new GenericType<JAXBElement<PartitionList>>() {});

      partitions = partitionsElement.getValue();
      assertNotNull(partitions);
      assertEquals(partitions.getXPartition().size(), 0);

      // Add again
      partAddResult = target().path("metastore/dimensions/").path(table).path("storages/test/partitions")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .post(Entity.xml(cubeObjectFactory.createXPartition(xp)), APIResult.class);
      assertEquals(partAddResult.getStatus(), Status.SUCCEEDED);

      // Verify partition was added
      partitionsElement = target().path("metastore/dimensions").path(table).path("storages/test/partitions")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(new GenericType<JAXBElement<PartitionList>>() {});

      partitions = partitionsElement.getValue();
      assertNotNull(partitions);
      assertEquals(partitions.getXPartition().size(), 1);

      // Drop again by values
      String val[] = new String[] {UpdatePeriod.HOURLY.format().format(partDate)};
      dropResult = target().path("metastore/dimensions").path(table).path("storages/test/partition")
          .queryParam("values", StringUtils.join(val, ","))
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .delete(APIResult.class);
      assertEquals(dropResult.getStatus(), Status.SUCCEEDED);

      // Verify partition was dropped
      partitionsElement = target().path("metastore/dimensions").path(table).path("storages/test/partitions")
          .queryParam("sessionid", grillSessionId).request(mediaType)
          .get(new GenericType<JAXBElement<PartitionList>>() {});

      partitions = partitionsElement.getValue();
      assertNotNull(partitions);
      assertEquals(partitions.getXPartition().size(), 0);
    } finally {
      setCurrentDatabase(prevDb);
      dropDatabase(DB);
    }
  }

}