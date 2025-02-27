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
package org.apache.pinot.tools;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.pinot.common.data.DateTimeFieldSpec;
import org.apache.pinot.common.data.DimensionFieldSpec;
import org.apache.pinot.common.data.FieldSpec;
import org.apache.pinot.common.data.MetricFieldSpec;
import org.apache.pinot.common.data.Schema;
import org.apache.pinot.common.data.TimeFieldSpec;
import org.apache.pinot.common.segment.ReadMode;
import org.apache.pinot.core.data.GenericRow;
import org.apache.pinot.core.data.readers.PinotSegmentRecordReader;
import org.apache.pinot.core.indexsegment.immutable.ImmutableSegment;
import org.apache.pinot.core.indexsegment.immutable.ImmutableSegmentLoader;
import org.apache.pinot.core.segment.index.ColumnMetadata;
import org.apache.pinot.core.segment.index.SegmentMetadataImpl;
import org.apache.pinot.core.segment.index.readers.Dictionary;
import org.apache.pinot.pql.parsers.Pql2Compiler;
import org.apache.pinot.pql.parsers.pql2.ast.AstNode;
import org.apache.pinot.pql.parsers.pql2.ast.BetweenPredicateAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.BooleanOperatorAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.ComparisonPredicateAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.FunctionCallAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.GroupByAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.IdentifierAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.InPredicateAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.LiteralAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.OutputColumnAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.OutputColumnListAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.PredicateAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.PredicateListAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.SelectAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.StarColumnListAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.StarExpressionAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.StringLiteralAstNode;
import org.apache.pinot.pql.parsers.pql2.ast.WhereAstNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The goal of this tool is to generate test dataset (as Avro files) with
 * characteristics similar to a given source dataset. The source dataset is
 * a set of Pinot segments. The tool can be used in situations where actual
 * source data isn't allowed to be used for the purpose of testing (regression,
 * performance, functional, evaluation of other OLAP systems etc).
 *
 * The tool understands the characteristics of the given dataset (Pinot segments)
 * and generates corresponding random data while preserving those characteristics.
 * The tool can then also be used to generate queries for the random data.
 *
 * So if we have a set of production data which you want to use for testing
 * but are unable to do so (because of security restrictions etc), then this tool
 * can be used to generate corresponding anonymous data and queries. Users can then
 * use the anonymized dataset (avro files) and generated queries for their testing.
 *
 * One avro file is generated per input Pinot segment. The tool also randomizes the
 * column names (and table name) so that source schema is not revealed. The user is also
 * allowed to provide a set of columns for which they want the data to be retained
 * as is (not anonymized). User should be careful when choosing these columns. Ideally
 * these should be time (or time related) columns since they don't reveal anything and so
 * it is fine to copy them as is from souce segments into Avro files.
 *
 * Please see the implementation notes further in the code explaining the global
 * dictionary building, and data generation and query generation phases in detail.
 *
 * Also, please see usage examples in
 * {@link org.apache.pinot.tools.admin.command.AnonymizeDataCommand} to learn
 * how this tool can be invoked from command line.
 *
 * Future work
 * (1) Add support for multi-value columns
 * (2) Add support for BYTES type
 * (3) Add support for partitioning (where dataset is hash partitioned on column)
 * (4) Add support for ORDER BY in query generator
 * (5) Potential memory explosion for extreme high cardinality global dictionary columns
 *     Please read the design doc for details.
 * (6) Add support for no dictionary filter columns (Generally this should not happen since
 *     whoever is using filter on a column should have created a dictionary for that column.
 *     But in case the filter column does not have segment dictionary, then we will not be
 *     able to build global dictionary)
 * (7) Make the global dictionary building phase per column basis to reduce the memory footprint
 * (8) Add support for generating queries when a predicate was not there in the original table
 *     and therefore not present in global dictionary either. The current implementation won't be
 *     able to rewrite this predicate correctly. It will substitute the original value with null.
 */
public class PinotDataAndQueryAnonymizer {
  private static final Logger LOGGER = LoggerFactory.getLogger(PinotDataAndQueryAnonymizer.class);

  private final static int INT_BASE_VALUE = 1000;
  private final static long LONG_BASE_VALUE = 100000;
  private static final float FLOAT_BASE_VALUE = 100.23f;
  private static final double DOUBLE_BASE_VALUE = 1000.2375;
  private static final String DICT_FILE_EXTENSION = ".dict";
  private static final String COLUMN_MAPPING_FILE_KEY = "columns.mapping";
  private static final String COLUMN_MAPPING_SEPARATOR = ":";

  private final String _outputDir;
  private int _numFilesToGenerate;
  private final String _segmentDir;
  private final String _filePrefix;
  // dictionaries used to generate data with same cardinality and data distribution
  // as in source table segments
  private final Map<String, OrigAndDerivedValueHolder> _columnToGlobalDictionary;
  // used to map the original column name to a generated column name
  private final Map<String, String> _origToDerivedColumnsMap;

  private final Stopwatch _timeToBuildDictionaries = Stopwatch.createUnstarted();
  private final Stopwatch _timeToGenerateAvroFiles = Stopwatch.createUnstarted();

  private Schema _pinotSchema = null;
  private org.apache.avro.Schema _avroSchema = null;

  private final Map<String, FieldSpec.DataType> _columnToDataTypeMap;
  // name of columns to build global dictionary for and corresponding total cardinality
  private final Map<String, Integer> _globalDictionaryColumns;
  // name of time (or time derived) columns for which we will retain data
  private final Set<String> _columnsNotAnonymized;

  private String[] _segmentDirectories;

  /**
   * Create an instance of PinotDataGenerator
   * @param outputDir parent directory where avro files will be generated
   * @param segmentDir directory containing segment
   * @param fileNamePrefix generated avro file name prefix
   */
  public PinotDataAndQueryAnonymizer(
      String segmentDir,
      String outputDir,
      String fileNamePrefix,
      Map<String, Integer> globalDictionaryColumns,
      Set<String> columnsNotAnonymized) {
    _outputDir = outputDir;
    _segmentDir = segmentDir;
    _filePrefix = fileNamePrefix;
    _columnToGlobalDictionary = new HashMap<>();
    _origToDerivedColumnsMap = new HashMap<>();
    _columnToDataTypeMap = new HashMap<>();
    _globalDictionaryColumns = globalDictionaryColumns;
    _columnsNotAnonymized = columnsNotAnonymized;

    for (String column : columnsNotAnonymized) {
      // sometime the predicates can also be on columns which the user
      // wants to retain the data for as is and thus these columns will be
      // part of filter column set as well.
      // But since the values are retained for these columns, we
      // don't need to build global dictionary for them. So remove
      // these columns from the filter column set.
      _globalDictionaryColumns.remove(column);
    }

    StringBuilder sb = new StringBuilder();
    sb.append("Columns to retain data for: ");
    for (String column : _columnsNotAnonymized) {
      sb.append(column).append(", ");
    }

    LOGGER.info(sb.toString());

    sb = new StringBuilder();
    sb.append("Columns to build global dictionary for: ");
    for (Map.Entry<String, Integer> entry : _globalDictionaryColumns.entrySet()) {
      sb.append("Column: ").append(entry.getKey()).append(" Cardinality: ").append(entry.getValue()).append(", ");
    }

    LOGGER.info(sb.toString());
  }

  /*****************************************************
   *                                                   *
   *             Global Dictionary Builder             *
   *                                                   *
   *****************************************************/

  /*
   * Global Dictionary Implementation Notes
   *
   * We build global dictionary for a specific set of columns that participate in filter (predicates)
   * in user queries.
   *
   * First step of using this tool is to feed in the actual queries and parse them to extract the set
   * of columns that are there in WHERE clause.
   *
   * Once these columns are identified, we build global dictionaries for them for two main reasons:
   *
   * (1) cardinality of such columns is same as in source data
   * (2) distribution and order of values in such columns is same as in source data.
   *
   * This ensures that queries with =, <, >, <=, >= predicates on the actual data yield same results on
   * generated data.
   *
   * Global dictionary is built in three steps:
   *
   * Step 1:
   *
   * Read dictionary from each segment and insert original values into the global dictionary.
   *
   * The values read from each segment dictionary will be in sorted order but that is not guaranteed across
   * segments. Secondly, the value seen in a segment might have already been inserted into global dictionary
   * while scanning the dictionary of previous segment.
   *
   * Since overall (across segments) there is no sort order and we need to prevent duplicates, we do a linear search
   * to detect if the value has already been inserted into before.
   *
   * Once we finish reading dictionaries from each segment, our global dictionary is 50% built -- it has all the
   * original values.
   *
   * Step 2: Sort the original value array
   *
   * Step 3: Generate derived values (sorted)
   *
   * Finally we have a 1-1 mapping between original values and derived values while maintaining the order. We then
   * persist the global dictionary and column name mapping to disk.
   */

  /**
   * Per column holder to store both original values and corresponding
   * derived values in global dictionary.
   */
  private static class OrigAndDerivedValueHolder {
    FieldSpec.DataType _dataType;
    Object[] _origValues;
    Object[] _derivedValues;
    int _index;
    Comparator _comparator;

    OrigAndDerivedValueHolder(
        FieldSpec.DataType dataType,
        int totalCardinality) {
      _dataType = dataType;
      // user specified cardinality might be slightly inaccurate depending
      // on when the user determined the cardinality and when the source
      // segments were given to this tool so just provision 10% additional
      // capacity
      _origValues = new Object[totalCardinality + (int)(0.1 * totalCardinality)];
      // we will use index value as the actual total cardinality based
      // on total number of values read from dictionary of each segment
      _index = 0;
      buildComparator();
    }

    void buildComparator() {
      switch (_dataType) {
        case INT:
          _comparator = new Comparator() {
            @Override
            public int compare(Object o1, Object o2) {
              return ((Integer)o1).compareTo((Integer)o2);
            }
          };
          break;
        case LONG:
          _comparator = new Comparator() {
            @Override
            public int compare(Object o1, Object o2) {
              return ((Long)o1).compareTo((Long)o2);
            }
          };
          break;
        case FLOAT:
          _comparator = new Comparator() {
            @Override
            public int compare(Object o1, Object o2) {
              return ((Float)o1).compareTo((Float) o2);
            }
          };
          break;
        case DOUBLE:
          _comparator = new Comparator() {
            @Override
            public int compare(Object o1, Object o2) {
              return ((Double)o1).compareTo((Double) o2);
            }
          };
        case STRING:
          _comparator = new Comparator() {
            @Override
            public int compare(Object o1, Object o2) {
              return ((String)o1).compareTo((String) o2);
            }
          };
          break;
        default:
          throw new UnsupportedOperationException("global dictionary currently does not support: " + _dataType.name());
      }
    }

    void sort() {
      Arrays.sort(_origValues, 0, _index, _comparator);
    }

    void addOrigValue(Object origValue) {
      if (!linearSearch(origValue)) {
        _origValues[_index++] = origValue;
      }
    }

    void setDerivedValues(Object[] derivedValues) {
      Preconditions.checkState(derivedValues.length == _index);
      _derivedValues = derivedValues;
    }

    Object getDerivedValueForOrigValue(Object origValue) {
      int index = binarySearch(0, _index - 1, origValue);
      Preconditions.checkState(index >= 0, "Expecting origValue: " + origValue);
      return _derivedValues[index];
    }

    // used during building global dictionary phase
    // to prevent adding original values already
    // seen from previous segments
    boolean linearSearch(Object key) {
      for (int i = 0; i < _index; i++) {
        if (key == null) {
          if (_origValues[i] == null) {
            return true;
          }
        } else {
          if (_origValues[i].equals(key)) {
            return true;
          }
        }
      }
      return false;
    }

    // used during data generation phase.
    // since the global dictionary is fully built
    // and sorted, we can do binary search
    // TODO: make this iterative
    int binarySearch(int low, int high, Object key) {
      if (low > high) return -1;

      int mid = (low + high)/2;

      if (_origValues[mid].equals(key)) {
        return mid;
      }

      if (isLessThan(_origValues[mid], key) < 0) {
        return binarySearch(mid + 1, high, key);
      }

      return binarySearch(low, mid - 1, key);
    }

    private int isLessThan(Object o1, Object o2) {
      switch (_dataType) {
        case INT:
          return ((Integer) o1).compareTo((Integer)o2);
        case LONG:
          return ((Long) o1).compareTo((Long)o2);
        case DOUBLE:
          return ((Double) o1).compareTo((Double) o2);
        case FLOAT:
          return ((Float) o1).compareTo((Float) o2);
        case STRING:
          return ((String) o1).compareTo((String) o2);
        default:
          throw new IllegalStateException("unexpected data type: " + _dataType);
      }
    }
  }

  public void buildGlobalDictionaries() throws Exception {
    if (_globalDictionaryColumns.isEmpty()) {
      LOGGER.info("Set of global dictionary columns is empty");
      return;
    }

    _timeToBuildDictionaries.start();

    File segmentParentDirectory = new File(_segmentDir);
    _segmentDirectories = segmentParentDirectory.list();
    _numFilesToGenerate = _segmentDirectories.length;

    // STEP 1 for building global dictionary
    for (String segmentDirectory : _segmentDirectories) {
      readDictionariesFromSegment(_segmentDir + "/" + segmentDirectory);
    }

    // STEP 2 for building global dictionary
    sortOriginalValuesInGlobalDictionaries();

    // STEP 3 for building global dictionary
    addDerivedValuesToGlobalDictionaries();

    _timeToBuildDictionaries.stop();

    // write global dictionaries and column mapping file to disk
    // query generator phase will load them
    writeGlobalDictionariesAndColumnMapping();
    LOGGER.info("Finished building global dictionaries. Time taken: {}secs", _timeToBuildDictionaries.elapsed(TimeUnit.SECONDS));
  }

  /**
   * Read dictionaries from a single segment
   * @param segmentDirectory segment index directory
   * @throws Exception
   */
  private void readDictionariesFromSegment(String segmentDirectory) throws Exception {
    File segmentIndexDir = new File(segmentDirectory);
    SegmentMetadataImpl segmentMetadata = new SegmentMetadataImpl(segmentIndexDir);

    if (_pinotSchema == null) {
      // only do this for first segment
      _pinotSchema = segmentMetadata.getSchema();
      anonymizeColumnNames(_pinotSchema);
      _avroSchema = getAvroSchemaFromPinotSchema(_pinotSchema);
    }

    // read dictionaries from segment and build equivalent dictionary of random values
    ImmutableSegment immutableSegment = ImmutableSegmentLoader.load(segmentIndexDir, ReadMode.mmap);
    Map<String, ColumnMetadata> columnMetadataMap = segmentMetadata.getColumnMetadataMap();
    for (Map.Entry<String, ColumnMetadata> entry : columnMetadataMap.entrySet()) {
      String columnName = entry.getKey();
      if (!_globalDictionaryColumns.containsKey(columnName)) {
        // global dictionary will be built only for columns participating in filters
        continue;
      }
      int totalCardinality = _globalDictionaryColumns.get(columnName);
      ColumnMetadata columnMetadata = entry.getValue();
      Dictionary dictionary = immutableSegment.getDictionary(columnName);
      if (dictionary != null) {
        // column has dictionary
        for (int dictId = 0; dictId < columnMetadata.getCardinality(); dictId++) {
          Object origValue = dictionary.get(dictId);
          addOrigValueToGlobalDictionary(origValue, columnName, columnMetadata, totalCardinality);
        }
      } else {
        // we build global dictionary only for columns that appear in filter
        // and such columns should ideally always have a dictionary in segment
        // so we should never end up here
        throw new UnsupportedOperationException("Data generator currently does not support filter columns without dictionary");
      }
    }
  }

  /**
   * First step towards building global dictionary
   * by inserting the original values from segments
   * into global dictionary
   * @param origValue original value
   * @param column column name
   * @param columnMetadata column metadata
   * @param cardinality total cardinality of column
   */
  private void addOrigValueToGlobalDictionary(
      Object origValue,
      String column,
      ColumnMetadata columnMetadata,
      int cardinality) {
    _columnToGlobalDictionary.putIfAbsent(column, new OrigAndDerivedValueHolder(columnMetadata.getDataType(), cardinality));
    OrigAndDerivedValueHolder holder = _columnToGlobalDictionary.get(column);
    holder.addOrigValue(origValue);
  }

  /**
   * This is the second step where we complete the global dictionaries
   * by sorting the original values to get sort order across all segments
   */
  private void sortOriginalValuesInGlobalDictionaries() {
    for (Map.Entry<String, OrigAndDerivedValueHolder> entry : _columnToGlobalDictionary.entrySet()) {
      OrigAndDerivedValueHolder valueHolder = entry.getValue();
      valueHolder.sort();
    }
  }

  /**
   * This is the third and final step where we complete the global
   * dictionaries by generating values:
   *
   * For numeric columns, we generate in order since
   * we start with a base value.
   * For string column, we first generate and then sort
   */
  private void addDerivedValuesToGlobalDictionaries() {
    // update global dictionary for each column by adding
    // the corresponding generated value for each orig value
    for (Map.Entry<String, OrigAndDerivedValueHolder> entry : _columnToGlobalDictionary.entrySet()) {
      OrigAndDerivedValueHolder valueHolder = entry.getValue();
      generateDerivedValuesForGlobalDictionary(valueHolder);
    }
  }

  private void generateDerivedValuesForGlobalDictionary(OrigAndDerivedValueHolder valueHolder) {
    int cardinality = valueHolder._index;
    switch (valueHolder._dataType) {
      case INT:
        valueHolder.setDerivedValues(generateDerivedIntValues(cardinality));
        break;
      case LONG:
        valueHolder.setDerivedValues(generateDerivedLongValues(cardinality));
        break;
      case FLOAT:
        valueHolder.setDerivedValues(generateDerivedFloatValues(cardinality));
        break;
      case DOUBLE:
        valueHolder.setDerivedValues(generateDerivedDoubleValues(cardinality));
        break;
      case STRING:
        valueHolder.setDerivedValues(generateDerivedStringValues(valueHolder));
        break;
      default:
        throw new UnsupportedOperationException("global dictionary currently does not support: " + valueHolder._dataType.name());
    }
  }

  private Integer[] generateDerivedIntValues(int cardinality) {
    Integer[] values = new Integer[cardinality];
    for (int i = 0; i < cardinality; i++) {
      values[i] = INT_BASE_VALUE + i;
    }
    return values;
  }

  private Long[] generateDerivedLongValues(int cardinality) {
    Long[] values = new Long[cardinality];
    for (int i = 0; i < cardinality; i++) {
      values[i] = LONG_BASE_VALUE + (long)i;
    }
    return values;
  }

  private Float[] generateDerivedFloatValues(int cardinality) {
    Float[] values = new Float[cardinality];
    for (int i = 0; i < cardinality; i++) {
      values[i] = FLOAT_BASE_VALUE + (float)i;
    }
    return values;
  }

  private Double[] generateDerivedDoubleValues(int cardinality) {
    Double[] values = new Double[cardinality];
    for (int i = 0; i < cardinality; i++) {
      values[i] = DOUBLE_BASE_VALUE + (double)i;
    }
    return values;
  }

  private String[] generateDerivedStringValues(OrigAndDerivedValueHolder valueHolder) {
    int cardinality = valueHolder._index;
    String[] values = new String[cardinality];
    for (int i = 0; i < cardinality; i++) {
      int origValLength = ((String)valueHolder._origValues[i]).length();
      values[i] = RandomStringUtils.randomAlphanumeric(origValLength);
    }
    Arrays.sort(values);
    return values;
  }

  /**
   * Write global dictionaries and column name mapping to disk
   * @throws Exception
   */
  private void writeGlobalDictionariesAndColumnMapping() throws Exception {
    // write column name mapping
    Stopwatch stopwatch = Stopwatch.createUnstarted();
    stopwatch.start();
    PrintWriter columnMappingWriter = new PrintWriter(new BufferedWriter(new FileWriter(_outputDir + "/" + COLUMN_MAPPING_FILE_KEY)));
    for (Map.Entry<String, String> entry : _origToDerivedColumnsMap.entrySet()) {
      String columnName = entry.getKey();
      String derivedColumnName = entry.getValue();
      columnMappingWriter.println(columnName + COLUMN_MAPPING_SEPARATOR + derivedColumnName);
    }
    columnMappingWriter.flush();
    // write global dictionary for each column
    for (String column : _columnToGlobalDictionary.keySet()) {
      PrintWriter dictionaryWriter = new PrintWriter(new BufferedWriter(new FileWriter(_outputDir + "/" + column + DICT_FILE_EXTENSION)));
      OrigAndDerivedValueHolder holder = _columnToGlobalDictionary.get(column);
      for (int i = 0; i < holder._index; i++) {
        dictionaryWriter.println(holder._origValues[i]);
        dictionaryWriter.println(holder._derivedValues[i]);
      }
      dictionaryWriter.flush();
    }
    stopwatch.stop();
    LOGGER.info("Finished writing global dictionaries and column name mapping to disk. Time taken: {}secs. Please see the files in {}",
        stopwatch.elapsed(TimeUnit.SECONDS), _outputDir);
  }



  /*****************************************************
   *                                                   *
   *             Data Generator                        *
   *                                                   *
   *****************************************************/

  /*
   * Data Generation Implementation Notes
   *
   * Read each segment row by row and generate a corresponding Avro file per segment. When building the
   * corresponding Avro row from Pinot segment row, three cases are possible for  a column:
   *
   * (1) Column is a global dictionary column. In this case, we consult the global dictionary to look up
   * the original value and get the corresponding mapped derived  value. Store this derived value in avro
   * record.
   *
   * (2) Column is a member of user supplied time/time-derived column set. In this case, store the original
   * value as is in the avro record.
   *
   * (3) Column is neither of the above two -- in this case, we simply generate a random value and insert into
   * Avro record. We don't have to remember this value ever again as it won't be used during query generation.
   *
   * NOTE: Currently the data generation is done immediately after global dictionary is built so data generation
   * step doesn't load global dictionary into memory. The data structures are already in memory. The query generation
   * is done separately and so it loads the global dictionaries and column name mapping.
   */

  public void generateAvroFiles()
      throws Exception {
    _timeToGenerateAvroFiles.start();

    int totalRows = 0;
    for (int file = 0 ; file < _numFilesToGenerate; file++) {
      String pathToSegment = _segmentDir + "/" + _segmentDirectories[file];
      File segmentIndexDir = new File(pathToSegment);
      try (PinotSegmentRecordReader recordReader = new PinotSegmentRecordReader(segmentIndexDir, _pinotSchema, null);
          DataFileWriter<GenericData.Record> avroRecordWriter = new DataFileWriter<>(new GenericDatumWriter<GenericData.Record>(_avroSchema))) {
        String pathToAvroFile = _outputDir + "/" + _filePrefix + file;
        LOGGER.info("Using segment {} to generate Avro file {}", pathToSegment, pathToAvroFile);
        File outputAvroFile = new File(pathToAvroFile);
        avroRecordWriter.create(_avroSchema, outputAvroFile);
        GenericRow row = new GenericRow();
        int rows = 0;
        while (recordReader.hasNext()) {
          // read actual row from segment
          row = recordReader.next(row);
          buildAvroRow(avroRecordWriter, row);
          rows++;
        }
        totalRows += rows;
        LOGGER.info("Generated Avro File {} with {} rows", pathToAvroFile, rows);
      }
    }

    _timeToGenerateAvroFiles.stop();
    LOGGER.info("Finished generating {} rows across {} avro files. Time taken {}secs",
        totalRows, _numFilesToGenerate, _timeToGenerateAvroFiles.elapsed(TimeUnit.SECONDS));
  }

  private void buildAvroRow(
      DataFileWriter<GenericData.Record> avroRecordWriter,
      GenericRow pinotRow) throws Exception {
    GenericData.Record record = new GenericData.Record(_avroSchema);
    Map<String, Object> fieldToValueMap = pinotRow.getFieldToValueMap();
    for (Map.Entry<String, Object> entry : fieldToValueMap.entrySet()) {
      String columnName = entry.getKey();
      Object origValue = entry.getValue();
      String derivedColumnName = _origToDerivedColumnsMap.get(columnName);
      if (_columnsNotAnonymized.contains(columnName)) {
        // retain the value
        record.put(derivedColumnName, origValue);
      } else if (_globalDictionaryColumns.containsKey(columnName)) {
        // use the randomly generated value from global dictionary
        OrigAndDerivedValueHolder valueHolder = _columnToGlobalDictionary.get(columnName);
        Object derivedValue = valueHolder.getDerivedValueForOrigValue(origValue);
        record.put(derivedColumnName, derivedValue);
      } else {
        // generate random value; but we don't need to store this value
        // anywhere as it won't be needed again during query generation phase
        Object derivedValue = generateRandomDerivedValue(origValue, _columnToDataTypeMap.get(columnName));
        record.put(derivedColumnName, derivedValue);
      }
    }
    avroRecordWriter.append(record);
  }

  /**
   * Used for columns that are neither date columns (for which we retain value)
   * or filter columns (for which we generate global dictionary 1:1 mapping between
   * original and generated values).
   * We can generate any random value for such columns and it doesn't matter to
   * the query generation.
   * @param origValue original value as seen when reading Pinot segment record
   * @param dataType data type of column
   * @return random generated value
   */
  private Object generateRandomDerivedValue(Object origValue, FieldSpec.DataType dataType) {
    Random random = new Random();
    switch (dataType) {
      case INT:
        return random.nextInt();
      case LONG:
        return random.nextLong();
      case FLOAT:
        return FLOAT_BASE_VALUE + random.nextFloat();
      case DOUBLE:
        return DOUBLE_BASE_VALUE + random.nextDouble();
      case STRING:
        String val = (String)origValue;
        if (val == null || val.equals("") || val.equals(" ") || val.equals("null")) {
          return val;
        } else {
          return RandomStringUtils.randomAlphanumeric(val.length());
        }
      default:
        // we should not be here as during schema read step
        // (which is the first thing we do)
        // we should have already caught this.
        throw new IllegalStateException("Unexpected data type");
    }
  }

  /**
   * Anonymize the column names in source schema
   * @param pinotSchema pinot schema
   */
  private void anonymizeColumnNames(Schema pinotSchema) {
    Map<String, FieldSpec> fieldSpecMap = pinotSchema.getFieldSpecMap();
    int col = 0;
    String prefix = "";
    for (Map.Entry<String, FieldSpec> entry : fieldSpecMap.entrySet()) {
      String columnName = entry.getKey();
      FieldSpec fieldSpec = entry.getValue();
      if (!fieldSpec.isSingleValueField()) {
        // TODO: add multi-value column support
        throw new UnsupportedOperationException("Data generator currently does not support multi-value columns");
      }
      if (fieldSpec instanceof DimensionFieldSpec) {
        prefix = "DIMENSION";
      } else if (fieldSpec instanceof MetricFieldSpec) {
        prefix = "METRIC";
      } else if (fieldSpec instanceof TimeFieldSpec) {
        prefix = "TIME";
      } else if (fieldSpec instanceof DateTimeFieldSpec) {
        prefix = "DATE_TIME";
      }
      String newColumnName = prefix +"_COL_" + col;
      _origToDerivedColumnsMap.put(columnName, newColumnName);
      ++col;
    }
  }

  /**
   * Generate corresponding Avro schema from Pinot table schema
   * @param pinotSchema pinot table schema
   * @return Avro schema
   */
  private org.apache.avro.Schema getAvroSchemaFromPinotSchema(Schema pinotSchema) {
    SchemaBuilder.FieldAssembler<org.apache.avro.Schema> fieldAssembler = SchemaBuilder.record("record").fields();
    for (FieldSpec fieldSpec : pinotSchema.getAllFieldSpecs()) {
      FieldSpec.DataType dataType = fieldSpec.getDataType();
      _columnToDataTypeMap.put(fieldSpec.getName(), dataType);
      switch (dataType) {
        case INT:
          fieldAssembler = fieldAssembler.name(_origToDerivedColumnsMap.get(fieldSpec.getName())).type().intType().noDefault();
          break;
        case LONG:
          fieldAssembler = fieldAssembler.name(_origToDerivedColumnsMap.get(fieldSpec.getName())).type().longType().noDefault();
          break;
        case FLOAT:
          fieldAssembler = fieldAssembler.name(_origToDerivedColumnsMap.get(fieldSpec.getName())).type().floatType().noDefault();
          break;
        case DOUBLE:
          fieldAssembler = fieldAssembler.name(_origToDerivedColumnsMap.get(fieldSpec.getName())).type().doubleType().noDefault();
          break;
        case STRING:
          fieldAssembler = fieldAssembler.name(_origToDerivedColumnsMap.get(fieldSpec.getName())).type().stringType().noDefault();
          break;
        default:
          // TODO: add BYTES support
          throw new UnsupportedOperationException("Data generator does not support type: " + dataType);
      }
    }

    return fieldAssembler.endRecord();
  }


  /*****************************************************
   *                                                   *
   *             QueryGenerator                        *
   *                                                   *
   *****************************************************/

  /*
   * Query Generator Implementation Notes
   *
   * Query Generation depends on previous phase where we built
   * global dictionary and column name mapping.
   *
   * Generator first loads the per column global dictionary and
   * column name mapping.
   *
   * For each query, it builds an AST and walks the AST to
   * rewrite the query
   *
   * The select list items (column names or functions with column names)
   * are rewritten using the anonymous column name from mapping file.
   * Similarly the column names in predicates are rewritten in same
   * manner
   *
   * The literals in predicates are rewritten as follows:
   *
   * For each predicate we extract the original column name from
   * query usually appearing on left side of predicate
   * e.g query:
   *
   * SELECT COL3, COL4, COL5
   * FROM FOO
   * WHERE COL1 = 102345 AND COL2 BETWEEN 2000000 AND 30000000
   *
   * The column encountered in predicates are of two types:
   * (1) Global dictionary columns
   * (2) Time columns
   *
   * In case of global dictionary column, we look up global dictionary
   * for (say COL1) and literal value 102345 and get the mapped
   * derived value (say 20347789)
   *
   * In case of time columns (say COL2), we know the data was retained
   * as is in Avro files, so we don't rewrite literal part of predicate
   *
   * With the above two cases, the example query will be rewritten as
   *
   * SELECT DERIVED_COL3, DERIVED_COL4, DERIVED_COL5
   * FROM FOO_ANONYMOUS
   * WHERE DERIVED_COL1 = 20347789 AND DERIVED_COL2 BETWEEN 2000000 AND 30000000
   *
   * IMPORTANT: Based on the current design and implementation of this tool,
   * it will be illegal state during query generation to encounter a column in
   * predicate that is neither a member of global dictionary column set or time
   * column set since the queries were preprocessed to identify the filter columns
   * and user provided us with the set of time columns.
   */

  public static class QueryGenerator {
    private static final String GENERATED_QUERIES_FILE_NAME = "queries.generated";

    String _outputDir;
    String _queryDir;
    String _queryFileName;
    String _tableName;
    private final Set<String> _globalDictionaryColumns;
    private final Set<String> _columnsNotAnonymized;
    // query generator builds these maps by reading files in outputDir
    // these must have been written out earlier during global dictionary
    // building phase
    private final Map<String, Map<Object, Object>> _origToDerivedValueMap;
    private final Map<String, String> _origToDerivedColumnsMap;

    private final Stopwatch _generateQueryWatch = Stopwatch.createUnstarted();

    public QueryGenerator(
        String outputDir,
        String queryDir,
        String queryFile,
        String tableName,
        Set<String> filterColumns,
        Set<String> columnsNotAnonymized) throws Exception {
      _outputDir = outputDir;
      _queryDir = queryDir;
      _queryFileName = queryFile;
      _tableName = tableName;
      _origToDerivedValueMap = new HashMap<>();
      _origToDerivedColumnsMap = new HashMap<>();
      _globalDictionaryColumns = filterColumns;
      _columnsNotAnonymized = columnsNotAnonymized;
      for (String column : columnsNotAnonymized) {
        _globalDictionaryColumns.remove(column);
      }
      loadGlobalDictionariesAndColumnMapping();
    }

    public void generateQueries() throws Exception {
      _generateQueryWatch.start();
      File queryFile = new File(_queryDir + "/" + _queryFileName);
      InputStream inputStream = new FileInputStream(queryFile);
      BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
      String query;
      PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(_queryDir + "/" + GENERATED_QUERIES_FILE_NAME)));
      int count = 0;
      while ((query = reader.readLine()) != null) {
        generateQuery(query, out);
        count++;
      }
      _generateQueryWatch.stop();
      LOGGER.info("Finished generating {} queries. Time taken {}secs. Please see generated query file in {}",
          count,  _generateQueryWatch.elapsed(TimeUnit.SECONDS), _queryDir);
      out.flush();
    }

    @VisibleForTesting
    public String generateQuery(String origQuery, PrintWriter out) {
      AstNode root = Pql2Compiler.buildAst(origQuery);
      StringBuilder genQuery = new StringBuilder();
      genQuery.append("SELECT ");
      SelectAstNode selectAstNode = (SelectAstNode)root;
      List<? extends AstNode> selectChildren = selectAstNode.getChildren();
      boolean rewrittenSelectList = false;
      boolean rewrittenWhere = false;
      for (AstNode child : selectChildren) {
        if (child instanceof OutputColumnListAstNode) {
          // handle select list
          Preconditions.checkState(!rewrittenSelectList, "Select list already rewritten");
          String selectList = rewriteSelectList((OutputColumnListAstNode)child);
          genQuery.append(selectList);
          // handle FROM clause right after rewriting the select list
          genQuery.append(" FROM ").append(_tableName).append(" ");
          rewrittenSelectList = true;
        } else if (child instanceof WhereAstNode) {
          // handle where
          Preconditions.checkState(rewrittenSelectList, "Select list should have been rewritten before rewriting WHERE");
          Preconditions.checkState(!rewrittenWhere, "WHERE already rewritten");
          // should have already rewritten the select list by now
          String filter = rewriteFilter((WhereAstNode)child);
          genQuery.append(filter).append(" ");
          rewrittenWhere = true;
        } else if (child instanceof GroupByAstNode) {
          // handle group by
          String groupBy = rewriteGroupBy((GroupByAstNode)child);
          genQuery.append(groupBy).append(" ");
        }  else if (child instanceof StarColumnListAstNode) {
          // handle SELECT * ....
          genQuery.append("* FROM ").append(_tableName).append(" ");
          rewrittenSelectList = true;
        } else {
          // TODO: handle order by
          // for now throw exception instead of generating incorrect query
          throw new UnsupportedOperationException("Order by is currently not supported");
        }
      }

      if (selectAstNode.isHasLimitClause()) {
        genQuery.append("LIMIT ").append(selectAstNode.getRecordLimit());
      } else if (selectAstNode.isHasTopClause()) {
        genQuery.append("TOP ").append(selectAstNode.getTopN());
      }

      String result = genQuery.toString().trim();

      if (out != null) {
        out.println(result);
      }

      return result;
    }

    private String rewriteGroupBy(GroupByAstNode groupByAstNode) {
      StringBuilder groupBy = new StringBuilder();
      groupBy.append("GROUP BY ");
      List<? extends AstNode> children = groupByAstNode.getChildren();
      int count = 0;
      for (AstNode groupByChild : children) {
        Preconditions.checkState(groupByChild instanceof IdentifierAstNode, "Expecting identifier as child node of group by");
        String column = ((IdentifierAstNode)groupByChild).getName();
        String derivedColumn = getAnonymousColumnName(column);
        if (count > 0) {
          groupBy.append(", ");
        }
        groupBy.append(derivedColumn);
        count++;
      }
      return groupBy.toString();
    }

    private String rewriteSelectList(OutputColumnListAstNode outputColumnListAstNode) {
      StringBuilder selectList = new StringBuilder();
      List<? extends AstNode> outputChildren = outputColumnListAstNode.getChildren();
      int numOutputColumns = outputChildren.size();
      int columnIndex = 0;
      for (AstNode outputChild : outputChildren) {
        if (outputChild instanceof OutputColumnAstNode) {
          // handle SELECT COL1, COL2 ....
          List<? extends AstNode> children = outputChild.getChildren();
          // OutputColumnAstNode represents an output column in the select list
          // the actual output column is represented by the child node of OutputColumnAstNode
          // and there can only be 1 such child
          Preconditions.checkState(children.size() == 1, "Invalid number of children for output column ast node");
          // handle the exact type of output column -- identifier or a function
          rewriteSelectListColumn(children.get(0), selectList, null, columnIndex, numOutputColumns);
        } else {
          throw new UnsupportedOperationException("Invalid type of child node for OuptutColumnListAstNode");
        }
        columnIndex++;
      }
      return selectList.toString();
    }

    private void rewriteSelectListColumn(
        AstNode output,
        StringBuilder selectList,
        AstNode parent,
        int columnIndex,
        int numOutputColumns) {
      if (output instanceof IdentifierAstNode) {
        // OUTPUT COLUMN is identifier (column name)
        IdentifierAstNode identifier = (IdentifierAstNode)output;
        String columnName = identifier.getName();
        String derivedColumnName = getAnonymousColumnName(columnName);
        if (parent instanceof FunctionCallAstNode) {
          // this column is part of a parent function expression in the select list
          selectList.append(derivedColumnName);
        } else {
          // this column is a standalone column in the select list
          // so simply add the derived column name to select list
          if (columnIndex <= numOutputColumns - 2) {
            // multi column select list then separate with comma and space
            selectList.append(derivedColumnName).append(", ");
          } else {
            // single column select list or last column in the select list
            selectList.append(derivedColumnName);
          }
        }
      } else if (output instanceof FunctionCallAstNode) {
        // OUTPUT COLUMN is function
        // handle function nesting by recursing and build the expression incrementally
        // e.g1 SUM(C1)
        // 1. SUM(
        // 2. recurse for C1
        // 3. SUM(DERIVED_C1
        // 4. recursion over
        // 5. SUM(DERIVED_C1)
        //
        // e.g2 SUM(ADD(C1,C2))
        // 1. SUM(
        // 2. recurse for ADD(C1,C2)
        // 3. SUM(ADD(
        // 4. recurse for C1
        // 5. SUM(ADD(DERIVED_C1
        // 6. append "," -> SUM(ADD(DERIVED_C1,
        // 7. recurse for C2
        // 8. SUM(ADD(DERIVED_C1,DERIVED_C2
        // 9. recursion for add's operands finishes
        // 10. finish add expression -> SUM(ADD(DERIVED_C1,DERIVED_C2)
        // 11. recursion for sum's operands finishes
        // 12. finish sum expression -> SUM(ADD(DERIVED_C1,DERIVED_C2))
        // 13. DONE
        FunctionCallAstNode function = (FunctionCallAstNode)output;
        List<? extends AstNode> functionOperands = function.getChildren();
        String name = function.getName();
        selectList.append(name).append("(");
        int count = 0;
        for (AstNode functionOperand : functionOperands) {
          if (count > 0) {
            // add "," before handling the next operand
            selectList.append(",");
          }
          rewriteSelectListColumn(functionOperand, selectList, function, columnIndex, numOutputColumns);
          count++;
        }
        // finish this function expression at the end of recursion
        if (!(parent instanceof FunctionCallAstNode)) {
          if (columnIndex <= numOutputColumns - 2) {
            selectList.append("), ");
          } else {
            selectList.append(")");
          }
        } else {
          selectList.append(")");
        }
      } else if (output instanceof StarExpressionAstNode) {
        // COUNT(*)
        selectList.append("*");
      } else {
        throw new UnsupportedOperationException("Literals are not supported in output columns");
      }
    }

    private String rewriteFilter(WhereAstNode whereAstNode) {
      StringBuilder filter = new StringBuilder();
      filter.append("WHERE ");
      PredicateListAstNode predicateListAstNode = (PredicateListAstNode)whereAstNode.getChildren().get(0);
      int numChildren = predicateListAstNode.getChildren().size();
      List<? extends AstNode> predicateList = predicateListAstNode.getChildren();
      for (int i = 0; i < numChildren; i += 2) {
        PredicateAstNode predicate = (PredicateAstNode) predicateList.get(i);
        rewritePredicate(predicate, filter);
        BooleanOperatorAstNode nextOperator;
        if (i + 1 < numChildren) {
          nextOperator = (BooleanOperatorAstNode) predicateList.get(i + 1);
          rewriteBooleanOperator(nextOperator, filter);
        }
      }
      return filter.toString();
    }

    private void rewriteBooleanOperator(BooleanOperatorAstNode operatorAstNode, StringBuilder filter) {
      if (operatorAstNode.name().equalsIgnoreCase("AND")) {
        filter.append(" AND ");
      } else {
        filter.append(" OR ");
      }
    }

    private void rewritePredicate(PredicateAstNode predicateAstNode, StringBuilder filter) {
      /// get column name participating in the predicate
      String columnName = predicateAstNode.getIdentifier();
      String derivedColumn = getAnonymousColumnName(columnName);
      LiteralAstNode literal;
      if (predicateAstNode instanceof ComparisonPredicateAstNode) {
        // handle COMPARISON
        ComparisonPredicateAstNode comparisonPredicate = (ComparisonPredicateAstNode)predicateAstNode;
        // get operator: <, >, <=, >=, != ....
        String operator = comparisonPredicate.getOperand();
        // get right hand side literal
        literal = comparisonPredicate.getLiteral();
        // build comparison predicate using the column name, operator and literal
        // e.g id <= 2000
        filter.append(derivedColumn).append(" ").append(operator).append(" ");
        rewriteLiteral(columnName, literal, filter);
      } else if (predicateAstNode instanceof BetweenPredicateAstNode) {
        // handle BETWEEN
        List<? extends AstNode> betweenChildren = predicateAstNode.getChildren();
        int numChildren = betweenChildren.size();
        // append column name for BETWEEN and BETWEEN operator itself
        filter.append(derivedColumn).append(" ").append("BETWEEN ");
        int count = 0;
        for (AstNode betweenChild: betweenChildren) {
          Preconditions.checkState(betweenChild instanceof LiteralAstNode, "Child of BetweenAstNode should be literal");
          literal = (LiteralAstNode)betweenChild;
          if (count > 0) {
            // separate two operands for BETWEEN with AND
            // e.g timestamp BETWEEN 1000 AND 1001
            filter.append(" AND ");
          }
          rewriteLiteral(columnName, literal, filter);
          count++;
        }
      } else if (predicateAstNode instanceof InPredicateAstNode) {
        // handle IN
        List<? extends AstNode> inChildren = predicateAstNode.getChildren();
        // append column name for IN and IN operator itself
        filter.append(derivedColumn).append(" ").append("IN ").append("(");
        int numChildren = inChildren.size();
        int count  = 0;
        for (AstNode betweenChild: inChildren) {
          Preconditions.checkState(betweenChild instanceof LiteralAstNode, "Child of InAstNode should be literal");
          literal = (LiteralAstNode)betweenChild;
          //String derivedValue = (String)getGeneratedValueForActualValue(columnName, literal);
          if (count > 0) {
            // separate two operands for IN with ","
            // e.g timestamp IN (a,b,c,d)
            filter.append(",");
          }
          rewriteLiteral(columnName, literal, filter);
          count++;
        }
        // finish the IN predicate
        filter.append(")");
      } else {
        // TODO: handle parenthesised predicate
        // for now throw exception as opposed to generating incorrect query
        throw new UnsupportedOperationException("predicate ast node: " + predicateAstNode.getClass() + " not supported");
      }
    }

    private void rewriteLiteral (String columnName, LiteralAstNode literalAstNode, StringBuilder sb) {
      String literalValue = literalAstNode.getValueAsString();
      String derivedValue = (String)getGeneratedValueForOrigValue(columnName, literalValue);
      if (literalAstNode instanceof StringLiteralAstNode) {
        // quote string literals
        sb.append("\"").append(derivedValue).append("\"");
      } else {
        // numeric literals
        sb.append(derivedValue);
      }
    }

    private void loadGlobalDictionariesAndColumnMapping() throws Exception {
      // load column name mapping
      File mappingFile = new File(_outputDir + "/" + COLUMN_MAPPING_FILE_KEY);
      InputStream inputStream = new FileInputStream(mappingFile);
      BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
      String line;
      while ((line = reader.readLine()) != null) {
        String[] names = line.split(COLUMN_MAPPING_SEPARATOR);
        _origToDerivedColumnsMap.put(names[0], names[1]);
      }

      // load global dictionaries
      for (String column : _globalDictionaryColumns) {
        Map<Object, Object> origToDerived = new HashMap<>();
        _origToDerivedValueMap.put(column, origToDerived);
        File dictionaryFile = new File(_outputDir + "/" + column + DICT_FILE_EXTENSION);
        inputStream = new FileInputStream(dictionaryFile);
        reader = new BufferedReader(new InputStreamReader(inputStream));
        String line1;
        while ((line1 = reader.readLine()) != null) {
          String line2 = reader.readLine();
          origToDerived.put(line1, line2);
        }
      }
    }

    private Object getGeneratedValueForOrigValue(String column, Object origValue) {
      // we use this method for only those columns during query generation that are
      // actually occurring in predicates
      // Two kinds of columns are supported in predicates by query generator
      // (1) columns that are participating in filter (and thus we built global
      //     dictionary for them containing 1-1 mapping between original and
      //     generated value
      // (2) columns that we retained the data for as is.
      if (_columnsNotAnonymized.contains(column)) {
        // if we retained the value, simply return the original value
        return origValue;
      } else if (_globalDictionaryColumns.contains(column)) {
        // if we built global dictionary, then get the generated value from global dictionary
        Map<Object, Object> origToDerived = _origToDerivedValueMap.get(column);
        Preconditions.checkState(origToDerived != null);
        return origToDerived.get(origValue);
      } else {
        // based on the current implementation, we should not come here since
        // we preprocess the queries to identify the filter columns and the columns
        // we retain the data for
        throw new IllegalStateException("Encountered an invalid filter column: " + column);
      }
    }

    private String getAnonymousColumnName(String column) {
      return _origToDerivedColumnsMap.get(column);
    }
  }



  /*****************************************************
   *                                                   *
   *             Filter Column Extractor               *
   *                                                   *
   *****************************************************/


  public static class FilterColumnExtractor {

    public static Set<String> extractColumnsUsedInFilter(
        String queryDir,
        String queryFileName) throws Exception {
      File queryFile = new File(queryDir + "/" + queryFileName);
      InputStream inputStream = new FileInputStream(queryFile);
      BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
      String query;
      Set<String> filterColumns = new HashSet<>();
      while ((query = reader.readLine()) != null) {
        examineWhereClause(query, filterColumns);
      }
      return filterColumns;
    }

    private static void examineWhereClause(String query, Set<String> filterColumns) {
      AstNode root = Pql2Compiler.buildAst(query);
      SelectAstNode selectAstNode = (SelectAstNode)root;
      List<? extends AstNode> selectChildren = selectAstNode.getChildren();
      for (AstNode child : selectChildren) {
        if (child instanceof WhereAstNode) {
          WhereAstNode whereAstNode = (WhereAstNode)child;
          PredicateListAstNode predicateListAstNode = (PredicateListAstNode)whereAstNode.getChildren().get(0);
          int numChildren = predicateListAstNode.getChildren().size();
          List<? extends AstNode> predicateList = predicateListAstNode.getChildren();
          for (int i = 0; i < numChildren; i += 2) {
            PredicateAstNode predicate = (PredicateAstNode) predicateList.get(i);
            filterColumns.add(predicate.getIdentifier());
          }
        }
      }
    }
  }
}
