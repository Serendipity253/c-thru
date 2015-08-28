/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.dq;

import co.cask.cdap.api.Config;
import co.cask.cdap.api.ProgramLifecycle;
import co.cask.cdap.api.app.AbstractApplication;
import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.dataset.table.Put;
import co.cask.cdap.api.dataset.table.Table;
import co.cask.cdap.api.mapreduce.AbstractMapReduce;
import co.cask.cdap.api.mapreduce.MapReduceConfigurer;
import co.cask.cdap.api.mapreduce.MapReduceContext;
import co.cask.cdap.api.schedule.Schedules;
import co.cask.cdap.api.stream.GenericStreamEventData;
import co.cask.cdap.api.templates.plugins.PluginProperties;
import co.cask.cdap.dq.etl.MapReducePipelineConfigurer;
import co.cask.cdap.dq.etl.MapReduceSourceContext;
import co.cask.cdap.dq.functions.BasicAggregationFunction;
import co.cask.cdap.dq.functions.CombinableAggregationFunction;
import co.cask.cdap.dq.rowkey.AggregationsRowKey;
import co.cask.cdap.dq.rowkey.ValuesRowKey;
import co.cask.cdap.template.etl.api.batch.BatchSource;
import co.cask.cdap.template.etl.api.batch.BatchSourceContext;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.hadoop.io.BooleanWritable;
import org.apache.hadoop.io.ByteWritable;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Data Quality Application
 */
public class DataQualityApp extends AbstractApplication<DataQualityApp.ConfigClass> {
  private static final Logger LOG = LoggerFactory.getLogger(DataQualityApp.class);
  private static final Gson GSON = new Gson();
  private static final Type TOKEN_TYPE_MAP_STRING_SET_STRING = new TypeToken<Map<String, Set<String>>>() { }.getType();

  public static final int DEFAULT_WORKFLOW_SCHEDULE_MINUTES = 5;
  public static final String DEFAULT_DATASET_NAME = "dataQuality";

  /**
   * Configuration Class for the application
   * Sets following fields: aggregationName, fields (a comma separated list
   * of the fields we want to aggregate over), workflowScheduleMinutes, source, datasetName,
   * inputFormat, and schema.
   */
  public static class ConfigClass extends Config {
    private int workflowScheduleMinutes;
    private DataQualitySource source;
    private String datasetName;
    private Map<String, Set<String>> fieldAggregations;

    public ConfigClass() {
      this.workflowScheduleMinutes = DEFAULT_WORKFLOW_SCHEDULE_MINUTES;
      // By default use a Stream BatchSource with 'logStream' as the name and 'clf' format
      this.source = new DataQualitySource("Stream", "logStream", ImmutableMap.of(
        "name", "logStream", "duration", DEFAULT_WORKFLOW_SCHEDULE_MINUTES + "m", "format", "clf"));
      this.datasetName = DEFAULT_DATASET_NAME;
      Set<String> aggSet = Sets.newHashSet("DiscreteValuesHistogram");
      this.fieldAggregations = ImmutableMap.of("content_length", aggSet);
    }

    public ConfigClass(int workflowScheduleMinutes, DataQualitySource source,
                       String datasetName, Map<String, Set<String>> fieldAggregations) {
      Preconditions.checkArgument(workflowScheduleMinutes > 0, "Workflow Frequency in minutes (>0) should be provided");
      Preconditions.checkNotNull(source, "Configuration for DataQualityApp Source is missing");
      Preconditions.checkArgument(!Strings.isNullOrEmpty(source.getName()),
                                  "Data Quality source name should not be null or empty");
      Preconditions.checkArgument(!Strings.isNullOrEmpty(source.getId()),
                                  "Data Quality source id should not be null or empty");
      Preconditions.checkArgument(!Strings.isNullOrEmpty(datasetName),
                                  "Output Dataset name should be not be null or empty");
      this.workflowScheduleMinutes = workflowScheduleMinutes;
      this.source = source;
      this.datasetName = datasetName;
      this.fieldAggregations = fieldAggregations;
    }
  }

  @Override
  public void configure() {
    ConfigClass configObj = getContext().getConfig();
    Integer scheduleMinutes = configObj.workflowScheduleMinutes;
    addMapReduce(new FieldAggregator(configObj.source,
                                     configObj.datasetName,
                                     configObj.fieldAggregations));
    setName("DataQualityApp");
    setDescription("Application with MapReduce job to determine the data quality in a Batch Source");
    createDataset(configObj.datasetName, Table.class);
    addService(new DataQualityService(configObj.datasetName));
    addWorkflow(new DataQualityWorkflow());
    String schedule = "*/" + scheduleMinutes + " * * * *";
    scheduleWorkflow(Schedules.createTimeSchedule("Data Quality Workflow Schedule",
                                                  "Schedule execution every" + scheduleMinutes
                                                    + " min", schedule), "DataQualityWorkflow");
  }

  /**
   * Map Reduce job that ingests a stream of data and builds an aggregation of the ingested data
   * that maps timestamp, sourceId, field type and field value to frequency.
   */
  public static final class FieldAggregator extends AbstractMapReduce {
    // Need to use the separator used in MapReduceSourceContext
    private static final String PLUGIN_ID = "input:";
    private final String datasetName;
    private final Map<String, Set<String>> fieldAggregations;

    private DataQualitySource source;

    public FieldAggregator(DataQualitySource source, String datasetName,
                           Map<String, Set<String>> fieldAggregations) {
      this.source = source;
      this.datasetName = datasetName;
      this.fieldAggregations = fieldAggregations;
    }

    @Override
    public void configure() {
      super.configure();
      final MapReduceConfigurer mrConfigurer = getConfigurer();
      BatchSource batchSource = usePlugin("source", source.getName(), PLUGIN_ID,
                                          PluginProperties.builder().addAll(source.getProperties()).build());
      Preconditions.checkNotNull(batchSource, "Could not find plugin %s of type 'source'", source.getName());
      // We use pluginId as the prefixId
      batchSource.configurePipeline(new MapReducePipelineConfigurer(mrConfigurer, PLUGIN_ID));
      setName("FieldAggregator");
      setOutputDataset(datasetName);
      setProperties(ImmutableMap.<String, String>builder()
                      .put("fieldAggregations", GSON.toJson(fieldAggregations))
                      .put("sourceId", source.getId())
                      .build());
    }

    @Override
    public void beforeSubmit(MapReduceContext context) throws Exception {
      Job job = context.getHadoopJob();
      job.setMapperClass(AggregationMapper.class);
      job.setReducerClass(AggregationReducer.class);
      BatchSource batchSource = context.newInstance(PLUGIN_ID);
      // TODO: Figure out metrics to be passed in
      BatchSourceContext sourceContext = new MapReduceSourceContext(context, null, PLUGIN_ID);
      batchSource.prepareRun(sourceContext);
    }
  }

  /**
   * Take in data and output a field type as a key and a DataQualityWritable containing the field value
   * as an associated value.
   */
  public static class AggregationMapper extends
    Mapper<LongWritable, GenericStreamEventData<StructuredRecord>, Text, DataQualityWritable>
    implements ProgramLifecycle<MapReduceContext> {
    private Set<String> fieldsSet = new HashSet<>();

    @Override
    public void initialize(MapReduceContext mapReduceContext) throws Exception {
      Map<String, Set<String>> fieldAggregations =
        GSON.fromJson(mapReduceContext.getSpecification().getProperties().get("fieldAggregations"),
                      TOKEN_TYPE_MAP_STRING_SET_STRING);
      if (fieldAggregations != null) {
        fieldsSet = fieldAggregations.keySet();
      }
    }

    @Override
    public void map(LongWritable key, GenericStreamEventData<StructuredRecord> event, Context context)
      throws IOException, InterruptedException {
      StructuredRecord body = event.getBody();
      for (Schema.Field field : body.getSchema().getFields()) {
        String fieldName = field.getName();
        Object fieldVal = body.get(fieldName);
        if (fieldVal != null) {
          DataQualityWritable outputValue;
          if (field.getSchema().isNullable()) {
            outputValue = getOutputValue(field.getSchema().getNonNullable().getType(), fieldVal);
          } else {
            outputValue = getOutputValue(field.getSchema().getType(), fieldVal);
          }
          if (outputValue != null && (fieldsSet.contains(fieldName) || fieldsSet.isEmpty())) {
            context.write(new Text(fieldName), outputValue);
          }
        }
      }
    }

    @Nullable
    private DataQualityWritable getOutputValue(Schema.Type type, Object o) {
      DataQualityWritable writable = new DataQualityWritable();
      switch (type) {
        case STRING:
          writable.set(new Text((String) o));
          break;
        case INT:
          writable.set(new IntWritable((Integer) o));
          break;
        case LONG:
          writable.set(new LongWritable((Long) o));
          break;
        case DOUBLE:
          writable.set(new DoubleWritable((Double) o));
          break;
        case FLOAT:
          writable.set(new FloatWritable((Float) o));
          break;
        case BOOLEAN:
          writable.set(new BooleanWritable((Boolean) o));
          break;
        case BYTES:
          writable.set(new ByteWritable((Byte) o));
          break;
        default:
          return null;
      }
      return writable;
    }

    @Override
    public void destroy() {
    }
  }

  /**
   * Generate and write an aggregation with the data collected by the mapper
   */
  public static class AggregationReducer extends Reducer<Text, DataQualityWritable, byte[], Put>
    implements ProgramLifecycle<MapReduceContext> {
    private static final Gson GSON = new Gson();
    private String sourceId;
    private Map<String, Set<String>> fieldAggregations;
    long timeKey = 0;

    @Override
    public void initialize(MapReduceContext mapReduceContext) throws Exception {
      timeKey = mapReduceContext.getLogicalStartTime();
      sourceId = mapReduceContext.getSpecification().getProperties().get("sourceId");
      fieldAggregations = GSON.fromJson(mapReduceContext
                                          .getSpecification().getProperties().get("fieldAggregations"),
                                        TOKEN_TYPE_MAP_STRING_SET_STRING);
    }

    @Override
    public void reduce(Text key, Iterable<DataQualityWritable> values, Context context)
      throws IOException, InterruptedException {
      LOG.debug("timestamp: {}", timeKey);
      Set<String> aggregationTypesSet = fieldAggregations.get(key.toString());
      List<AggregationTypeValue> aggregationTypeValueList = new ArrayList<>();
      AggregationsRowKey aggregationsRowKey = new AggregationsRowKey(timeKey, sourceId);
      byte[] fieldColumnKey = Bytes.toBytes(key.toString());
      for (String aggregationType : aggregationTypesSet) {
        boolean isCombinable = true;
        try {
          Class<?> aggregationClass = Class.forName("co.cask.cdap.dq.functions." + aggregationType);
          BasicAggregationFunction instance = (BasicAggregationFunction) aggregationClass.newInstance();
          isCombinable = instance instanceof CombinableAggregationFunction;
          for (DataQualityWritable value : values) {
            instance.add(value);
          }
          ValuesRowKey valuesRowKey = new ValuesRowKey(timeKey, key.toString(), sourceId);
          context.write(valuesRowKey.getTableRowKey(), new Put(valuesRowKey.getTableRowKey(),
                                                               Bytes.toBytes(aggregationType),
                                                               instance.aggregate()));

          AggregationTypeValue aggregationTypeValue = new AggregationTypeValue(aggregationType, isCombinable);
          aggregationTypeValueList.add(aggregationTypeValue);
        } catch (ClassNotFoundException | RuntimeException | InstantiationException | IllegalAccessException e) {
          throw new RuntimeException(e);
        }
      }
      byte[] aggregationTypeListBytes = Bytes.toBytes(GSON.toJson(aggregationTypeValueList));
      context.write(aggregationsRowKey.getTableRowKey(),
                    new Put(aggregationsRowKey.getTableRowKey(), fieldColumnKey, aggregationTypeListBytes));
      //TODO: There are issues if multiple apps use the same source and same fields, but different aggregations
    }

    @Override
    public void destroy() {
    }
  }
}
