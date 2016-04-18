/*
 *   Copyright 2012 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.repository;


import org.saiku.database.dto.MondrianSchema;
import org.saiku.datasources.connection.RepositoryFile;
import org.saiku.service.user.UserService;
import org.saiku.service.util.exception.SaikuServiceException;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.jcr.RepositoryException;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;

/**
 * JackRabbit JCR Repository Manager for Saiku.
 */
public class ClassPathRepositoryManager implements IRepositoryManager {

  private static final Logger log = LoggerFactory.getLogger(JackRabbitRepositoryManager.class);
  private static ClassPathRepositoryManager ref;
  private final String defaultRole;
  private UserService userService;

  private String session = null;

  private ClassPathRepositoryManager(String config, String data, String password, String oldpassword, String defaultRole) {

    this.defaultRole = defaultRole;
  }

  /*
   * TODO this is currently threadsafe but to improve performance we should split it up to allow multiple sessions to hit the repo at the same time.
   */
  public static synchronized ClassPathRepositoryManager getClassPathRepositoryManager(String config, String data, String password, String oldpassword, String defaultRole) {
    if (ref == null)
      // it's ok, we can call this constructor
      ref = new ClassPathRepositoryManager(config, data, password, oldpassword, defaultRole);
    return ref;
  }

  public Object clone()
      throws CloneNotSupportedException {
    throw new CloneNotSupportedException();
    // that'll teach 'em
  }

  public void init() {

  }

  public boolean start(UserService userService) throws RepositoryException {
    this.userService = userService;
    if (session == null) {
      log.info("starting repo");

      log.info("repo started");
      log.info("logging in");

      log.info("logged in");

      createFiles();
      createFolders();
      createNamespace();
      createSchemas();
      createDataSources();

      File n = this.createFolder("/homes");

      HashMap<String, List<AclMethod>> m = new HashMap<>();
      ArrayList<AclMethod> l = new ArrayList<>();
      l.add(AclMethod.READ);
      m.put(defaultRole, l);
      AclEntry e = new AclEntry("admin", AclType.SECURED, m, null);

      Acl2 acl2 = new Acl2(n);
      acl2.addEntry(n.getPath(), e);
      acl2.serialize(n);

      this.createFolder("/datasources");

      m = new HashMap<>();
      l = new ArrayList<>();
      l.add(AclMethod.WRITE);
      l.add(AclMethod.READ);
      l.add(AclMethod.GRANT);
      m.put("ROLE_ADMIN", l);
      e = new AclEntry("admin", AclType.PUBLIC, m, null);

      acl2 = new Acl2(n);
      acl2.addEntry(n.getPath(), e);
      acl2.serialize(n);

      this.createFolder("/etc");


      this.createFolder("/legacyreports");


      acl2 = new Acl2(n);
      acl2.addEntry(n.getPath(), e);
      acl2.serialize(n);


      this.createFolder("/etc/theme");


      acl2 = new Acl2(n);
      acl2.addEntry(n.getPath(), e);
      acl2.serialize(n);

      log.info("node added");
      this.session = "init";
    }
    return true;

  }



  public void createUser(String u) throws RepositoryException {

      File node = this.createFolder("/homes/"+u);
      //node.setProperty("type", "homedirectory");
      //node.setProperty("user", u);
      AclEntry e = new AclEntry(u, AclType.PRIVATE, null, null);

      Acl2 acl2 = new Acl2(node);
      acl2.addEntry(node.getPath(), e);
      acl2.serialize(node);

  }


  public Object getHomeFolders() throws RepositoryException {
    //login();


    return this.getAllFoldersInCurrentDirectory("/homes");
  }

  public Object getHomeFolder(String path) throws RepositoryException {
    return this.getAllFoldersInCurrentDirectory("home:"+path);
  }

  public Object getFolder(String user, String directory) throws RepositoryException {
    return this.getAllFoldersInCurrentDirectory("/homes/home:"+user+"/"+directory);
  }
  private Object getFolderNode(String directory) throws RepositoryException {
    if(directory.startsWith("/")){
      directory = directory.substring(1, directory.length());
    }
    return this.getAllFoldersInCurrentDirectory(directory);
  }

  public void shutdown() {

  }
  public boolean createFolder(String username, String folder) throws RepositoryException {
    this.createFolder(folder);

    return true;
  }

  public boolean deleteFolder(String folder) throws RepositoryException {
    if(folder.startsWith("/")){
      folder = folder.substring(1, folder.length());
    }
        /*Node n;
        try {

            n = getFolder(folder);
            n.remove();
        } catch (RepositoryException e) {
            log.error("Could not remove folder: "+folder, e);
        }*/
    this.delete(folder);
    return true;
  }



  public void deleteRepository() throws RepositoryException {

  }

  public boolean moveFolder(String user, String folder, String source, String target) throws RepositoryException {
   return false;
  }

  public Object saveFile(Object file, String path, String user, String type, List<String> roles) throws
      RepositoryException {
    if(file==null){
      //Create new folder
      String parent;
      if(path.contains("/")) {
        parent = path.substring(0, path.lastIndexOf("/"));
      }
      else{
        parent = "/";
      }
      File node = getFolder(parent);
      Acl2 acl2 = new Acl2(node);
      acl2.setAdminRoles(userService.getAdminRoles());
      if (acl2.canWrite(node, user, roles)) {
        throw new SaikuServiceException("Can't write to file or folder");
      }

      int pos = path.lastIndexOf("/");
      String filename = "./" + path.substring(pos + 1, path.length());
      this.createFolder(filename);
      return null;

    }
    else {
      int pos = path.lastIndexOf("/");
      String filename = "./" + path.substring(pos + 1, path.length());
      File n = getFolder(path.substring(0, pos));
      Acl2 acl2 = new Acl2(n);
      acl2.setAdminRoles(userService.getAdminRoles());
      if (acl2.canWrite(n, user, roles)) {
        throw new SaikuServiceException("Can't write to file or folder");
      }

      File check = this.getNode(filename);
      if(check.exists()){
        check.delete();
      }
      File resNode = this.createNode(filename);
      switch (type) {
      case "nt:saikufiles":
        //resNode.addMixin("nt:saikufiles");
        break;
      case "nt:mondrianschema":
        //resNode.addMixin("nt:mondrianschema");
        break;
      case "nt:olapdatasource":
        //resNode.addMixin("nt:olapdatasource");
        break;
      }
      FileWriter fileWriter;
      try {
        fileWriter = new FileWriter(resNode);

        fileWriter.write((String)file);
        fileWriter.flush();
        fileWriter.close();
      } catch (IOException e) {
        e.printStackTrace();
      }

      return resNode;
    }
  }

  public void removeFile(String path, String user, List<String> roles) throws RepositoryException {
    File node = getFolder(path);
    Acl2 acl2 = new Acl2(node);
    acl2.setAdminRoles(userService.getAdminRoles());
    if ( !acl2.canRead(node, user, roles) ) {
      //TODO Throw exception
      throw new RepositoryException();

    }

    this.getNode(path).delete();

  }

  public void moveFile(String source, String target, String user, List<String> roles) throws RepositoryException {


  }


  public Object saveInternalFile(Object file, String path, String type) throws RepositoryException {
    if(file==null){
      //Create new folder
      String parent = path.substring(0, path.lastIndexOf("/"));
      File node = getFolder(parent);

      int pos = path.lastIndexOf("/");
      String filename = "./" + path.substring(pos + 1, path.length());
      this.createFolder(filename);

      return null;

    }
    else {
      int pos = path.lastIndexOf("/");
      String filename = "./" + path.substring(pos + 1, path.length());
      File n = getFolder(path.substring(0, pos));

      File check = this.getNode(filename);
      if(check.exists()){
        check.delete();
      }

      if(type == null){
        type ="";
      }

      File f = this.createNode(filename);
      FileWriter fileWriter = null;
      try {
        fileWriter = new FileWriter(f);

        fileWriter.write((String)file);
        fileWriter.flush();
        fileWriter.close();
      } catch (IOException e) {
        e.printStackTrace();
      }


      return f;
    }
  }

  public Object saveBinaryInternalFile(InputStream file, String path, String type) throws RepositoryException {
    if(file==null){
      //Create new folder
      String parent = path.substring(0, path.lastIndexOf("/"));
      File node = getFolder(parent);

      int pos = path.lastIndexOf("/");
      String filename = "./" + path.substring(pos + 1, path.length());
      File resNode = this.createNode(filename);
      return resNode;

    }
    else {
      int pos = path.lastIndexOf("/");
      String filename = "./" + path.substring(pos + 1, path.length());
      File n = getFolder(path.substring(0, pos));



      if(type == null){
        type ="";
      }


      File check = this.getNode(filename);
      if(check.exists()){
        check.delete();
      }

      File resNode = this.createNode(filename);

      FileOutputStream outputStream =
          null;
      try {
        outputStream = new FileOutputStream(new File(filename));
      } catch (FileNotFoundException e) {
        e.printStackTrace();
      }

      int read = 0;
      byte[] bytes = new byte[1024];

      try {
        while ((read = file.read(bytes)) != -1) {
          try {
            outputStream.write(bytes, 0, read);
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      } catch (IOException e) {
        e.printStackTrace();
      }

      return resNode;
    }
  }

  public String getFile(String s, String username, List<String> roles) throws RepositoryException {
    File node = getFolder(s);
    Acl2 acl2 = new Acl2(node);
    acl2.setAdminRoles(userService.getAdminRoles());
    if ( !acl2.canRead(node, username, roles) ) {
      //TODO Throw exception
      throw new RepositoryException();
    }
    byte[] encoded = new byte[0];
    try {
      encoded = Files.readAllBytes(Paths.get(s));
    } catch (IOException e) {
      e.printStackTrace();
    }
    try {
      return new String(encoded, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    }
    return null;

  }


  public String getInternalFile(String s) throws RepositoryException {

    byte[] encoded = new byte[0];
    try {
      encoded = Files.readAllBytes(Paths.get(s));
    } catch (IOException e) {
      e.printStackTrace();
    }
    try {
      return new String(encoded, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    }
    return null;
  }

  public InputStream getBinaryInternalFile(String s) throws RepositoryException {
    Path path = Paths.get(s);
    try {
      byte[]  f =Files.readAllBytes(path);
      return new ByteArrayInputStream(f);
    } catch (IOException e) {
      e.printStackTrace();
    }
    return null;
  }
  public void removeInternalFile(String s) throws RepositoryException {
    this.getNode(s).delete();

  }

  public List<MondrianSchema> getAllSchema() throws RepositoryException {
    /*QueryManager qm = session.getWorkspace().getQueryManager();
    String sql = "SELECT * FROM [nt:mondrianschema]";
    Query query = qm.createQuery(sql, Query.JCR_SQL2);

    QueryResult res = query.execute();

    NodeIterator node = res.getNodes();

    List<MondrianSchema> l = new ArrayList<>();
    while (node.hasNext()) {
      Node n = node.nextNode();
      String p = n.getPath();

      MondrianSchema m = new MondrianSchema();
      m.setName(n.getName());
      m.setPath(p);

      l.add(m);

    }
    return l;*/
    return null;
  }

  public List<IRepositoryObject> getAllFiles(List<String> type, String username, List<String> roles) {
    //return getRepoObjects(root, type, username, roles, false);
    return null;
  }

  public List<IRepositoryObject> getAllFiles(List<String> type, String username, List<String> roles, String path) throws
      RepositoryException {
    /*Node node = this.getNodeIfExists(path, session);
    return getRepoObjects(node, type, username, roles, true);*/
    return null;
  }


  public void deleteFile(String datasourcePath) {
    File n;
    try {
      n = getFolder(datasourcePath);
      n.delete();

    } catch (RepositoryException e) {
      log.error("Could not remove file "+datasourcePath, e );
    }

  }
  private AclEntry getAclObj(String path){
    File node = null;
    try {
      node = (File) getFolderNode(path);
    } catch (RepositoryException e) {
      log.error("Could not get file", e);
    }
    Acl2 acl2 = new Acl2(node);
    acl2.setAdminRoles(userService.getAdminRoles());
    AclEntry entry = acl2.getEntry(path);
    if ( entry == null ) entry = new AclEntry();
    return entry;
  }
  public AclEntry getACL(String object, String username, List<String> roles) {
    File node = null;
    try {
      node = (File) getFolderNode(object);
    } catch (RepositoryException e) {
      log.error("Could not get file/folder", e);
    }
    Acl2 acl2 = new Acl2(node);
    acl2.setAdminRoles(userService.getAdminRoles());

    if(acl2.canGrant(node, username, roles)){
      return getAclObj(object);
    }

    return null;
  }

  public void setACL(String object, String acl, String username, List<String> roles) throws RepositoryException {


    ObjectMapper mapper = new ObjectMapper();
    log.debug("Set ACL to " + object + " : " + acl);
    AclEntry ae = null;
    try {
      ae = mapper.readValue(acl, AclEntry.class);
    } catch (IOException e) {
      log.error("Could not read ACL blob", e);
    }

    File node = null;
    try {
      node = (File) getFolderNode(object);
    } catch (RepositoryException e) {
      log.error("Could not get file/folder "+ object, e);
    }

    Acl2 acl2 = new Acl2(node);
    acl2.setAdminRoles(userService.getAdminRoles());


    if (acl2.canGrant(node, username, roles)) {
      if (node != null) {
        acl2.addEntry(object, ae);
        acl2.serialize(node);
      }
    }
  }

  public List<MondrianSchema> getInternalFilesOfFileType(String type) throws RepositoryException {
/*    QueryManager qm = session.getWorkspace().getQueryManager();
    String sql = "SELECT * FROM [nt:mongoschema]";
    Query query = qm.createQuery(sql, Query.JCR_SQL2);

    QueryResult res = query.execute();

    NodeIterator node = res.getNodes();

    List<MondrianSchema> l = new ArrayList<>();
    while (node.hasNext()) {
      Node n = node.nextNode();
      String p = n.getPath();

      MondrianSchema m = new MondrianSchema();
      m.setName(n.getName());
      m.setPath(p);
      m.setType(type);
      l.add(m);

    }
    return l;*/
    return null;
  }


  public List<DataSource> getAllDataSources() throws RepositoryException {
    /*QueryManager qm = session.getWorkspace().getQueryManager();
    String sql = "SELECT * FROM [nt:olapdatasource]";
    Query query = qm.createQuery(sql, Query.JCR_SQL2);

    QueryResult res = query.execute();

    NodeIterator node = res.getNodes();

    List<DataSource> ds = new ArrayList<>();
    while (node.hasNext()) {
      Node n = node.nextNode();
      JAXBContext jaxbContext = null;
      Unmarshaller jaxbMarshaller = null;
      try {
        jaxbContext = JAXBContext.newInstance(DataSource.class);
      } catch (JAXBException e) {
        log.error("Could not read XML", e);
      }
      try {
        jaxbMarshaller = jaxbContext != null ? jaxbContext.createUnmarshaller() : null;
      } catch (JAXBException e) {
        log.error("Could not read XML", e);
      }
      InputStream stream = new ByteArrayInputStream(n.getNodes("jcr:content").nextNode().getProperty("jcr:data").getString().getBytes());
      DataSource d = null;
      try {
        d = (DataSource) (jaxbMarshaller != null ? jaxbMarshaller.unmarshal(stream) : null);
      } catch (JAXBException e) {
        log.error("Could not read XML", e);
      }

      if (d != null) {
        d.setPath(n.getPath());
      }
      ds.add(d);

    }

    return ds;*/
    return null;
  }

  public void saveDataSource(DataSource ds, String path, String user) throws RepositoryException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try {
      JAXBContext jaxbContext = JAXBContext.newInstance(DataSource.class);
      Marshaller jaxbMarshaller = jaxbContext.createMarshaller();

      // output pretty printed
      jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);

      jaxbMarshaller.marshal(ds, baos);


    } catch (JAXBException e) {
      log.error("Could not read XML", e);
    }

    int pos = path.lastIndexOf("/");
    String filename = "./" + path.substring(pos + 1, path.length());
    //File n = getFolder(path.substring(0, pos));
    File f = new File(path);
    try {
      FileWriter fileWriter = new FileWriter(f);

      fileWriter.write(baos.toString());
      fileWriter.flush();
      fileWriter.close();
    } catch (IOException e) {
      e.printStackTrace();
    }

  }

  public byte[] exportRepository() throws RepositoryException, IOException {
    return null;
  }

  public void restoreRepository(byte[] xml) throws RepositoryException, IOException {

  }

  public RepositoryFile getFile(String fileUrl) {
    File n = null;
    try {
      n = getFolder(fileUrl);
    } catch (RepositoryException e) {
      e.printStackTrace();
    }

    return new RepositoryFile(n != null ? n.getName() : null, null, null, fileUrl);

  }


  public Object getRepository() {
    return null;
  }

  public void setRepository(Object repository) {
    //this.repository = repository;
  }

  private void createNamespace() throws RepositoryException {
    /*NamespaceRegistry ns = session.getWorkspace().getNamespaceRegistry();

    if (!Arrays.asList(ns.getPrefixes()).contains("home")) {
      ns.registerNamespace("home", "http://www.meteorite.bi/namespaces/home");
    }*/
  }

  private void createDataSources() throws RepositoryException {
/*
    NodeTypeManager manager = session.getWorkspace().getNodeTypeManager();
    NodeTypeTemplate ntt = manager.createNodeTypeTemplate();
    ntt.setName("nt:olapdatasource");

    String[] str = new String[]{"nt:file"};
    ntt.setDeclaredSuperTypeNames(str);
    ntt.setMixin(true);

    PropertyDefinitionTemplate pdt3 = manager.createPropertyDefinitionTemplate();

    pdt3.setName("jcr:data");
    pdt3.setRequiredType(PropertyType.STRING);

    PropertyDefinitionTemplate pdt4 = manager.createPropertyDefinitionTemplate();

    pdt4.setName("enabled");
    pdt4.setRequiredType(PropertyType.STRING);

    PropertyDefinitionTemplate pdt5 = manager.createPropertyDefinitionTemplate();

    pdt5.setName("owner");
    pdt5.setRequiredType(PropertyType.STRING);


    ntt.getPropertyDefinitionTemplates().add(pdt3);
    ntt.getPropertyDefinitionTemplates().add(pdt4);
    ntt.getPropertyDefinitionTemplates().add(pdt5);
    try {
      manager.registerNodeType(ntt, false);
    }
    catch(NodeTypeExistsException ignored){

    }*/
  }

  private void createSchemas() throws RepositoryException {
/*
    NodeTypeManager manager =
        session.getWorkspace().getNodeTypeManager();
    NodeTypeTemplate ntt = manager.createNodeTypeTemplate();
    ntt.setName("nt:mondrianschema");
    //ntt.setPrimaryItemName("nt:file");
    String[] str = new String[]{"nt:file"};
    ntt.setDeclaredSuperTypeNames(str);
    ntt.setMixin(true);
    PropertyDefinitionTemplate pdt = manager.createPropertyDefinitionTemplate();

    pdt.setName("schemaname");
    pdt.setRequiredType(PropertyType.STRING);
    pdt.isMultiple();
    PropertyDefinitionTemplate pdt2 = manager.createPropertyDefinitionTemplate();

    pdt2.setName("cubenames");
    pdt2.setRequiredType(PropertyType.STRING);
    pdt2.isMultiple();

    PropertyDefinitionTemplate pdt3 = manager.createPropertyDefinitionTemplate();

    pdt3.setName("jcr:data");
    pdt3.setRequiredType(PropertyType.STRING);

    PropertyDefinitionTemplate pdt4 = manager.createPropertyDefinitionTemplate();
    pdt4.setName("owner");
    pdt4.setRequiredType(PropertyType.STRING);

    ntt.getPropertyDefinitionTemplates().add(pdt);
    ntt.getPropertyDefinitionTemplates().add(pdt2);
    ntt.getPropertyDefinitionTemplates().add(pdt3);
    ntt.getPropertyDefinitionTemplates().add(pdt4);


    try {
      manager.registerNodeType(ntt, false);
    }
    catch(NodeTypeExistsException ignored){

    }*/
  }

  private void createFiles() throws RepositoryException {
/*
    NodeTypeManager manager =
        session.getWorkspace().getNodeTypeManager();
    NodeTypeTemplate ntt = manager.createNodeTypeTemplate();
    ntt.setName("nt:saikufiles");
    String[] str = new String[]{"nt:file"};
    ntt.setDeclaredSuperTypeNames(str);
    ntt.setMixin(true);
    PropertyDefinitionTemplate pdt = manager.createPropertyDefinitionTemplate();
    pdt.setName("owner");
    pdt.setRequiredType(PropertyType.STRING);


    PropertyDefinitionTemplate pdt2 = manager.createPropertyDefinitionTemplate();
    pdt2.setName("type");
    pdt2.setRequiredType(PropertyType.STRING);

    PropertyDefinitionTemplate pdt4 = manager.createPropertyDefinitionTemplate();
    pdt4.setName("roles");
    pdt4.setRequiredType(PropertyType.STRING);

    PropertyDefinitionTemplate pdt5 = manager.createPropertyDefinitionTemplate();
    pdt5.setName("users");
    pdt5.setRequiredType(PropertyType.STRING);


    PropertyDefinitionTemplate pdt3 = manager.createPropertyDefinitionTemplate();
    pdt3.setName("jcr:data");
    pdt3.setRequiredType(PropertyType.STRING);

    ntt.getPropertyDefinitionTemplates().add(pdt);
    ntt.getPropertyDefinitionTemplates().add(pdt2);
    ntt.getPropertyDefinitionTemplates().add(pdt3);
    ntt.getPropertyDefinitionTemplates().add(pdt4);
    ntt.getPropertyDefinitionTemplates().add(pdt5);

    try {
      manager.registerNodeType(ntt, false);
    }
    catch(NodeTypeExistsException ignored){

    }*/
  }

  public void createFileMixin(String type) throws RepositoryException {
/*
    NodeTypeManager manager =
        session.getWorkspace().getNodeTypeManager();
    NodeTypeTemplate ntt = manager.createNodeTypeTemplate();
    ntt.setName(type);
    String[] str = new String[]{"nt:file"};
    ntt.setDeclaredSuperTypeNames(str);
    ntt.setMixin(true);
    PropertyDefinitionTemplate pdt = manager.createPropertyDefinitionTemplate();
    pdt.setName("owner");
    pdt.setRequiredType(PropertyType.STRING);


    PropertyDefinitionTemplate pdt2 = manager.createPropertyDefinitionTemplate();
    pdt2.setName("type");
    pdt2.setRequiredType(PropertyType.STRING);

    PropertyDefinitionTemplate pdt4 = manager.createPropertyDefinitionTemplate();
    pdt4.setName("roles");
    pdt4.setRequiredType(PropertyType.STRING);

    PropertyDefinitionTemplate pdt5 = manager.createPropertyDefinitionTemplate();
    pdt5.setName("users");
    pdt5.setRequiredType(PropertyType.STRING);


    PropertyDefinitionTemplate pdt3 = manager.createPropertyDefinitionTemplate();
    pdt3.setName("jcr:data");
    pdt3.setRequiredType(PropertyType.STRING);

    ntt.getPropertyDefinitionTemplates().add(pdt);
    ntt.getPropertyDefinitionTemplates().add(pdt2);
    ntt.getPropertyDefinitionTemplates().add(pdt3);
    ntt.getPropertyDefinitionTemplates().add(pdt4);
    ntt.getPropertyDefinitionTemplates().add(pdt5);

    try {
      manager.registerNodeType(ntt, false);
    }
    catch(NodeTypeExistsException ignored){

    }*/
  }

  public Object getRepositoryObject() {
    return null;
  }

  private void createFolders() throws RepositoryException {

   /* NodeTypeManager manager =
        session.getWorkspace().getNodeTypeManager();
    NodeTypeTemplate ntt = manager.createNodeTypeTemplate();
    ntt.setName("nt:saikufolders");
    String[] str = new String[]{"nt:folder"};
    ntt.setDeclaredSuperTypeNames(str);
    ntt.setMixin(true);
    PropertyDefinitionTemplate pdt = manager.createPropertyDefinitionTemplate();
    pdt.setName("owner");
    pdt.setRequiredType(PropertyType.STRING);


    PropertyDefinitionTemplate pdt2 = manager.createPropertyDefinitionTemplate();
    pdt2.setName("type");
    pdt2.setRequiredType(PropertyType.STRING);

    PropertyDefinitionTemplate pdt4 = manager.createPropertyDefinitionTemplate();
    pdt4.setName("roles");
    pdt4.setRequiredType(PropertyType.STRING);

    PropertyDefinitionTemplate pdt5 = manager.createPropertyDefinitionTemplate();
    pdt5.setName("users");
    pdt5.setRequiredType(PropertyType.STRING);



    ntt.getPropertyDefinitionTemplates().add(pdt);
    ntt.getPropertyDefinitionTemplates().add(pdt2);
    ntt.getPropertyDefinitionTemplates().add(pdt4);
    ntt.getPropertyDefinitionTemplates().add(pdt5);

    try {
      manager.registerNodeType(ntt, false);
    }
    catch(NodeTypeExistsException ignored){

    }*/
  }

  private List<IRepositoryObject> getRepoObjects(File files, List<String> fileType, String username, List<String> roles,
                                                 boolean includeparent) {
   return null;
  }


  private File createFolder(String path){
    boolean success = (new File(path)).mkdirs();
    if (!success) {
      // Directory creation failed
    }
    return new File(path);
  }

  private File[] getAllFoldersInCurrentDirectory(String path){
    return null;
  }
  private void delete(String folder) {
    File file = new File(folder);

    file.delete();
  }


  private File getFolder(String path) throws RepositoryException {
    return this.getNode(path);
  }

  private File getNode(String path) {
    return new File(path);
  }

  private File createNode(String filename){
    return null;
  }

}