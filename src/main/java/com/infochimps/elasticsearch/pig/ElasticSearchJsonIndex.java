package com.infochimps.elasticsearch.pig;

import com.infochimps.elasticsearch.ElasticSearchOutputFormat;
import com.infochimps.elasticsearch.hadoop.util.HadoopUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.pig.ResourceSchema;
import org.apache.pig.StoreFunc;
import org.apache.pig.StoreFuncInterface;
import org.apache.pig.data.Tuple;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.elasticsearch.common.jackson.JsonParseException;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
   Pig storefunc for Elastic Search. Takes json data <b>only</b>.
   <p>
   USAGE:
   <p>
   STORE records INTO ElasticSearchJsonIndex();
   STORE records INTO ElasticSearchJsonIndex(idFieldName, bulkSize);
   STORE records INTO ElasticSearchJsonIndex(idFieldName, bulkSize, esConfig);
   STORE records INTO ElasticSearchJsonIndex(idFieldName, bulkSize, esConfig, esPlugins);

   where:

   idFieldName = Named field of the record to use as the record id. If none is passed in
                 then the record is assumed to have no id.
   bulkSize    = Number of records for ElasticSearchOutputFormat to batch up before sending
                 a bulk index request to Elastic Search. Default: 1000.
   esConfig    = Full path to local elasticsearch.yml. Default: /etc/elasticsearch/elasticsearch.yml
   esPlugins   = Full path to local elastic search plugins dir. Default: /usr/local/share/elasticsearch/plugins
   
 */
public class ElasticSearchJsonIndex extends StoreFunc implements StoreFuncInterface {

    private static final Log LOG = LogFactory.getLog(ElasticSearchJsonIndex.class);
    
    protected RecordWriter writer = null;
    protected ObjectMapper mapper = new ObjectMapper();
    protected String idFieldName;
    protected String bulkSize;
    protected String esConfig;
    protected String esPlugins;

    // For hadoop configuration
    private static final String ES_INDEX_NAME = "elasticsearch.index.name";
    private static final String ES_BULK_SIZE = "elasticsearch.bulk.size";
    private static final String ES_IS_JSON = "elasticsearch.is_json";
    private static final String ES_ID_FIELD_NAME = "elasticsearch.id.field.name";
    private static final String ES_FIELD_NAMES = "elasticsearch.field.names";
    private static final String ES_ID_FIELD = "elasticsearch.id.field";
    private static final String ES_OBJECT_TYPE = "elasticsearch.object.type";

    // Other string constants
    private static final String SLASH = "/";
    private static final String NO_ID_FIELD = "-1";
    private static final String LOCAL_SCHEME = "file://";    
    private static final String DEFAULT_BULK = "1000";
    private static final String DEFAULT_ES_CONFIG = "/etc/elasticsearch/elasticsearch.yml";
    private static final String DEFAULT_ES_PLUGINS = "/usr/local/share/elasticsearch/plugins";
    private static final String ES_CONFIG_HDFS_PATH = "/tmp/elasticsearch/elasticsearch.yml";
    private static final String ES_PLUGINS_HDFS_PATH = "/tmp/elasticsearch/plugins";
    
    public ElasticSearchJsonIndex() {
        this(NO_ID_FIELD, DEFAULT_BULK);
    }

    public ElasticSearchJsonIndex(String idFieldName, String bulkSize) {
        this(idFieldName, bulkSize, DEFAULT_ES_CONFIG);
    }

    public ElasticSearchJsonIndex(String idFieldName, String bulkSize, String esConfig) {
        this(idFieldName, bulkSize, esConfig, DEFAULT_ES_PLUGINS);
    }

    public ElasticSearchJsonIndex(String idFieldName, String bulkSize, String esConfig, String esPlugins) {
        this.idFieldName = idFieldName;
        this.bulkSize    = bulkSize;
        this.esConfig    = esConfig;
        this.esPlugins   = esPlugins;
    }

    @Override
    public void checkSchema(ResourceSchema s) throws IOException {
    }

    /**
       Look at passed in location and configuration and set options. Note that, since this
       is called more than once, we need to make sure and not change anything we've already
       set.
     */
    @Override
    public void setStoreLocation(String location, Job job) throws IOException {
        String[] es_store  = location.substring(5).split(SLASH);
        if (es_store.length != 2) {
            throw new RuntimeException("Please specify a valid elasticsearch index, eg. es://myindex/myobj");
        }
        Configuration conf = job.getConfiguration();
        // Only set if we haven't already
        if (conf.get(ES_INDEX_NAME) == null) {
            try {
                job.getConfiguration().set(ES_INDEX_NAME, es_store[0]);
                job.getConfiguration().set(ES_OBJECT_TYPE, es_store[1]);
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new RuntimeException("You must specify both an index and an object type.");
            }
            job.getConfiguration().setBoolean(ES_IS_JSON, true);
            job.getConfiguration().set(ES_BULK_SIZE, bulkSize);
            job.getConfiguration().set(ES_ID_FIELD_NAME, idFieldName);

            // Adds the elasticsearch.yml file (esConfig) to the distributed cache
            try {
                Path hdfsConfigPath = new Path(ES_CONFIG_HDFS_PATH);
                Path hdfsPluginsPath = new Path(ES_PLUGINS_HDFS_PATH);

                HadoopUtils.uploadLocalFile(new Path(LOCAL_SCHEME+esConfig), hdfsConfigPath, job.getConfiguration());
                HadoopUtils.shipFileIfNotShipped(hdfsConfigPath, job.getConfiguration());

                HadoopUtils.uploadLocalFile(new Path(LOCAL_SCHEME+esPlugins), hdfsPluginsPath, job.getConfiguration());
                HadoopUtils.shipArchiveIfNotShipped(hdfsPluginsPath, job.getConfiguration());                
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public OutputFormat getOutputFormat() throws IOException {
        return new ElasticSearchOutputFormat();
    }

    // Suppressing unchecked warnings for RecordWriter, which is not parameterized by StoreFuncInterface
    @Override
    public void prepareToWrite(@SuppressWarnings("rawtypes") RecordWriter writer) throws IOException {
        this.writer = writer;
    }

    /**
       Map a tuple object into a map-writable object for elasticsearch.
     */
    @SuppressWarnings("unchecked")
    @Override
    public void putNext(Tuple t) throws IOException {
        if (!t.isNull(0)) {
            MapWritable record  = new MapWritable();
            String jsonData = t.get(0).toString();

            // parse json data and put into mapwritable record
            try {
                HashMap<String,Object> data = mapper.readValue(jsonData, HashMap.class);
                record = (MapWritable)toWritable(data);
            } catch (JsonParseException e) {
                e.printStackTrace();
            } catch (JsonMappingException e) {
                e.printStackTrace();
            }
            try {
                writer.write(NullWritable.get(), record);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }
    }

    /**
       Recursively converts an arbitrary object into the appropriate writable. Please enlighten me if there is an existing
       method for doing this.      
     */
    private Writable toWritable(Object thing) {
        if (thing instanceof String) {
            return new Text((String)thing);
        } else if (thing instanceof Long) {
            return new LongWritable((Long)thing);
        } else if (thing instanceof Integer) {
            return new IntWritable((Integer)thing);
        } else if (thing instanceof Double) {
            return new DoubleWritable((Double)thing);
        } else if (thing instanceof Float) {
            return new FloatWritable((Float)thing);
        } else if (thing instanceof Map) {
            MapWritable result = new MapWritable();
            for (Map.Entry<String,Object> entry : ((Map<String,Object>)thing).entrySet()) {
                result.put(new Text(entry.getKey().toString()), toWritable(entry.getValue()));
            }
            return result;
        } else if (thing instanceof List) {
            if (((List)thing).size() > 0) {
                Object first = ((List)thing).get(0);
                Writable[] listOfThings = new Writable[((List)thing).size()];
                for (int i = 0; i < listOfThings.length; i++) {
                    listOfThings[i] = toWritable(((List)thing).get(i));
                }
                return new ArrayWritable(toWritable(first).getClass(), listOfThings);
            }
        }
        return NullWritable.get();
    }
        
    @Override
    public void cleanupOnFailure(String location, Job job) throws IOException {
    }
}
