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
package org.apache.pinot.tools.admin;

import java.lang.reflect.Field;
import org.apache.pinot.tools.Command;
import org.apache.pinot.tools.admin.command.AddSchemaCommand;
import org.apache.pinot.tools.admin.command.AddTableCommand;
import org.apache.pinot.tools.admin.command.AddTenantCommand;
import org.apache.pinot.tools.admin.command.AnonymizeDataCommand;
import org.apache.pinot.tools.admin.command.ApplyTableConfigCommand;
import org.apache.pinot.tools.admin.command.AvroSchemaToPinotSchema;
import org.apache.pinot.tools.admin.command.BackfillDateTimeColumnCommand;
import org.apache.pinot.tools.admin.command.ChangeNumReplicasCommand;
import org.apache.pinot.tools.admin.command.ChangeTableState;
import org.apache.pinot.tools.admin.command.CreateSegmentCommand;
import org.apache.pinot.tools.admin.command.DeleteClusterCommand;
import org.apache.pinot.tools.admin.command.GenerateDataCommand;
import org.apache.pinot.tools.admin.command.MoveReplicaGroup;
import org.apache.pinot.tools.admin.command.OfflineSegmentIntervalCheckerCommand;
import org.apache.pinot.tools.admin.command.PostQueryCommand;
import org.apache.pinot.tools.admin.command.RealtimeProvisioningHelperCommand;
import org.apache.pinot.tools.admin.command.RebalanceTableCommand;
import org.apache.pinot.tools.admin.command.ShowClusterInfoCommand;
import org.apache.pinot.tools.admin.command.StartBrokerCommand;
import org.apache.pinot.tools.admin.command.StartControllerCommand;
import org.apache.pinot.tools.admin.command.StartKafkaCommand;
import org.apache.pinot.tools.admin.command.StartServerCommand;
import org.apache.pinot.tools.admin.command.StartZookeeperCommand;
import org.apache.pinot.tools.admin.command.StopProcessCommand;
import org.apache.pinot.tools.admin.command.StreamAvroIntoKafkaCommand;
import org.apache.pinot.tools.admin.command.UploadSegmentCommand;
import org.apache.pinot.tools.admin.command.ValidateConfigCommand;
import org.apache.pinot.tools.admin.command.VerifyClusterStateCommand;
import org.apache.pinot.tools.admin.command.VerifySegmentState;
import org.apache.pinot.tools.segment.converter.PinotSegmentConvertCommand;
import org.apache.pinot.tools.segment.converter.SegmentMergeCommand;
import org.apache.pinot.tools.tuner.CollectMetadataForIndexTuning;
import org.apache.pinot.tools.tuner.EntriesScannedQuantileReport;
import org.apache.pinot.tools.tuner.IndexTunerCommand;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.SubCommand;
import org.kohsuke.args4j.spi.SubCommandHandler;
import org.kohsuke.args4j.spi.SubCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Class to implement Pinot Administrator, that provides the following commands:
 *
 * System property: `pinot.admin.system.exit`(default to false) is used to decide if System.exit(...) will be called with exit code.
 *
 * Sample Usage in Commandline:
 *  JAVA_OPTS="-Xms4G -Xmx4G -Dpinot.admin.system.exit=true" \
 *  bin/pinot-admin.sh AddSchema \
 *    -schemaFile /my/path/to/schema/schema.json \
 *    -controllerHost localhost \
 *    -controllerPort 9000 \
 *    -exec
 *
 */
public class PinotAdministrator {
  private static final Logger LOGGER = LoggerFactory.getLogger(PinotAdministrator.class);

  //@formatter:off
  @Argument(handler = SubCommandHandler.class, metaVar = "<subCommand>")
  @SubCommands({
      @SubCommand(name = "GenerateData", impl = GenerateDataCommand.class),
      @SubCommand(name = "CreateSegment", impl = CreateSegmentCommand.class),
      @SubCommand(name = "StartZookeeper", impl = StartZookeeperCommand.class),
      @SubCommand(name = "StartKafka", impl = StartKafkaCommand.class),
      @SubCommand(name = "StreamAvroIntoKafka", impl = StreamAvroIntoKafkaCommand.class),
      @SubCommand(name = "StartController", impl = StartControllerCommand.class),
      @SubCommand(name = "StartBroker", impl = StartBrokerCommand.class),
      @SubCommand(name = "StartServer", impl = StartServerCommand.class),
      @SubCommand(name = "AddTable", impl = AddTableCommand.class),
      @SubCommand(name = "ChangeTableState", impl = ChangeTableState.class),
      @SubCommand(name = "AddTenant", impl = AddTenantCommand.class),
      @SubCommand(name = "AddSchema", impl = AddSchemaCommand.class),
      @SubCommand(name = "UploadSegment", impl = UploadSegmentCommand.class),
      @SubCommand(name = "PostQuery", impl = PostQueryCommand.class),
      @SubCommand(name = "StopProcess", impl = StopProcessCommand.class),
      @SubCommand(name = "DeleteCluster", impl = DeleteClusterCommand.class),
      @SubCommand(name = "ShowClusterInfo", impl = ShowClusterInfoCommand.class),
      @SubCommand(name = "AvroSchemaToPinotSchema", impl = AvroSchemaToPinotSchema.class),
      @SubCommand(name = "RebalanceTable", impl = RebalanceTableCommand.class),
      @SubCommand(name = "ChangeNumReplicas", impl = ChangeNumReplicasCommand.class),
      @SubCommand(name = "ValidateConfig", impl = ValidateConfigCommand.class),
      @SubCommand(name = "VerifySegmentState", impl = VerifySegmentState.class),
      @SubCommand(name = "ConvertPinotSegment", impl = PinotSegmentConvertCommand.class),
      @SubCommand(name = "MoveReplicaGroup", impl = MoveReplicaGroup.class),
      @SubCommand(name = "BackfillSegmentColumn", impl = BackfillDateTimeColumnCommand.class),
      @SubCommand(name = "VerifyClusterState", impl = VerifyClusterStateCommand.class),
      @SubCommand(name = "ApplyTableConfig", impl = ApplyTableConfigCommand.class),
      @SubCommand(name = "RealtimeProvisioningHelper", impl = RealtimeProvisioningHelperCommand.class),
      @SubCommand(name = "MergeSegments", impl = SegmentMergeCommand.class),
      @SubCommand(name = "CheckOfflineSegmentIntervals", impl = OfflineSegmentIntervalCheckerCommand.class),
      @SubCommand(name = "CollectMetadataForIndexTuning", impl = CollectMetadataForIndexTuning.class),
      @SubCommand(name = "EntriesScannedQuantileReport", impl = EntriesScannedQuantileReport.class),
      @SubCommand(name = "IndexTuner", impl = IndexTunerCommand.class),
      @SubCommand(name = "AnonymizeData", impl = AnonymizeDataCommand.class)
  })
  Command _subCommand;
  //@formatter:on

  @Option(name = "-help", required = false, help = true, aliases = {"-h", "--h", "--help"}, usage = "Print this message.")
  boolean _help = false;
  boolean _status = false;

  private boolean getStatus() {
    return _status;
  }

  public void execute(String[] args) {
    try {
      CmdLineParser parser = new CmdLineParser(this);
      parser.parseArgument(args);

      if ((_subCommand == null) || _help) {
        printUsage();
      } else if (_subCommand.getHelp()) {
        _subCommand.printUsage();
        _status = true;
      } else {
        _status = _subCommand.execute();
      }
    } catch (CmdLineException e) {
      LOGGER.error("Error: {}", e.getMessage());
    } catch (Exception e) {
      LOGGER.error("Exception caught: ", e);
    }
  }

  public static void main(String[] args) {
    PinotAdministrator pinotAdministrator = new PinotAdministrator();
    pinotAdministrator.execute(args);
    if (System.getProperties().getProperty("pinot.admin.system.exit", "false").equalsIgnoreCase("true")) {
      System.exit(pinotAdministrator.getStatus() ? 0 : 1);
    }
  }

  public void printUsage() {
    LOGGER.info("Usage: pinot-admin.sh <subCommand>");
    LOGGER.info("Valid subCommands are:");

    Class<PinotAdministrator> obj = PinotAdministrator.class;

    for (Field f : obj.getDeclaredFields()) {
      if (f.isAnnotationPresent(SubCommands.class)) {
        SubCommands subCommands = f.getAnnotation(SubCommands.class);

        for (SubCommand subCommand : subCommands.value()) {
          Class<?> subCommandClass = subCommand.impl();
          Command command = null;

          try {
            command = (Command) subCommandClass.newInstance();
            LOGGER.info("\t" + subCommand.name() + "\t<" + command.description() + ">");
          } catch (Exception e) {
            LOGGER.info("Internal Error: Error instantiating class.");
          }
        }
      }
    }
    LOGGER.info("For other crud operations, please refer to ${ControllerAddress}/help.");
  }
}
