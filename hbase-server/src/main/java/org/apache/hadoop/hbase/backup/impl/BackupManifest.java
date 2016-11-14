/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.backup.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.backup.BackupInfo;
import org.apache.hadoop.hbase.backup.BackupType;
import org.apache.hadoop.hbase.backup.util.BackupClientUtil;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.classification.InterfaceStability;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.shaded.protobuf.generated.BackupProtos;
import org.apache.hadoop.hbase.shaded.protobuf.generated.HBaseProtos;


/**
 * Backup manifest Contains all the meta data of a backup image. The manifest info will be bundled
 * as manifest file together with data. So that each backup image will contain all the info needed
 * for restore.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class BackupManifest {

  private static final Log LOG = LogFactory.getLog(BackupManifest.class);

  // manifest file name
  public static final String MANIFEST_FILE_NAME = ".backup.manifest";

  // backup image, the dependency graph is made up by series of backup images

  public static class BackupImage implements Comparable<BackupImage> {

    private String backupId;
    private BackupType type;
    private String rootDir;
    private List<TableName> tableList;
    private long startTs;
    private long completeTs;
    private ArrayList<BackupImage> ancestors;
    private HashMap<TableName, HashMap<String, Long>> incrTimeRanges;

    public BackupImage() {
      super();
    }

    public BackupImage(String backupId, BackupType type, String rootDir,
        List<TableName> tableList, long startTs, long completeTs) {
      this.backupId = backupId;
      this.type = type;
      this.rootDir = rootDir;
      this.tableList = tableList;
      this.startTs = startTs;
      this.completeTs = completeTs;
    }

    static BackupImage fromProto(BackupProtos.BackupImage im) {
      String backupId = im.getBackupId();
      String rootDir = im.getRootDir();
      long startTs = im.getStartTs();
      long completeTs = im.getCompleteTs();
      List<HBaseProtos.TableName> tableListList = im.getTableListList();
      List<TableName> tableList = new ArrayList<TableName>();
      for(HBaseProtos.TableName tn : tableListList) {
        tableList.add(ProtobufUtil.toTableName(tn));
      }

      List<BackupProtos.BackupImage> ancestorList = im.getAncestorsList();

      BackupType type =
          im.getBackupType() == BackupProtos.BackupType.FULL ? BackupType.FULL:
            BackupType.INCREMENTAL;

      BackupImage image = new BackupImage(backupId, type, rootDir, tableList, startTs, completeTs);
      for(BackupProtos.BackupImage img: ancestorList) {
        image.addAncestor(fromProto(img));
      }
      image.setIncrTimeRanges(loadIncrementalTimestampMap(im));
      return image;
    }

    BackupProtos.BackupImage toProto() {
      BackupProtos.BackupImage.Builder builder = BackupProtos.BackupImage.newBuilder();
      builder.setBackupId(backupId);
      builder.setCompleteTs(completeTs);
      builder.setStartTs(startTs);
      builder.setRootDir(rootDir);
      if (type == BackupType.FULL) {
        builder.setBackupType(BackupProtos.BackupType.FULL);
      } else{
        builder.setBackupType(BackupProtos.BackupType.INCREMENTAL);
      }

      for (TableName name: tableList) {
        builder.addTableList(ProtobufUtil.toProtoTableNameShaded(name));
      }

      if (ancestors != null){
        for (BackupImage im: ancestors){
          builder.addAncestors(im.toProto());
        }
      }

      setIncrementalTimestampMap(builder);
      return builder.build();
    }


    private static HashMap<TableName, HashMap<String, Long>>
        loadIncrementalTimestampMap(BackupProtos.BackupImage proto) {
      List<BackupProtos.TableServerTimestamp> list = proto.getTstMapList();

      HashMap<TableName, HashMap<String, Long>> incrTimeRanges =
          new HashMap<TableName, HashMap<String, Long>>();
      if(list == null || list.size() == 0) return incrTimeRanges;
      for(BackupProtos.TableServerTimestamp tst: list){
        TableName tn = ProtobufUtil.toTableName(tst.getTable());
        HashMap<String, Long> map = incrTimeRanges.get(tn);
        if(map == null){
          map = new HashMap<String, Long>();
          incrTimeRanges.put(tn, map);
        }
        List<BackupProtos.ServerTimestamp> listSt = tst.getServerTimestampList();
        for(BackupProtos.ServerTimestamp stm: listSt) {
          ServerName sn = ProtobufUtil.toServerNameShaded(stm.getServer());
          map.put(sn.getHostname() +":" + sn.getPort(), stm.getTimestamp());
        }
      }
      return incrTimeRanges;
    }


    private void setIncrementalTimestampMap(BackupProtos.BackupImage.Builder builder) {
      if (this.incrTimeRanges == null) {
        return;
      }
      for (Entry<TableName, HashMap<String,Long>> entry: this.incrTimeRanges.entrySet()) {
        TableName key = entry.getKey();
        HashMap<String, Long> value = entry.getValue();
        BackupProtos.TableServerTimestamp.Builder tstBuilder =
            BackupProtos.TableServerTimestamp.newBuilder();
        tstBuilder.setTable(ProtobufUtil.toProtoTableNameShaded(key));

        for (Map.Entry<String, Long> entry2 : value.entrySet()) {
          String s = entry2.getKey();
          BackupProtos.ServerTimestamp.Builder stBuilder = BackupProtos.ServerTimestamp.newBuilder();
          HBaseProtos.ServerName.Builder snBuilder = HBaseProtos.ServerName.newBuilder();
          ServerName sn = ServerName.parseServerName(s);
          snBuilder.setHostName(sn.getHostname());
          snBuilder.setPort(sn.getPort());
          stBuilder.setServer(snBuilder.build());
          stBuilder.setTimestamp(entry2.getValue());
          tstBuilder.addServerTimestamp(stBuilder.build());
        }
        builder.addTstMap(tstBuilder.build());
      }
    }

    public String getBackupId() {
      return backupId;
    }

    public void setBackupId(String backupId) {
      this.backupId = backupId;
    }

    public BackupType getType() {
      return type;
    }

    public void setType(BackupType type) {
      this.type = type;
    }

    public String getRootDir() {
      return rootDir;
    }

    public void setRootDir(String rootDir) {
      this.rootDir = rootDir;
    }

    public List<TableName> getTableNames() {
      return tableList;
    }

    public void setTableList(List<TableName> tableList) {
      this.tableList = tableList;
    }

    public long getStartTs() {
      return startTs;
    }

    public void setStartTs(long startTs) {
      this.startTs = startTs;
    }

    public long getCompleteTs() {
      return completeTs;
    }

    public void setCompleteTs(long completeTs) {
      this.completeTs = completeTs;
    }

    public ArrayList<BackupImage> getAncestors() {
      if (this.ancestors == null) {
        this.ancestors = new ArrayList<BackupImage>();
      }
      return this.ancestors;
    }

    public void addAncestor(BackupImage backupImage) {
      this.getAncestors().add(backupImage);
    }

    public boolean hasAncestor(String token) {
      for (BackupImage image : this.getAncestors()) {
        if (image.getBackupId().equals(token)) {
          return true;
        }
      }
      return false;
    }

    public boolean hasTable(TableName table) {
      for (TableName t : tableList) {
        if (t.equals(table)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public int compareTo(BackupImage other) {
      String thisBackupId = this.getBackupId();
      String otherBackupId = other.getBackupId();
      int index1 = thisBackupId.lastIndexOf("_");
      int index2 = otherBackupId.lastIndexOf("_");
      String name1 = thisBackupId.substring(0, index1);
      String name2 = otherBackupId.substring(0, index2);
      if(name1.equals(name2)) {
        Long thisTS = Long.valueOf(thisBackupId.substring(index1 + 1));
        Long otherTS = Long.valueOf(otherBackupId.substring(index2 + 1));
        return thisTS.compareTo(otherTS);
      } else {
        return name1.compareTo(name2);
      }
    }
    @Override
    public boolean equals(Object obj) {
      if (obj instanceof BackupImage) {
        return this.compareTo((BackupImage)obj) == 0;
      }
      return false;
    }
    @Override
    public int hashCode() {
      int hash = 33 * this.getBackupId().hashCode() + type.hashCode();
      hash = 33 * hash + rootDir.hashCode();
      hash = 33 * hash + Long.valueOf(startTs).hashCode();
      hash = 33 * hash + Long.valueOf(completeTs).hashCode();
      for (TableName table : tableList) {
        hash = 33 * hash + table.hashCode();
      }
      return hash;
    }

    public HashMap<TableName, HashMap<String, Long>> getIncrTimeRanges() {
      return incrTimeRanges;
    }

    public void setIncrTimeRanges(HashMap<TableName, HashMap<String, Long>> incrTimeRanges) {
      this.incrTimeRanges = incrTimeRanges;
    }
  }

  // hadoop hbase configuration
  protected Configuration config = null;

  // backup root directory
  private String rootDir = null;

  // backup image directory
  private String tableBackupDir = null;

  // backup log directory if this is an incremental backup
  private String logBackupDir = null;

  // backup token
  private String backupId;

  // backup type, full or incremental
  private BackupType type;

  // the table list for the backup
  private ArrayList<TableName> tableList;

  // actual start timestamp of the backup process
  private long startTs;

  // actual complete timestamp of the backup process
  private long completeTs;

  // the region server timestamp for tables:
  // <table, <rs, timestamp>>
  private Map<TableName, HashMap<String, Long>> incrTimeRanges;

  // dependency of this backup, including all the dependent images to do PIT recovery
  //private Map<String, BackupImage> dependency;
  private BackupImage backupImage;

  /**
   * Construct manifest for a ongoing backup.
   * @param backupCtx The ongoing backup context
   */
  public BackupManifest(BackupInfo backupCtx) {
    this.backupId = backupCtx.getBackupId();
    this.type = backupCtx.getType();
    this.rootDir = backupCtx.getTargetRootDir();
    if (this.type == BackupType.INCREMENTAL) {
      this.logBackupDir = backupCtx.getHLogTargetDir();
    }
    this.startTs = backupCtx.getStartTs();
    this.completeTs = backupCtx.getEndTs();
    this.loadTableList(backupCtx.getTableNames());
    this.backupImage = new BackupImage(this.backupId, this.type, this.rootDir, tableList, this.startTs,
     this.completeTs);
  }


  /**
   * Construct a table level manifest for a backup of the named table.
   * @param backupCtx The ongoing backup context
   */
  public BackupManifest(BackupInfo backupCtx, TableName table) {
    this.backupId = backupCtx.getBackupId();
    this.type = backupCtx.getType();
    this.rootDir = backupCtx.getTargetRootDir();
    this.tableBackupDir = backupCtx.getBackupStatus(table).getTargetDir();
    if (this.type == BackupType.INCREMENTAL) {
      this.logBackupDir = backupCtx.getHLogTargetDir();
    }
    this.startTs = backupCtx.getStartTs();
    this.completeTs = backupCtx.getEndTs();
    List<TableName> tables = new ArrayList<TableName>();
    tables.add(table);
    this.loadTableList(tables);
    this.backupImage = new BackupImage(this.backupId, this.type, this.rootDir, tableList, this.startTs,
      this.completeTs);
  }

  /**
   * Construct manifest from a backup directory.
   * @param conf configuration
   * @param backupPath backup path
   * @throws IOException
   */

  public BackupManifest(Configuration conf, Path backupPath) throws IOException {
    this(backupPath.getFileSystem(conf), backupPath);
  }

  /**
   * Construct manifest from a backup directory.
   * @param fs the FileSystem
   * @param backupPath backup path
   * @throws BackupException exception
   */

  public BackupManifest(FileSystem fs, Path backupPath) throws BackupException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Loading manifest from: " + backupPath.toString());
    }
    // The input backupDir may not exactly be the backup table dir.
    // It could be the backup log dir where there is also a manifest file stored.
    // This variable's purpose is to keep the correct and original location so
    // that we can store/persist it.
    this.tableBackupDir = backupPath.toString();
    this.config = fs.getConf();
    try {

      FileStatus[] subFiles = BackupClientUtil.listStatus(fs, backupPath, null);
      if (subFiles == null) {
        String errorMsg = backupPath.toString() + " does not exist";
        LOG.error(errorMsg);
        throw new IOException(errorMsg);
      }
      for (FileStatus subFile : subFiles) {
        if (subFile.getPath().getName().equals(MANIFEST_FILE_NAME)) {

          // load and set manifest field from file content
          FSDataInputStream in = fs.open(subFile.getPath());
          long len = subFile.getLen();
          byte[] pbBytes = new byte[(int) len];
          in.readFully(pbBytes);
          BackupProtos.BackupImage proto = null;
          try{
            proto = BackupProtos.BackupImage.parseFrom(pbBytes);
          } catch(Exception e){
            throw new BackupException(e);
          }
          this.backupImage = BackupImage.fromProto(proto);
          // Here the parameter backupDir is where the manifest file is.
          // There should always be a manifest file under:
          // backupRootDir/namespace/table/backupId/.backup.manifest
          this.rootDir = backupPath.getParent().getParent().getParent().toString();

          Path p = backupPath.getParent();
          if (p.getName().equals(HConstants.HREGION_LOGDIR_NAME)) {
            this.rootDir = p.getParent().toString();
          } else {
            this.rootDir = p.getParent().getParent().toString();
          }
          this.backupId = this.backupImage.getBackupId();
          this.startTs = this.backupImage.getStartTs();
          this.completeTs = this.backupImage.getCompleteTs();
          this.type = this.backupImage.getType();
          this.tableList = (ArrayList<TableName>)this.backupImage.getTableNames();
          this.incrTimeRanges = this.backupImage.getIncrTimeRanges();
          LOG.debug("Loaded manifest instance from manifest file: "
              + BackupClientUtil.getPath(subFile.getPath()));
          return;
        }
      }
      String errorMsg = "No manifest file found in: " + backupPath.toString();
      throw new IOException(errorMsg);

    } catch (IOException e) {
      throw new BackupException(e.getMessage());
    }
  }

  public BackupType getType() {
    return type;
  }

  public void setType(BackupType type) {
    this.type = type;
  }

  /**
   * Loads table list.
   * @param tableList Table list
   */
  private void loadTableList(List<TableName> tableList) {

    this.tableList = this.getTableList();
    if (this.tableList.size() > 0) {
      this.tableList.clear();
    }
    for (int i = 0; i < tableList.size(); i++) {
      this.tableList.add(tableList.get(i));
    }

    LOG.debug(tableList.size() + " tables exist in table set.");
  }

  /**
   * Get the table set of this image.
   * @return The table set list
   */
  public ArrayList<TableName> getTableList() {
    if (this.tableList == null) {
      this.tableList = new ArrayList<TableName>();
    }
    return this.tableList;
  }

  /**
   * Persist the manifest file.
   * @throws IOException IOException when storing the manifest file.
   */

  public void store(Configuration conf) throws BackupException {
    byte[] data = backupImage.toProto().toByteArray();
    // write the file, overwrite if already exist
    Path manifestFilePath =
        new Path(new Path((this.tableBackupDir != null ? this.tableBackupDir : this.logBackupDir))
            ,MANIFEST_FILE_NAME);
    try {
      FSDataOutputStream out =
          manifestFilePath.getFileSystem(conf).create(manifestFilePath, true);
      out.write(data);
      out.close();
    } catch (IOException e) {
      throw new BackupException(e.getMessage());
    }

    LOG.info("Manifest file stored to " + manifestFilePath);
  }


  /**
   * Get this backup image.
   * @return the backup image.
   */
  public BackupImage getBackupImage() {
    return backupImage;
  }

  /**
   * Add dependent backup image for this backup.
   * @param image The direct dependent backup image
   */
  public void addDependentImage(BackupImage image) {
    this.backupImage.addAncestor(image);
  }

  /**
   * Set the incremental timestamp map directly.
   * @param incrTimestampMap timestamp map
   */
  public void setIncrTimestampMap(HashMap<TableName, HashMap<String, Long>> incrTimestampMap) {
    this.incrTimeRanges = incrTimestampMap;
    this.backupImage.setIncrTimeRanges(incrTimestampMap);
  }

  public Map<TableName, HashMap<String, Long>> getIncrTimestampMap() {
    if (this.incrTimeRanges == null) {
      this.incrTimeRanges = new HashMap<TableName, HashMap<String, Long>>();
    }
    return this.incrTimeRanges;
  }

  /**
   * Get the image list of this backup for restore in time order.
   * @param reverse If true, then output in reverse order, otherwise in time order from old to new
   * @return the backup image list for restore in time order
   */
  public ArrayList<BackupImage> getRestoreDependentList(boolean reverse) {
    TreeMap<Long, BackupImage> restoreImages = new TreeMap<Long, BackupImage>();
    restoreImages.put(backupImage.startTs, backupImage);
    for (BackupImage image : backupImage.getAncestors()) {
      restoreImages.put(Long.valueOf(image.startTs), image);
    }
    return new ArrayList<BackupImage>(reverse ? (restoreImages.descendingMap().values())
        : (restoreImages.values()));
  }

  /**
   * Get the dependent image list for a specific table of this backup in time order from old to new
   * if want to restore to this backup image level.
   * @param table table
   * @return the backup image list for a table in time order
   */
  public ArrayList<BackupImage> getDependentListByTable(TableName table) {
    ArrayList<BackupImage> tableImageList = new ArrayList<BackupImage>();
    ArrayList<BackupImage> imageList = getRestoreDependentList(true);
    for (BackupImage image : imageList) {
      if (image.hasTable(table)) {
        tableImageList.add(image);
        if (image.getType() == BackupType.FULL) {
          break;
        }
      }
    }
    Collections.reverse(tableImageList);
    return tableImageList;
  }

  /**
   * Get the full dependent image list in the whole dependency scope for a specific table of this
   * backup in time order from old to new.
   * @param table table
   * @return the full backup image list for a table in time order in the whole scope of the
   *         dependency of this image
   */
  public ArrayList<BackupImage> getAllDependentListByTable(TableName table) {
    ArrayList<BackupImage> tableImageList = new ArrayList<BackupImage>();
    ArrayList<BackupImage> imageList = getRestoreDependentList(false);
    for (BackupImage image : imageList) {
      if (image.hasTable(table)) {
        tableImageList.add(image);
      }
    }
    return tableImageList;
  }

  /**
   * Check whether backup image1 could cover backup image2 or not.
   * @param image1 backup image 1
   * @param image2 backup image 2
   * @return true if image1 can cover image2, otherwise false
   */
  public static boolean canCoverImage(BackupImage image1, BackupImage image2) {
    // image1 can cover image2 only when the following conditions are satisfied:
    // - image1 must not be an incremental image;
    // - image1 must be taken after image2 has been taken;
    // - table set of image1 must cover the table set of image2.
    if (image1.getType() == BackupType.INCREMENTAL) {
      return false;
    }
    if (image1.getStartTs() < image2.getStartTs()) {
      return false;
    }
    List<TableName> image1TableList = image1.getTableNames();
    List<TableName> image2TableList = image2.getTableNames();
    boolean found = false;
    for (int i = 0; i < image2TableList.size(); i++) {
      found = false;
      for (int j = 0; j < image1TableList.size(); j++) {
        if (image2TableList.get(i).equals(image1TableList.get(j))) {
          found = true;
          break;
        }
      }
      if (!found) {
        return false;
      }
    }

    LOG.debug("Backup image " + image1.getBackupId() + " can cover " + image2.getBackupId());
    return true;
  }

  /**
   * Check whether backup image set could cover a backup image or not.
   * @param fullImages The backup image set
   * @param image The target backup image
   * @return true if fullImages can cover image, otherwise false
   */
  public static boolean canCoverImage(ArrayList<BackupImage> fullImages, BackupImage image) {
    // fullImages can cover image only when the following conditions are satisfied:
    // - each image of fullImages must not be an incremental image;
    // - each image of fullImages must be taken after image has been taken;
    // - sum table set of fullImages must cover the table set of image.
    for (BackupImage image1 : fullImages) {
      if (image1.getType() == BackupType.INCREMENTAL) {
        return false;
      }
      if (image1.getStartTs() < image.getStartTs()) {
        return false;
      }
    }

    ArrayList<String> image1TableList = new ArrayList<String>();
    for (BackupImage image1 : fullImages) {
      List<TableName> tableList = image1.getTableNames();
      for (TableName table : tableList) {
        image1TableList.add(table.getNameAsString());
      }
    }
    ArrayList<String> image2TableList = new ArrayList<String>();
    List<TableName> tableList = image.getTableNames();
    for (TableName table : tableList) {
      image2TableList.add(table.getNameAsString());
    }

    for (int i = 0; i < image2TableList.size(); i++) {
      if (image1TableList.contains(image2TableList.get(i)) == false) {
        return false;
      }
    }

    LOG.debug("Full image set can cover image " + image.getBackupId());
    return true;
  }

  public BackupInfo toBackupInfo()
  {
    BackupInfo info = new BackupInfo();
    info.setType(type);
    TableName[] tables = new TableName[tableList.size()];
    info.addTables(getTableList().toArray(tables));
    info.setBackupId(backupId);
    info.setStartTs(startTs);
    info.setTargetRootDir(rootDir);
    if(type == BackupType.INCREMENTAL) {
      info.setHlogTargetDir(logBackupDir);
    }
    return info;
  }
}
