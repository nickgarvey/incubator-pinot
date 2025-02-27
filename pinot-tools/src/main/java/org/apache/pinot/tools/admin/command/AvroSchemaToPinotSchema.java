/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.tools.admin.command;

import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.pinot.common.data.FieldSpec;
import org.apache.pinot.common.data.Schema;
import org.apache.pinot.core.util.AvroUtils;
import org.apache.pinot.tools.Command;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Class for command to convert avro schema to pinot schema. Given that it is not always possible to
 * automatically do this, the intention is to get most of the work done by this class, and require any
 * manual editing on top.
 */
public class AvroSchemaToPinotSchema extends AbstractBaseAdminCommand implements Command {
  private static final Logger LOGGER = LoggerFactory.getLogger(AvroSchemaToPinotSchema.class);

  @Option(name = "-avroSchemaFile", forbids = {"-avroDataFile"}, metaVar = "<String>", usage = "Path to avro schema file.")
  String _avroSchemaFile;

  @Option(name = "-avroDataFile", forbids = {"-avroSchemaFile"}, metaVar = "<String>", usage = "Path to avro data file.")
  String _avroDataFile;

  @Option(name = "-outputDir", required = true, metaVar = "<string>", usage = "Path to output directory")
  String _outputDir;

  @Option(name = "-pinotSchemaName", required = true, metaVar = "<string>", usage = "Pinot schema name")
  String _pinotSchemaName;

  @Option(name = "-dimensions", metaVar = "<string>", usage = "Comma separated dimension column names.")
  String _dimensions;

  @Option(name = "-metrics", metaVar = "<string>", usage = "Comma separated metric column names.")
  String _metrics;

  @Option(name = "-timeColumnName", metaVar = "<string>", usage = "Name of the time column.")
  String _timeColumnName;

  @Option(name = "-timeUnit", metaVar = "<string>", usage = "Unit of the time column (default DAYS).")
  TimeUnit _timeUnit = TimeUnit.DAYS;

  @SuppressWarnings("FieldCanBeLocal")
  @Option(name = "-help", help = true, aliases = {"-h", "--h", "--help"}, usage = "Print this message.")
  private boolean _help = false;

  @Override
  public boolean execute()
      throws Exception {
    if (_dimensions == null && _metrics == null && _timeColumnName == null) {
      LOGGER.error(
          "Error: Missing required argument, please specify at least one of -dimensions, -metrics, -timeColumnName");
      return false;
    }

    Schema schema;
    if (_avroSchemaFile != null) {
      schema = AvroUtils.getPinotSchemaFromAvroSchemaFile(new File(_avroSchemaFile), buildFieldTypesMap(), _timeUnit);
    } else if (_avroDataFile != null) {
      schema = AvroUtils.getPinotSchemaFromAvroDataFile(new File(_avroDataFile), buildFieldTypesMap(), _timeUnit);
    } else {
      LOGGER.error("Error: Missing required argument, please specify either -avroSchemaFile, or -avroDataFile");
      return false;
    }

    schema.setSchemaName(_pinotSchemaName);

    File outputDir = new File(_outputDir);
    if (!outputDir.isDirectory()) {
      LOGGER.error("ERROR: Output directory: {} does not exist or is not a directory", _outputDir);
      return false;
    }
    File outputFile = new File(outputDir, _pinotSchemaName + ".json");
    LOGGER.info("Store Pinot schema to file: {}", outputFile.getAbsolutePath());

    try (FileWriter writer = new FileWriter(outputFile)) {
      writer.write(schema.toPrettyJsonString());
    }

    return true;
  }

  @Override
  public String description() {
    return "Extracting Pinot schema file from Avro schema or data file.";
  }

  @Override
  public boolean getHelp() {
    return _help;
  }

  @Override
  public String toString() {
    return "AvroSchemaToPinotSchema -avroSchemaFile " + _avroSchemaFile + " -avroDataFile " + _avroDataFile
        + " -outputDir " + _outputDir + " -pinotSchemaName " + _pinotSchemaName + " -dimensions " + _dimensions
        + " -metrics " + _metrics + " -timeColumnName " + _timeColumnName + " -timeUnit " + _timeUnit;
  }

  /**
   * Build a Map with column name as key and fieldType (dimension/metric/time) as value, from the
   * options list.
   *
   * @return The column <-> fieldType map.
   */
  private Map<String, FieldSpec.FieldType> buildFieldTypesMap() {
    Map<String, FieldSpec.FieldType> fieldTypes = new HashMap<>();
    if (_dimensions != null) {
      for (String column : _dimensions.split("\\s*,\\s*")) {
        fieldTypes.put(column, FieldSpec.FieldType.DIMENSION);
      }
    }
    if (_metrics != null) {
      for (String column : _metrics.split("\\s*,\\s*")) {
        fieldTypes.put(column, FieldSpec.FieldType.METRIC);
      }
    }
    if (_timeColumnName != null) {
      fieldTypes.put(_timeColumnName, FieldSpec.FieldType.TIME);
    }
    return fieldTypes;
  }
}
