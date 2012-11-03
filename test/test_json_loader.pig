--
-- This tests the json indexer. Run in local mode with 'pig -x local test/test_json_loader.pig'
--
	
%default ES_HOME    '/opt/es'
%default ES_JAR_DIR '$ES_HOME/lib'
%default ES_YAML    '$ES_HOME/config/elasticsearch.yml'
%default PLUGINS    '$ES_HOME/plugins'

%default INDEX      'foo_test'
%default OBJ        'foo'        

register $ES_JAR_DIR/*.jar;
register target/wonderdog*.jar;

foo = LOAD 'test/foo.json' AS (data:chararray);

--
-- Query parameters let elasticsearch output format that we're storing json data and
-- want to use a bulk request size of 1 record.
--
STORE foo INTO 'es://$INDEX/$OBJ?json=true&size=1' USING com.infochimps.elasticsearch.pig.ElasticSearchStorage('$ES_YAML', '$PLUGINS');
