--
-- This tests loading data from elasticsearch
--

%default ES_HOME    '/opt/es'
%default ES_JAR_DIR '$ES_HOME/lib'
%default ES_YAML    '$ES_HOME/config/elasticsearch.yml'
%default PLUGINS    '$ES_HOME/plugins'

%default INDEX      'foo_test'
%default OBJ        'foo'        

register $ES_JAR_DIR/*.jar;
register target/wonderdog*.jar;

--
-- Will load the data as (doc_id, contents) tuples where the contents is the original json source from elasticsearch
--
foo = LOAD 'es://$INDEX/$OBJ' USING com.infochimps.elasticsearch.pig.ElasticSearchStorage('$ES_YAML', '$PLUGINS') AS (doc_id:chararray, contents:chararray);
DUMP foo;
